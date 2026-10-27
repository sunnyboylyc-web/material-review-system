"""Bounded request control and evidence-backed creation dates. No work on import."""
import contextlib
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import time
from zoneinfo import ZoneInfo

SHANGHAI = ZoneInfo('Asia/Shanghai')
RETRY_CODES = (51010, 40100, 40000, 400153)


class ResilienceRejected(RuntimeError):
    pass


def require(ok, code):
    if not ok:
        raise ResilienceRejected(code)


def creation(value):
    if not isinstance(value, str) or not value.strip() or value == 'UNKNOWN':
        return None
    try:
        stamp = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
        if stamp.tzinfo:
            stamp = stamp.astimezone(SHANGHAI).replace(tzinfo=None)
        return stamp.isoformat(sep=' ')
    except ValueError:
        return None


class SharedRequestControl:
    """One request at a time across processes; rate-limit pauses survive child exit."""
    def __init__(self, directory, clock=time.time, sleeper=time.sleep):
        self.directory = Path(directory)
        self.clock, self.sleep = clock, sleeper

    @contextlib.contextmanager
    def locked(self):
        import fcntl
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        require(self.directory.resolve() == self.directory, 'UNSAFE_REQUEST_CONTROL')
        lock = self.directory / 'request-control.lock'
        state = self.directory / 'request-control.json'
        require(not lock.is_symlink() and not state.is_symlink(), 'UNSAFE_REQUEST_CONTROL')
        with lock.open('a+') as stream:
            fcntl.flock(stream, fcntl.LOCK_EX)
            value = json.loads(state.read_bytes()) if state.exists() else {'lastStarted': 0, 'pauseUntil': 0}
            require(set(value) == {'lastStarted', 'pauseUntil'} and
                    all(type(x) in (int, float) and 0 <= x <= self.clock() + 86400 for x in value.values()),
                    'REQUEST_CONTROL_INVALID')
            yield state, value

    @staticmethod
    def write(path, value):
        pending = path.with_suffix('.tmp')
        require(not pending.is_symlink(), 'UNSAFE_REQUEST_CONTROL')
        with pending.open('w') as stream:
            json.dump(value, stream)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(pending, path)

    @contextlib.contextmanager
    def request(self):
        with self.locked() as (path, value):
            while True:
                delay = max(value['lastStarted'] + 2, value['pauseUntil']) - self.clock()
                if delay <= 0:
                    break
                self.sleep(min(delay, 2))
            value['lastStarted'] = self.clock()
            self.write(path, value)
            yield  # Hold the interprocess mutex until this HTTP response completes.

    def pause(self, seconds=60):
        with self.locked() as (path, value):
            value['pauseUntil'] = max(value['pauseUntil'], self.clock() + seconds)
            self.write(path, value)


class HistoricalCreationLookup:
    """Only current, hash-verified rows within the plan's verified account lineage."""
    def __init__(self, root, project, day):
        self.root, self.project, self.day = Path(root), project, day
        self.expected_aweme_id = project.get('expectedAwemeId') or (project.get('douyinIds') or [None])[0]

    def source_anchor_matches(self, source, document, material_size, material_sha):
        if document.get('projectRef') != self.project['id']:
            return False
        if document.get('collectionSelection'):
            return document['collectionSelection'].get('awemeId') == self.expected_aweme_id
        # Pre-theme sources carry the same verified anchor in their immutable receipt.
        path = source.parent.parent / 'receipt.json'
        if source.parent.name != 'publication' or not path.exists():
            return False
        require(path.resolve(strict=True)==path and path.is_relative_to(self.root/'.local'), 'HISTORY_SOURCE_PATH_INVALID')
        receipt=json.loads(path.read_bytes())
        if receipt.get('status')!='COLLECTED_NOT_IMPORTED' or receipt.get('transportEvidence')!='HTTP_BYTES':
            return False
        expected={'absolutePath':str(source),'sizeBytes':material_size,'sha256':material_sha}
        require(expected in receipt.get('publication',[]), 'HISTORY_SOURCE_CONTENT_MISMATCH')
        anchor=receipt.get('anchorResolution',{})
        if anchor.get('status')!='EXACT_UNIQUE_MATCH' or str(anchor.get('resolvedAwemeId'))!=self.expected_aweme_id:
            return False
        items=receipt.get('sources',[])+anchor.get('evidence',[])
        require(items and anchor.get('evidence'), 'HISTORY_SOURCE_IDENTITY_UNVERIFIED')
        for item in items:
            file=Path(item['absolutePath'])
            require(file.resolve(strict=True)==file and (file.is_relative_to(self.root/'.local')
                or file==self.root/'backend/src/main/resources/configuration/material-33-v1.json'), 'HISTORY_SOURCE_PATH_INVALID')
            data=file.read_bytes()
            require(len(data)==item['sizeBytes'] and hashlib.sha256(data).hexdigest()==item['sha256'], 'HISTORY_SOURCE_HASH_MISMATCH')
        plan=json.loads(Path(receipt['sources'][0]['absolutePath']).read_bytes())
        originals=[p for p in plan.get('projects',[]) if p['id']==self.project['id']]
        if len(originals)!=1:
            return False
        identity=originals[0]
        return ((self.project.get('publicDouyinId') and identity.get('publicDouyinId')==self.project['publicDouyinId'])
                or self.expected_aweme_id in identity.get('douyinIds',[]))

    def __call__(self, material_ids):
        import pymysql
        ids = sorted(set(material_ids))
        if not ids:
            return {}
        require(len(ids) <= 200000, 'HISTORY_LOOKUP_TOO_LARGE')
        accounts = self.project.get('sourceAccounts', [self.project.get('storageAccountId', self.project['advertiserId'])])
        require(accounts and len(accounts) <= 100 and all(re.fullmatch(r'[1-9][0-9]{0,29}', a) for a in accounts),
                'HISTORY_IDENTITY_INVALID')
        path = self.root / '.local/d218-runtime/db-credentials.json'
        require(path.resolve(strict=True) == path, 'HISTORY_CREDENTIAL_PATH_INVALID')
        secret = json.loads(path.read_bytes())
        require(isinstance(self.expected_aweme_id,str) and re.fullmatch(r'[1-9][0-9]{0,29}',self.expected_aweme_id), 'HISTORY_IDENTITY_INVALID')
        result = {}
        checked_sources = {}
        wanted = set(ids)
        with pymysql.connect(host='127.0.0.1', port=3306, user=secret['username'], password=secret['password'],
                             database='material_dashboard', charset='utf8mb4', read_timeout=90) as db:
            with db.cursor() as cursor:
                cursor.execute('START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY')
                for offset in range(0, len(ids), 500):
                    part = ids[offset:offset + 500]
                    cursor.execute('SELECT f.material_id,f.raw_fact_json,f.row_sha256,f.input_version_id,s.stat_date,s.account_id,v.material_path,v.material_size_bytes,v.material_sha256 '
                        'FROM scsj_fact_day_state s JOIN scsj_fact_input_version v ON v.id=s.current_input_version_id '
                        'JOIN scsj_material_day_fact f ON f.input_version_id=v.id '
                        'WHERE s.account_id IN (' + ','.join(['%s'] * len(accounts)) + ') '
                        'AND f.material_id IN (' + ','.join(['%s'] * len(part)) + ') AND v.disposition=\'CURRENT\' '
                        'AND f.account_id=s.account_id AND f.stat_date=s.stat_date AND v.account_id=s.account_id '
                        'AND v.stat_date=s.stat_date AND v.source_type=\'MCP_AUTO\'', tuple(accounts) + tuple(part))
                    for mid, raw, digest, version, day, account, material_path, material_size, material_sha in cursor:
                        if version not in checked_sources:
                            source=Path(material_path)
                            require(source.resolve(strict=True)==source and source.is_relative_to(self.root/'.local') and source.is_file(),'HISTORY_SOURCE_PATH_INVALID')
                            payload=source.read_bytes()
                            require(len(payload)==material_size and hashlib.sha256(payload).hexdigest()==material_sha,'HISTORY_SOURCE_HASH_MISMATCH')
                            document=json.loads(payload)
                            checked_sources[version]=dict(matches=self.source_anchor_matches(source,document,material_size,material_sha),
                                rows={x['material_id']:x for x in document.get('rows',[]) if x.get('material_id') in wanted})
                        if not checked_sources[version]['matches']:continue
                        require(hashlib.sha256(raw.encode()).hexdigest() == digest, 'HISTORY_ROW_HASH_MISMATCH')
                        parsed=json.loads(raw)
                        require(checked_sources[version]['rows'].get(mid)==parsed,'HISTORY_SOURCE_CONTENT_MISMATCH')
                        date = creation(parsed.get('material_create_time_v2'))
                        if date is None or date[:10] > self.day:
                            continue
                        entry = result.setdefault(mid, {'values': {}, 'conflict': False})
                        entry['values'].setdefault(date, {'inputVersionId': version, 'rowSha256': digest,
                            'statDate': str(day), 'accountId': account})
                        entry['conflict'] = len(entry['values']) > 1
            db.rollback()
        return {mid: {'value': next(iter(x['values'])) if not x['conflict'] else None,
                      'conflict': x['conflict'], 'evidence': next(iter(x['values'].values()))}
                for mid, x in result.items()}


def resolve_creation_dates(rows, lookup, numeric):
    """Keep observed responses untouched; derived rows have an explicit provenance file."""
    missing = [r['material_id'] for r in rows if r['material_id'] != '-2'
               and r.get('material_create_time_v2') in (None, '', 'UNKNOWN')]
    history = lookup(missing) if lookup and missing else {}
    accepted, pending, resolutions = [], [], []
    for source in rows:
        row = dict(source)
        # The existing aggregate sentinel has no creation date by definition.
        # Keep its raw date and metrics; ordinary material names never grant this exception.
        if row['material_id'] == '-2':
            accepted.append(row)
            continue
        if row['material_id'] not in missing:
            require(creation(row.get('material_create_time_v2')) is not None,'ROW_REJECTED')
            accepted.append(row)
            continue
        found = history.get(row['material_id'])
        require(not (found and found.get('conflict')), 'CREATION_TIME_HISTORY_CONFLICT')
        reliable = found and not found.get('conflict') and creation(found.get('value'))
        if reliable:
            row['material_create_time_v2'] = reliable
            resolutions.append({'materialId': row['material_id'], 'value': reliable,
                                'source': found['evidence']})
            accepted.append(row)
            continue
        require(numeric(row['stat_cost_for_roi2']) == 0, 'CREATION_TIME_UNRESOLVED_POSITIVE_OR_UNKNOWN')
        # A zero-spend row with business contributions/unknowns cannot be silently deferred.
        for field in ('total_pay_order_gmv_for_roi2', 'total_pay_order_count_for_roi2',
                      'live_show_count_for_roi2_v2', 'live_watch_count_for_roi2_v2'):
            require(field in row and numeric(row[field]) == 0, 'ZERO_SPEND_HAS_OTHER_CONTRIBUTIONS')
        pending.append({'materialId': row['material_id'], 'reason': 'CREATION_TIME_UNRESOLVED', 'row': row})
    return accepted, pending, resolutions
