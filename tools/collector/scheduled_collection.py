"""Bounded yesterday-only scheduler. Production invocation is systemd, never a browser request."""
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
from zoneinfo import ZoneInfo

ROOT = Path('/opt/scsj-trial/live-d126')
CONFIG = '.local/authorized-collection/00000000000000000000000000000001/configuration'
SCHEMA = 'SCSJ_LOCAL_CLOUD'
TABLES = ('SCSJ_FACT_INPUT_VERSION', 'SCSJ_MATERIAL_DAY_FACT', 'SCSJ_FACT_DAY_STATE')


class Rejected(Exception):
    pass


def require(value, code='PRECHECK_REJECTED'):
    if not value:
        raise Rejected(code)


def stamp():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def read(path):
    return json.loads(path.read_bytes())


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def save(path, value):
    """Atomic state replacement; immutable evidence uses separate unique directories."""
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    require(path.parent.resolve() == path.parent and not path.is_symlink(), 'UNSAFE_PATH')
    tmp = path.parent / ('.' + uuid.uuid4().hex + '.tmp')
    with tmp.open('x', encoding='utf-8') as out:
        json.dump(value, out, ensure_ascii=False)
        out.flush()
        os.fsync(out.fileno())
    os.replace(tmp, path)
    if sys.platform == 'linux':
        fd = os.open(path.parent, os.O_DIRECTORY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)


def slot(now):
    local = now.astimezone(ZoneInfo('Asia/Shanghai'))
    regular = local.hour in (9, 10, 11, 12) and local.minute < 5
    temporary_day = local.date().isoformat() == '2026-10-16'
    temporary_1420 = temporary_day and local.hour == 14 and 20 <= local.minute < 25
    temporary_1400 = temporary_day and local.hour == 14 and local.minute < 5
    require(regular or temporary_1420 or temporary_1400, 'OUTSIDE_SCHEDULE_SLOT')
    # Keep historical hour slot keys stable, but the new 13:40 slot must not
    # collide with today's earlier 14:00 attempt.
    point = local.strftime('%Y-%m-%dT%H') + (':20' if temporary_1420 else '')
    return (local.date() - dt.timedelta(days=1)).isoformat(), point


class Runner:
    def __init__(self, root, ops):
        self.root, self.ops = root, ops
        self.base = root / '.local/collection-scheduler'
        self.public = {}

    def publish(self):
        self.public['updatedAt'] = stamp()
        save(self.base / 'status.json', self.public)

    def update(self, state, status=None, persist=True, **values):
        if status:
            state['status'] = status
        state.update(values)
        if state['status'] != 'RETRY_WAIT':
            state.pop('retryNotBefore', None)
        state['updatedAt'] = stamp()
        if persist:
            save(self.state_path(state), state)
        exposed = {k: state[k] for k in ('projectId', 'statDate', 'status', 'updatedAt',
                    'errorCode', 'retryNotBefore', 'rows') if k in state and state[k] is not None}
        self.public['projects'] = [p for p in self.public['projects'] if p['projectId'] != state['projectId']] + [exposed]
        self.publish()

    def state_path(self, state):
        key = hashlib.sha256((state['projectId'] + ':' + state['accountId']).encode()).hexdigest()
        return self.base / 'days' / state['statDate'] / (key + '.json')

    def run(self, now):
        day, point = slot(now)
        self.public = dict(schemaVersion='collection-scheduler-v1', updatedAt=stamp(),
                           statDate=day, status='RUNNING', projects=[])
        self.publish()
        self.ops.heartbeat = self.publish
        try:
            plan, projects = self.ops.plan(day)
            require(0 < len(projects) <= 32, 'PROJECT_COUNT_REJECTED')
        except Exception:
            self.public['status'] = 'FAILED'
            self.publish()
            raise
        pending = list(projects)
        # A waiting child's stack/process stays frozen. Only this parent may
        # dispatch another project, and its entire pipeline completes before
        # the child's retry permit is released. No parallel network/imports.
        def next_project():
            if not pending:
                return
            project = pending.pop(0)
            state = dict(projectId=project['id'], accountId=project['advertiserId'], statDate=day,
                         status='PENDING', attempts=[])
            path = self.state_path(state)
            try:
                if path.exists() or path.is_symlink():
                    require(path.resolve() == path, 'UNSAFE_STATE_PATH')
                    loaded = read(path)
                    require(loaded['projectId'] == project['id'] and loaded['accountId'] == project['advertiserId']
                            and loaded['statDate'] == day and isinstance(loaded['attempts'], list), 'STATE_IDENTITY_CHANGED')
                    state = loaded
            except Exception:
                self.update(state, 'REVIEW_REQUIRED', persist=False, errorCode='STATE_RECORD_INVALID')
                return
            try:
                self.project(state, plan, project, point)
            except Exception as error:
                # Any exception after intent-to-import means outcome unknown; never silently recollect.
                review = state['status'] in ('IMPORTING', 'VERIFYING', 'REVIEW_REQUIRED')
                code = str(error) if isinstance(error, Rejected) else 'STEP_FAILED'
                if not re.fullmatch('[A-Z0-9_]{1,80}', code):
                    code = 'STEP_FAILED'
                self.update(state, 'REVIEW_REQUIRED' if review else 'FAILED', errorCode=code)
        def yield_projects():
            deadline = time.monotonic() + 300
            while pending and time.monotonic() < deadline:
                next_project()
        self.ops.yield_project = yield_projects
        while pending:
            next_project()
        states = [p['status'] for p in self.public['projects']]
        self.public['status'] = ('REVIEW_REQUIRED' if 'REVIEW_REQUIRED' in states else
            'COMPLETE' if all(s == 'VERIFIED' for s in states) else
            'PARTIAL' if 'VERIFIED' in states else 'FAILED')
        self.publish()
        return self.public

    def project(self, state, plan, project, point):
        old = state['status']
        if old in ('VERIFIED', 'ALREADY_PRESENT', 'REVIEW_REQUIRED'):
            self.update(state)
            return
        if old in ('IMPORTING', 'VERIFYING'):
            # Last process may have committed. Check exact saved source/config, do not issue Import again.
            self.update(state, 'VERIFYING')
            self.ops.validate_source(state)
            rows = self.ops.verify(state)
            self.update(state, 'VERIFIED', rows=rows, errorCode=None, retryNotBefore=None)
            return
        if old in ('COLLECTING', 'RETRY_WAIT'):
            # Aborted collector may have published but not saved its result. Recover its private output only.
            recovered = self.ops.recover_collection(state)
            if recovered:
                state['receipt'] = recovered
            else:
                self.update(state, 'REVIEW_REQUIRED', errorCode='COLLECTION_INTERRUPTED')
                return
        if point in state['attempts']:
            self.update(state)
            return
        state['attempts'].append(point)
        state.pop('errorCode', None)
        state.pop('retryNotBefore', None)
        if not state.get('receipt'):
            if self.ops.exists(project, state['statDate']):
                self.update(state, 'ALREADY_PRESENT', errorCode='EXISTING_FACTS_NOT_OWNED')
                return
            state['plan'] = str(plan)
            state['attemptDir'] = str(self.base / 'attempts' / uuid.uuid4().hex)
            self.update(state, 'COLLECTING')
            def progress(status, retry=None):
                self.update(state, status, retryNotBefore=retry)
            state['receipt'] = self.ops.collect(state, progress)
            self.update(state, 'SOURCE_READY')
        self.ops.validate_source(state)
        self.ops.prepare(state)
        self.update(state, 'SOURCE_READY')
        self.ops.backup(state['statDate'])
        self.ops.validate_source(state)
        require(not self.ops.exists(project, state['statDate']), 'TARGET_APPEARED_BEFORE_IMPORT')
        self.update(state, 'IMPORTING')
        self.ops.import_source(state)
        self.update(state, 'VERIFYING')
        rows = self.ops.verify(state)
        self.ops.validate_source(state)
        self.update(state, 'VERIFIED', rows=rows, errorCode=None, retryNotBefore=None)


class LinuxOps:
    def __init__(self, root, client):
        self.root, self.client = root, client
        self.jar = root / 'backend/target/data-dashboard-backend-0.0.1-SNAPSHOT.jar'
        self.heartbeat = lambda: None
        sys.path.insert(0, str(root / 'tools/collector'))
        import collect
        self.collector = collect
        self.baseline = read(self.private(root / '.local/runtime-plan.json'))
        require(self.baseline['workspaceRoot'] == str(root) and self.baseline['schemaName'] == SCHEMA)
        require(self.private(Path(self.baseline['credentialsPath'])) == root / '.local/db-credentials.json')

    def private(self, path):
        require(path.resolve(strict=True) == path and path.is_relative_to(self.root / '.local'), 'UNSAFE_PRIVATE_PATH')
        return path

    def command(self, args, timeout=240, progress=None, log=None, read_output=True):
        """No untrusted process output reaches journal; poll heartbeat even while JVM/backup works."""
        if log is None:
            with tempfile.TemporaryFile() as output:
                return self._wait(args, timeout, output, progress, read_output)
        log.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        require(log.parent.resolve() == log.parent and not log.exists(), 'OUTPUT_ALREADY_EXISTS')
        with log.open('x+b') as output:
            return self._wait(args, timeout, output, progress, read_output)

    def _wait(self, args, timeout, output, progress, read_output=True):
        process = subprocess.Popen(args, cwd=self.root, stdout=output, stderr=subprocess.DEVNULL)
        deadline, consumed = time.monotonic() + timeout, 0
        try:
            while process.poll() is None:
                self.heartbeat()
                if progress:
                    chunk = os.pread(output.fileno(), 65536, consumed)
                    complete = chunk.rfind(b'\n') + 1
                    for line in chunk[:complete].splitlines():
                        try:
                            event = json.loads(line)
                            if event.get('status') == 'RETRY_WAIT':
                                started = time.monotonic()
                                progress('RETRY_WAIT', event.get('retryNotBefore'), event.get('retryCount'))
                                # Time spent on other projects is not this
                                # parked child's active timeout budget.
                                deadline += time.monotonic() - started
                            elif event.get('status') == 'RETRY_RUNNING':
                                progress('COLLECTING', None)
                        except (ValueError, AttributeError):
                            pass
                    consumed += complete
                require(time.monotonic() < deadline, 'STEP_TIMEOUT')
                time.sleep(2)
            output.seek(0)
            data = output.read() if read_output else b''
            require(process.returncode == 0, 'PROCESS_FAILED')
            return data.decode('utf-8')
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()

    def java(self, main, arguments, prefix):
        output = self.command(['/usr/bin/java', '-Xms32m', '-Xmx256m',
            '-Dloader.main=cn.datadashboard.local.' + main, '-cp', str(self.jar),
            'org.springframework.boot.loader.launch.PropertiesLauncher', *map(str, arguments)])
        paths = [line[len(prefix):] for line in output.splitlines() if line.startswith(prefix)]
        require(len(paths) == 1, 'JAVA_RECEIPT_MISSING')
        path = self.private(Path(paths[0]))
        return read(path), path

    def plan(self, day):
        document, path = self.java('LocalCollectorPlanMain', [self.root / CONFIG, day, '--dry-run'],
                                   'Local collector dry-run plan: ')
        require(document['statDate'] == day and document['timeZone'] == 'Asia/Shanghai')
        projects = document['projects']
        require(projects, 'NO_ENABLED_PROJECTS')
        require(len({p['advertiserId'] for p in projects}) == len(projects), 'DUPLICATE_ACCOUNT')
        for p in projects:
            self.collector.load_plan(self.root, str(path), p['id'])
        return path, projects

    def exists(self, project, day):
        account = project['advertiserId']
        require(re.fullmatch('[1-9][0-9]{0,29}', account) and dt.date.fromisoformat(day).isoformat() == day)
        query = 'SELECT ' + '+'.join(f"(SELECT COUNT(*) FROM {SCHEMA}.{t} WHERE account_id='{account}' AND stat_date='{day}')" for t in TABLES) + ';'
        result = self.command(['/usr/bin/mysql', '--defaults-extra-file=' + str(self.client), '--batch',
                               '--skip-column-names', '--execute', query], 60).strip()
        require(result.isdigit(), 'DATABASE_CHECK_FAILED')
        return int(result) > 0

    def collect(self, state, progress):
        log = Path(state['attemptDir']) / 'collector-output.jsonl'
        permit = log.parent / 'retry-permit.json'
        granted = 0
        def parked(status, retry=None, retry_count=None):
            nonlocal granted
            progress(status, retry)
            if status == 'RETRY_WAIT':
                require(type(retry_count) is int and retry_count == granted + 1 and retry_count <= 12,
                        'RETRY_SEQUENCE_INVALID')
                self.yield_project()
                if granted:
                    require(read(self.private(permit)) == {'resume': True, 'retryCount': granted},
                            'RETRY_PERMIT_CHANGED')
                else:
                    require(not permit.exists() and not permit.is_symlink(), 'RETRY_PERMIT_ALREADY_EXISTS')
                save(permit, {'resume': True, 'retryCount': retry_count})
                granted = retry_count
        try:
            self.command([sys.executable, '-B', str(self.root / 'tools/collector/collect.py'),
                '--plan', state['plan'], '--project', state['projectId'], '--execute',
                '--credentials', str(self.root / '.local/collector-auth/credentials.json'),
                '--retry-gate', str(permit)], 2400, parked, log)
        except Rejected as error:
            if str(error) == 'PROCESS_FAILED':
                raise Rejected(self.collection_failure(state)) from None
            raise
        receipt = self.recover_collection(state)
        require(receipt, 'COLLECTION_NOT_COMPLETE')
        return receipt

    def collection_failure(self, state):
        """Expose only verified protocol location, never provider text or private identifiers."""
        try:
            log = self.private(Path(state['attemptDir']) / 'collector-output.jsonl')
            require(log.stat().st_size <= 1024 * 1024)
            result = json.loads(log.read_text().splitlines()[-1])
            require(result['status'] == 'FAILED' and result['errorCode'] == 'PROTOCOL_REJECTED')
            path = self.private(Path(result['receipt']))
            require(path.name == 'receipt.json' and path.parent.parent == self.root / '.local/collector-runs'
                    and re.fullmatch('[0-9a-f]{32}', path.parent.name) and path.stat().st_size <= 1024 * 1024)
            receipt = read(path)
            plan, project, _, sources = self.collector.load_plan(self.root, state['plan'], state['projectId'])
            require(plan['statDate'] == state['statDate'] and project['advertiserId'] == state['accountId'])
            require(receipt['status'] == 'FAILED' and receipt['errorCode'] == 'PROTOCOL_REJECTED'
                    and receipt['databaseImported'] is False and receipt['transportEvidence'] == 'HTTP_BYTES'
                    and receipt['configurationRevision'] == plan['revision'] and receipt['sources'] == sources
                    and receipt['projectRef'] == state['projectId'] and receipt['advertiserId'] == state['accountId']
                    and receipt['statDate'] == state['statDate'])
            for item in sources + [receipt['failureEvidence']]:
                source = Path(item['absolutePath'])
                require(source.resolve(strict=True) == source and source.is_relative_to(self.root))
                require(self.collector.evidence(source) == item)
            require(Path(receipt['failureEvidence']['absolutePath']) == path.parent / 'failure-response.http.json')
            d = receipt['diagnostic']
            facts = d['facts']
            require(d['stage'] == 'tools/call' and d['rule'] == 'BUSINESS_CODE_ZERO'
                    and facts['codeType'] == 'integer' and type(facts['numericCode']) is int
                    and facts['numericCode'] == 51010)
            require(d['group'] in ('base', 'video', 'assist', 'ratio1', 'ratio2')
                    and type(d['page']) is int and 1 <= d['page'] <= 100000
                    and type(facts['retryCount']) is int and 0 <= facts['retryCount'] <= 12)
            return f"PLATFORM_51010_{d['group'].upper()}_PAGE_{d['page']}_RETRIES_{facts['retryCount']}"
        except Exception:
            return 'PROCESS_FAILED'

    def recover_collection(self, state):
        log = Path(state['attemptDir']) / 'collector-output.jsonl'
        if not log.exists():
            return None
        for line in reversed(self.private(log).read_text().splitlines()):
            try:
                result = json.loads(line)
            except ValueError:
                continue
            if result.get('status') == 'COLLECTED_NOT_IMPORTED':
                return str(self.private(Path(result['receipt'])))
        return None

    def validate_source(self, state):
        plan_path = self.private(Path(state['plan']))
        plan, project, _, sources = self.collector.load_plan(self.root, str(plan_path), state['projectId'])
        require(project['advertiserId'] == state['accountId'] and plan['statDate'] == state['statDate'])
        pointer = Path(plan['configurationEvidence']['absolutePath']).parent.parent / 'current.json'
        require(read(self.private(pointer))['revision'] == plan['revision'], 'CONFIGURATION_CHANGED')
        receipt_path = self.private(Path(state['receipt']))
        receipt = read(receipt_path)
        require(receipt['status'] == 'COLLECTED_NOT_IMPORTED' and receipt['databaseImported'] is False
                and receipt['transportEvidence'] == 'HTTP_BYTES' and receipt['sources'] == sources
                and receipt['configurationRevision'] == plan['revision'], 'SOURCE_RECEIPT_REJECTED')
        expected_publication = [self.collector.evidence(receipt_path.parent / 'publication' / name)
                                for name in ('manifest.json', 'material_day.json')]
        require(receipt['publication'] == expected_publication, 'SOURCE_PUBLICATION_CHANGED')
        items = receipt['publication'] + receipt['rawEvidence'] + receipt['sources']
        items += receipt.get('anchorResolution', {}).get('evidence', [])
        for item in items:
            path = Path(item['absolutePath'])
            require(path.resolve(strict=True) == path and path.is_relative_to(self.root), 'UNSAFE_SOURCE_PATH')
            require(self.collector.evidence(path) == item, 'SOURCE_CHANGED')
        material = read(receipt_path.parent / 'publication/material_day.json')
        require(material['advertiserId'] == state['accountId'] and material['projectRef'] == state['projectId']
                and material['statDate'] == state['statDate'], 'SOURCE_IDENTITY_CHANGED')
        state['rows'] = len(material['rows'])

    def prepare(self, state):
        config = Path(state['attemptDir']) / 'import.json'
        expected = dict(workspaceRoot=str(self.root), credentialsPath=self.baseline['credentialsPath'],
            schemaName=SCHEMA, expectedAccountId=state['accountId'],
            autoDays=[dict(date=state['statDate'], inputDirectory=str(Path(state['receipt']).parent / 'publication'))], manualDays=[])
        if not config.exists():
            save(config, dict(expected, runId='scheduled-' + uuid.uuid4().hex))
        actual = read(self.private(config))
        require({k: v for k, v in actual.items() if k != 'runId'} == expected, 'IMPORT_CONFIG_CHANGED')
        state['importConfig'] = str(config)
        state['importConfigSha256'] = sha(config)
        result, _ = self.java('LocalFactPreflightMain', [config, state['statDate']], 'Local source preflight receipt: ')
        require(result['status'] == 'SOURCE_READY' and result['rowCount'] == state['rows'], 'SOURCE_PREFLIGHT_FAILED')

    def backup(self, day):
        base = self.root / '.local/collection-scheduler/backups' / day
        marker = base / 'complete.json'
        require(shutil.disk_usage(self.root).free > 1024**3, 'DISK_RESERVE_REQUIRED')
        if marker.exists():
            data = read(self.private(marker))
            require(sha(self.private(base / data['file'])) == data['sha256'], 'BACKUP_CHANGED')
            return
        require(shutil.disk_usage(self.root).free > 2 * 1024**3, 'DISK_RESERVE_REQUIRED')
        base.mkdir(mode=0o700, parents=True, exist_ok=True)
        name = uuid.uuid4().hex + '.sql'
        dump = base / name
        # Output stays private. The same bounded command path supplies heartbeats and timeout.
        self.command(['/usr/bin/mysqldump', '--defaults-extra-file=' + str(self.client),
            '--single-transaction', '--no-tablespaces', '--skip-lock-tables', '--set-gtid-purged=OFF',
            '--column-statistics=0', '--hex-blob', '--default-character-set=utf8mb4', SCHEMA], 240, log=dump, read_output=False)
        require(dump.stat().st_size > 0 and shutil.disk_usage(self.root).free > 1024**3, 'BACKUP_FAILED')
        save(marker, dict(file=name, bytes=dump.stat().st_size, sha256=sha(dump)))

    def import_source(self, state):
        require(sha(self.private(Path(state['importConfig']))) == state['importConfigSha256'], 'IMPORT_CONFIG_CHANGED')
        result, path = self.java('LocalFactImportMain', [state['importConfig']], 'Local fact import receipt: ')
        state['importReceipt'] = str(path)
        require(result['status'] == 'COMPLETE', 'IMPORT_NOT_COMPLETE')

    def verify(self, state):
        require(sha(self.private(Path(state['importConfig']))) == state['importConfigSha256'], 'IMPORT_CONFIG_CHANGED')
        result, path = self.java('LocalFactVerifyMain', [state['importConfig']], 'Local fact verification receipt: ')
        require(result['status'] == 'ALL_INPUT_DAYS_MATCHED' and result['verifiedDays'] == 1
                and result['verifiedRows'] == state['rows'], 'READBACK_NOT_MATCHED')
        state['verificationReceipt'] = str(path)
        return result['verifiedRows']


def main():
    import fcntl
    import pwd
    require(sys.platform == 'linux' and os.geteuid() == pwd.getpwnam('scsj-trial').pw_uid, 'WRONG_RUNTIME_USER')
    os.umask(0o077)
    entered_at = dt.datetime.now(dt.timezone.utc)
    day, _ = slot(entered_at)
    require(ROOT.resolve(strict=True) == ROOT)
    base = ROOT / '.local/collection-scheduler'
    base.mkdir(mode=0o700, exist_ok=True)
    require(base.resolve() == base)
    lock_path = base / 'runner.lock'
    require(not lock_path.is_symlink())
    with lock_path.open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print('SCHEDULER_ALREADY_RUNNING')
            return
        result = locked_run(ROOT, day, entered_at)
        print(json.dumps(dict(status=result['status'], statDate=result['statDate'], projectCount=len(result['projects']))))
        return 0 if result['status'] == 'COMPLETE' else 2


def locked_run(root, day, entered_at=None):
    """Called only after slot and flock checks: even credential/init failure replaces old success."""
    base = root / '.local/collection-scheduler'
    initial = dict(schemaVersion='collection-scheduler-v1', updatedAt=stamp(), statDate=day,
                   status='RUNNING', projects=[])
    save(base / 'status.json', initial)
    runner_started = False
    try:
        credential = root / '.local/db-credentials.json'
        require(credential.resolve(strict=True) == credential)
        secret = read(credential)
        require(secret['username'] == 'scsj_live' and secret['port'] == 3306
                and re.fullmatch('[0-9a-f]{48}', secret['password']))
        with tempfile.TemporaryDirectory(prefix='db-client-', dir=base) as temp:
            client = Path(temp) / 'client.cnf'
            with client.open('x') as out:
                out.write('[client]\nuser=scsj_live\npassword=' + secret['password'] + '\nhost=127.0.0.1\nport=3306\nprotocol=TCP\n')
            runner = Runner(root, LinuxOps(root, client))
            runner_started = True
            return runner.run(entered_at or dt.datetime.now(dt.timezone.utc))
    except Exception:
        if not runner_started:
            initial.update(status='FAILED', updatedAt=stamp())
            save(base / 'status.json', initial)
        raise


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception:
        print('SCHEDULER_STOPPED_CHECK_PRIVATE_STATUS')
        sys.exit(2)
