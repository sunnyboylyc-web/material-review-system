"""D187: one verified weekly business-database dump, never a system backup.

The installed systemd timer is the only automatic caller.  File transactions are
independent of application code and never import SQL or invoke collection APIs.
All errors exposed to the journal/UI are fixed codes, not database stderr.
"""
from contextlib import contextmanager
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import shutil
import stat
import subprocess
import sys
import time
import uuid


ROOT = Path('/opt/material-perspectives')
DATABASE = 'SCSJ_LOCAL_CLOUD'
STORE = '.local/database-weekly-backup'
LEGACY_ROOT = '.local/collection-scheduler/backups'
LEGACY_DATES = tuple(f'2026-10-{day}' for day in range(13, 18))
HEX = re.compile(r'[0-9a-f]{64}')
GENERATION = re.compile(r'[0-9a-f]{32}')
FOOTER = re.compile(rb'(?:^|\n)-- Dump completed on [0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:]{8}\s*\Z')
MANIFEST_KEYS = {'schemaVersion', 'generation', 'database', 'createdAt', 'bytes',
                 'sha256', 'validation', 'restoreTested'}
ERROR_CODES = {
    'UNSAFE_PATH', 'UNSAFE_PERMISSIONS', 'FILE_CHANGED', 'INVALID_JSON',
    'INVALID_MANIFEST', 'INVALID_LEGACY_MANIFEST', 'LEGACY_LAYOUT_CHANGED',
    'BACKUP_LOCKED', 'COLLECTION_BUSY', 'DISK_RESERVE_REQUIRED', 'DUMP_FAILED',
    'DUMP_TIMEOUT', 'DUMP_INCOMPLETE', 'DUMP_READBACK_FAILED', 'BACKUP_CHANGED',
    'UNKNOWN_ARTIFACTS', 'INCOMPLETE_ATTEMPT_REQUIRES_REVIEW',
    'TRANSACTION_CHANGED', 'CLEANUP_CHANGED', 'DATABASE_CREDENTIALS_INVALID',
    'WRONG_RUNTIME_USER', 'UNSUPPORTED_RUNTIME', 'IO_FAILED', 'BACKUP_FAILED',
    'NONTRANSACTIONAL_SCHEMA', 'DATABASE_PREFLIGHT_FAILED', 'INTERRUPTED',
}


class Rejected(Exception):
    """A safe, bounded code suitable for the private/public status contract."""


def require(condition, code):
    if not condition:
        raise Rejected(code)


def stamp():
    return dt.datetime.now(dt.timezone.utc).isoformat().replace('+00:00', 'Z')


def valid_stamp(value):
    try:
        parsed = dt.datetime.fromisoformat(value)
        return parsed.utcoffset() == dt.timedelta(0)
    except (TypeError, ValueError):
        return False


def _link(value):
    return stat.S_ISLNK(value.st_mode) or bool(
        getattr(value, 'st_file_attributes', 0) & getattr(stat, 'FILE_ATTRIBUTE_REPARSE_POINT', 0x400))


def checked(root, path, missing=False):
    """Lexical containment and every ancestor checked; do not resolve links."""
    root, path = Path(root).absolute(), Path(path).absolute()
    require(path.is_relative_to(root), 'UNSAFE_PATH')
    for current in reversed((path, *path.parents)):
        try:
            info = current.lstat()
        except FileNotFoundError:
            require(missing and current.is_relative_to(root), 'UNSAFE_PATH')
            continue
        require(not _link(info), 'UNSAFE_PATH')
        if current != path:
            require(stat.S_ISDIR(info.st_mode), 'UNSAFE_PATH')
        elif stat.S_ISREG(info.st_mode):
            require(info.st_nlink == 1, 'UNSAFE_PATH')
        else:
            require(stat.S_ISDIR(info.st_mode), 'UNSAFE_PATH')
    return path


def file_info(root, path):
    path = checked(root, path)
    value = path.lstat()
    require(stat.S_ISREG(value.st_mode) and value.st_nlink == 1, 'UNSAFE_PATH')
    return value


def identity(value):
    # Windows Python lstat/fstat disagree about deprecated ctime semantics.
    # Linux production retains ctime as an additional replacement/change guard.
    return (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns,
            value.st_ctime_ns if os.name != 'nt' else None)


def open_read(root, path):
    before = file_info(root, path)
    fd = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_BINARY', 0))
    try:
        require(identity(os.fstat(fd)) == identity(before), 'FILE_CHANGED')
        return os.fdopen(fd, 'rb')
    except BaseException:
        os.close(fd)
        raise


def digest(root, path):
    before = file_info(root, path)
    hasher = hashlib.sha256()
    with open_read(root, path) as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            hasher.update(chunk)
        require(identity(os.fstat(stream.fileno())) == identity(before), 'FILE_CHANGED')
    require(identity(file_info(root, path)) == identity(before), 'FILE_CHANGED')
    return before.st_size, hasher.hexdigest()


def read_json(root, path):
    info = file_info(root, path)
    require(info.st_size <= 65536, 'INVALID_JSON')
    try:
        with open_read(root, path) as stream:
            raw = stream.read(65537)
            require(identity(os.fstat(stream.fileno())) == identity(info), 'FILE_CHANGED')
        require(identity(file_info(root, path)) == identity(info), 'FILE_CHANGED')
        value = json.loads(raw)
    except (ValueError, UnicodeError):
        raise Rejected('INVALID_JSON') from None
    require(isinstance(value, dict), 'INVALID_JSON')
    return value


def sync_directory(path):
    if sys.platform == 'linux':
        fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY | getattr(os, 'O_NOFOLLOW', 0))
        try:
            os.fsync(fd)
        finally:
            os.close(fd)


def private_directory(root, path):
    path = checked(root, path, missing=True)
    if not path.exists():
        require(path.parent.is_dir(), 'UNSAFE_PATH')
        path.mkdir(mode=0o700)
        sync_directory(path.parent)
    require(path.is_dir(), 'UNSAFE_PATH')
    if sys.platform == 'linux':
        require(stat.S_IMODE(path.stat().st_mode) == 0o700, 'UNSAFE_PERMISSIONS')
    return path


def atomic_json(root, path, value):
    path = checked(root, path, missing=True)
    if path.exists():
        file_info(root, path)
    temporary = path.parent / ('.write-' + uuid.uuid4().hex + '.tmp')
    fd = os.open(temporary, os.O_CREAT | os.O_EXCL | os.O_WRONLY |
                 getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_BINARY', 0), 0o600)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write((json.dumps(value, ensure_ascii=True, separators=(',', ':')) + '\n').encode('utf-8'))
            stream.flush()
            os.fsync(stream.fileno())
        checked(root, path, missing=True)
        os.replace(temporary, path)
        sync_directory(path.parent)
    finally:
        if temporary.exists():
            # This temporary filename was exclusively created by this invocation.
            checked(root, temporary).unlink()


@contextmanager
def exclusive_lock(root, path, code, create=True):
    checked(root, path, missing=create)
    fd = os.open(path, (os.O_CREAT if create else 0) | os.O_RDWR | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    locked = False
    try:
        info = os.fstat(fd)
        require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1, 'UNSAFE_PATH')
        require(identity(file_info(root, path)) == identity(info), 'FILE_CHANGED')
        try:
            if sys.platform == 'linux':
                import fcntl
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            elif sys.platform == 'win32':
                import msvcrt
                msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)
            else:
                raise Rejected('UNSUPPORTED_RUNTIME')
            locked = True
        except (OSError, BlockingIOError):
            raise Rejected(code) from None
        yield
    finally:
        if locked:
            if sys.platform == 'linux':
                import fcntl
                fcntl.flock(fd, fcntl.LOCK_UN)
            else:
                import msvcrt
                os.lseek(fd, 0, os.SEEK_SET)
                msvcrt.locking(fd, msvcrt.LK_UNLCK, 1)
        os.close(fd)


def manifest_valid(value):
    return (isinstance(value, dict) and set(value) == MANIFEST_KEYS
            and type(value['schemaVersion']) is int and value['schemaVersion'] == 1
            and isinstance(value['generation'], str) and GENERATION.fullmatch(value['generation'])
            and value['database'] == DATABASE and valid_stamp(value['createdAt'])
            and type(value['bytes']) is int and value['bytes'] > 0
            and isinstance(value['sha256'], str) and HEX.fullmatch(value['sha256'])
            and value['validation'] == 'DUMP_EXIT_AND_HASH_READBACK'
            and value['restoreTested'] is False)


def legacy_valid(value):
    if not (isinstance(value, dict) and set(value) == {'schemaVersion', 'database', 'entries'}
            and type(value['schemaVersion']) is int and value['schemaVersion'] == 1
            and value['database'] == DATABASE and isinstance(value['entries'], list)
            and len(value['entries']) == 5):
        return False
    for index, entry in enumerate(value['entries']):
        if not (isinstance(entry, dict) and set(entry) == {
                'date', 'file', 'bytes', 'sha256', 'markerBytes', 'markerSha256'}
                and entry['date'] == LEGACY_DATES[index]
                and isinstance(entry['file'], str) and re.fullmatch(r'[0-9a-f]{32}\.sql', entry['file'])
                and all(type(entry[key]) is int and entry[key] > 0 for key in ('bytes', 'markerBytes'))
                and all(isinstance(entry[key], str) and HEX.fullmatch(entry[key])
                        for key in ('sha256', 'markerSha256'))):
            return False
    return True


def capture_legacy(root):
    """Read/hash exactly the five approved legacy dumps; never delete here."""
    root = Path(root).absolute()
    base = checked(root, root / LEGACY_ROOT)
    require(base.is_dir() and {p.name for p in base.iterdir()} == set(LEGACY_DATES),
            'LEGACY_LAYOUT_CHANGED')
    entries = []
    for day in LEGACY_DATES:
        directory = checked(root, base / day)
        require(directory.is_dir(), 'LEGACY_LAYOUT_CHANGED')
        marker = directory / 'complete.json'
        document = read_json(root, marker)
        require(set(document) == {'file', 'bytes', 'sha256'}
                and isinstance(document['file'], str)
                and re.fullmatch(r'[0-9a-f]{32}\.sql', document['file'])
                and type(document['bytes']) is int and document['bytes'] > 0
                and isinstance(document['sha256'], str) and HEX.fullmatch(document['sha256']),
                'INVALID_LEGACY_MANIFEST')
        require({p.name for p in directory.iterdir()} == {'complete.json', document['file']},
                'LEGACY_LAYOUT_CHANGED')
        size, checksum = digest(root, directory / document['file'])
        require((size, checksum) == (document['bytes'], document['sha256']), 'BACKUP_CHANGED')
        marker_size, marker_sha = digest(root, marker)
        entries.append(dict(date=day, file=document['file'], bytes=size, sha256=checksum,
                            markerBytes=marker_size, markerSha256=marker_sha))
    result = dict(schemaVersion=1, database=DATABASE, entries=entries)
    require(legacy_valid(result), 'INVALID_LEGACY_MANIFEST')
    return result


def generation_directory(root, manifest):
    require(manifest_valid(manifest), 'INVALID_MANIFEST')
    return checked(root, root / STORE / 'generations' / manifest['generation'], missing=True)


def verify_generation(root, manifest):
    directory = generation_directory(root, manifest)
    require(directory.is_dir() and {p.name for p in directory.iterdir()} == {'business.sql', 'manifest.json'},
            'UNKNOWN_ARTIFACTS')
    require(read_json(root, directory / 'manifest.json') == manifest, 'BACKUP_CHANGED')
    require(digest(root, directory / 'business.sql') == (manifest['bytes'], manifest['sha256']), 'BACKUP_CHANGED')


def read_current(root):
    path = root / STORE / 'current.json'
    checked(root, path, missing=True)
    if not path.exists():
        return None
    value = read_json(root, path)
    verify_generation(root, value)
    return value


def _check_file_optional(root, path, size, checksum):
    checked(root, path, missing=True)
    if path.exists():
        require(digest(root, path) == (size, checksum), 'CLEANUP_CHANGED')


def _remove_file(root, path):
    checked(root, path, missing=True)
    if path.exists():
        file_info(root, path)
        path.unlink()
        sync_directory(path.parent)


def cleanup_retired(root, transaction, current):
    """Only the exact journaled predecessors, after validating the new current."""
    require(current is not None and current['generation'] == transaction['generation'], 'TRANSACTION_CHANGED')
    require(read_json(root, root / STORE / 'current.json') == current, 'TRANSACTION_CHANGED')
    verify_generation(root, current)
    previous = transaction['previous']
    if previous is not None:
        require(manifest_valid(previous) and previous['generation'] != current['generation'], 'TRANSACTION_CHANGED')
        directory = generation_directory(root, previous)
        if directory.exists():
            require({p.name for p in directory.iterdir()} <= {'business.sql', 'manifest.json'}, 'CLEANUP_CHANGED')
            _check_file_optional(root, directory / 'business.sql', previous['bytes'], previous['sha256'])
            if (directory / 'manifest.json').exists():
                require(read_json(root, directory / 'manifest.json') == previous, 'CLEANUP_CHANGED')
    legacy = transaction['legacy']
    require(legacy_valid(legacy), 'INVALID_LEGACY_MANIFEST')
    base = checked(root, root / LEGACY_ROOT, missing=True)
    if base.exists():
        require({p.name for p in base.iterdir()} <= set(LEGACY_DATES), 'CLEANUP_CHANGED')
        for entry in legacy['entries']:
            directory = checked(root, base / entry['date'], missing=True)
            if directory.exists():
                require(directory.is_dir() and {p.name for p in directory.iterdir()}
                        <= {'complete.json', entry['file']}, 'CLEANUP_CHANGED')
                _check_file_optional(root, directory / entry['file'], entry['bytes'], entry['sha256'])
                _check_file_optional(root, directory / 'complete.json', entry['markerBytes'], entry['markerSha256'])
    # Every retained candidate was checked before deletion begins. Recheck each
    # exact file immediately before unlink; a later mismatch keeps the remainder.
    if previous is not None:
        directory = generation_directory(root, previous)
        if directory.exists():
            _check_file_optional(root, directory / 'business.sql', previous['bytes'], previous['sha256'])
            _remove_file(root, directory / 'business.sql')
            if (directory / 'manifest.json').exists():
                require(read_json(root, directory / 'manifest.json') == previous, 'CLEANUP_CHANGED')
            _remove_file(root, directory / 'manifest.json')
            directory.rmdir()
            sync_directory(directory.parent)
    if base.exists():
        for entry in legacy['entries']:
            directory = checked(root, base / entry['date'], missing=True)
            if not directory.exists():
                continue
            _check_file_optional(root, directory / entry['file'], entry['bytes'], entry['sha256'])
            _remove_file(root, directory / entry['file'])
            _check_file_optional(root, directory / 'complete.json', entry['markerBytes'], entry['markerSha256'])
            _remove_file(root, directory / 'complete.json')
            directory.rmdir()
            sync_directory(base)


def _transaction(root, current):
    base = root / STORE
    value = read_json(root, base / 'transaction.json')
    require(set(value) == {'schemaVersion', 'generation', 'previous', 'legacy'}
            and type(value['schemaVersion']) is int and value['schemaVersion'] == 1
            and isinstance(value['generation'], str) and GENERATION.fullmatch(value['generation'])
            and (value['previous'] is None or manifest_valid(value['previous']))
            and legacy_valid(value['legacy']), 'TRANSACTION_CHANGED')
    require(value['legacy'] == read_json(root, base / 'legacy.json'), 'TRANSACTION_CHANGED')
    if current is None or current['generation'] != value['generation']:
        raise Rejected('INCOMPLETE_ATTEMPT_REQUIRES_REVIEW')
    cleanup_retired(root, value, current)
    _remove_file(root, base / 'transaction.json')
    return current


def _layout(root, current):
    base = root / STORE
    allowed = {'current.json', 'last-attempt.json', 'legacy.json', 'transaction.json',
               'worker.lock', 'generations'}
    require({p.name for p in base.iterdir()} <= allowed, 'UNKNOWN_ARTIFACTS')
    for path in base.iterdir():
        checked(root, path)
    expected = set() if current is None else {current['generation']}
    require({p.name for p in (base / 'generations').iterdir()} == expected, 'UNKNOWN_ARTIFACTS')


def system_dump(root, stream, reserve_bytes):
    """Bounded mysqldump; credentials only in an inherited anonymous memfd."""
    require(sys.platform == 'linux' and hasattr(os, 'memfd_create'), 'UNSUPPORTED_RUNTIME')
    credentials = read_json(root, root / '.local/d145-runtime/db-credentials.json')
    require(credentials.get('username') == 'scsj_d145'
            and type(credentials.get('port')) is int and credentials['port'] == 3306
            and isinstance(credentials.get('password'), str)
            and re.fullmatch(r'[0-9a-f]{48}', credentials['password']), 'DATABASE_CREDENTIALS_INVALID')
    client = os.memfd_create('scsj-weekly-db-client', os.MFD_CLOEXEC)
    process = None
    try:
        # memfd modes are not restricted by umask. MySQL ignores world-writable
        # option files, and the credential-bearing descriptor must be private.
        os.fchmod(client, 0o600)
        require(os.name == 'nt' or stat.S_IMODE(os.fstat(client).st_mode) == 0o600,
                'UNSAFE_PERMISSIONS')
        data = ('[client]\nhost=127.0.0.1\nport=3306\nuser=scsj_d145\npassword='
                + credentials['password'] + '\nprotocol=tcp\n').encode('ascii')
        os.write(client, data)
        os.lseek(client, 0, os.SEEK_SET)
        del data, credentials
        # single-transaction is consistent for InnoDB, not arbitrary future
        # engines. No business rows are read or printed by this metadata check.
        query = ("SELECT COUNT(*),COALESCE(SUM(ENGINE IS NULL OR ENGINE<>'InnoDB'),0) "
                 "FROM information_schema.TABLES WHERE TABLE_SCHEMA='SCSJ_LOCAL_CLOUD' "
                 "AND TABLE_TYPE='BASE TABLE';")
        try:
            metadata = subprocess.run([
                '/usr/bin/mysql', '--defaults-extra-file=/proc/self/fd/' + str(client),
                '--batch', '--skip-column-names', '--connect-timeout=10', '-e', query,
            ], stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                pass_fds=(client,), timeout=15, check=False)
        except subprocess.TimeoutExpired:
            raise Rejected('DATABASE_PREFLIGHT_FAILED') from None
        require(metadata.returncode == 0 and re.fullmatch(rb'[0-9]+\t[0-9]+\r?\n?', metadata.stdout)
                and len(metadata.stdout) <= 128, 'DATABASE_PREFLIGHT_FAILED')
        count, other_engines = (int(value) for value in metadata.stdout.split())
        require(count >= 4, 'DATABASE_PREFLIGHT_FAILED')
        require(other_engines == 0, 'NONTRANSACTIONAL_SCHEMA')
        os.lseek(client, 0, os.SEEK_SET)
        process = subprocess.Popen([
            '/usr/bin/mysqldump', '--defaults-extra-file=/proc/self/fd/' + str(client),
            '--single-transaction', '--quick', '--no-tablespaces', '--skip-lock-tables',
            '--set-gtid-purged=OFF', '--column-statistics=0', '--hex-blob',
            '--default-character-set=utf8mb4', DATABASE,
        ], stdin=subprocess.DEVNULL, stdout=stream, stderr=subprocess.DEVNULL,
            pass_fds=(client,), start_new_session=True)
        started = time.monotonic()
        while process.poll() is None:
            require(time.monotonic() - started < 1500, 'DUMP_TIMEOUT')
            require(shutil.disk_usage(root).free > reserve_bytes, 'DISK_RESERVE_REQUIRED')
            time.sleep(0.2)
        require(process.returncode == 0, 'DUMP_FAILED')
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        os.close(client)


def _fresh_backup(root, current, dump, reserve_bytes):
    base = root / STORE
    _layout(root, current)
    legacy = read_json(root, base / 'legacy.json')
    require(legacy_valid(legacy), 'INVALID_LEGACY_MANIFEST')
    estimated = max([entry['bytes'] for entry in legacy['entries']]
                    + ([current['bytes']] if current else []))
    require(shutil.disk_usage(root).free > reserve_bytes + max(estimated * 2, 64 * 1024 * 1024),
            'DISK_RESERVE_REQUIRED')
    generation = uuid.uuid4().hex
    transaction = dict(schemaVersion=1, generation=generation, previous=current, legacy=legacy)
    atomic_json(root, base / 'transaction.json', transaction)
    directory = private_directory(root, base / 'generations' / generation)
    path = directory / 'business.sql'
    owned = None
    try:
        fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY |
                     getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_BINARY', 0), 0o600)
        owned = os.fstat(fd)
        with os.fdopen(fd, 'wb') as stream:
            if dump is None:
                system_dump(root, stream, reserve_bytes)
            else:
                dump(stream)
            stream.flush()
            os.fsync(stream.fileno())
        require(shutil.disk_usage(root).free > reserve_bytes, 'DISK_RESERVE_REQUIRED')
        size, checksum = digest(root, path)
        require(size > 0, 'DUMP_INCOMPLETE')
        with open_read(root, path) as stream:
            stream.seek(max(0, size - 4096))
            require(FOOTER.search(stream.read(4096)) is not None, 'DUMP_INCOMPLETE')
        # Independent streaming readback after flushing the complete dump.
        require(digest(root, path) == (size, checksum), 'DUMP_READBACK_FAILED')
        manifest = dict(schemaVersion=1, generation=generation, database=DATABASE,
                        createdAt=stamp(), bytes=size, sha256=checksum,
                        validation='DUMP_EXIT_AND_HASH_READBACK', restoreTested=False)
        atomic_json(root, directory / 'manifest.json', manifest)
        verify_generation(root, manifest)
        require(read_json(root, base / 'transaction.json') == transaction, 'TRANSACTION_CHANGED')
        atomic_json(root, base / 'current.json', manifest)
    except Exception:
        # Delete only this invocation's incomplete output, never the prior backup.
        # If publish happened (including an fsync error), leave the journal for
        # idempotent recovery: the new current must not be removed.
        published = True  # An unreadable pointer is not evidence it is safe to delete.
        try:
            published = read_json(root, base / 'current.json') != current
        except (OSError, Rejected):
            if not (base / 'current.json').exists() and current is None:
                published = False
        if not published and owned is not None:
            try:
                now = file_info(root, path)
                require((now.st_dev, now.st_ino) == (owned.st_dev, owned.st_ino), 'FILE_CHANGED')
                require({p.name for p in directory.iterdir()} <= {'business.sql', 'manifest.json'}, 'UNKNOWN_ARTIFACTS')
                _remove_file(root, path)
                if (directory / 'manifest.json').exists():
                    candidate = read_json(root, directory / 'manifest.json')
                    require(manifest_valid(candidate) and candidate['generation'] == generation, 'INVALID_MANIFEST')
                    _remove_file(root, directory / 'manifest.json')
                directory.rmdir()
                _remove_file(root, base / 'transaction.json')
            except (OSError, Rejected):
                pass  # Preserve anything changed/unknown; next run fails closed.
        raise
    cleanup_retired(root, transaction, manifest)
    _remove_file(root, base / 'transaction.json')
    _layout(root, manifest)
    return manifest


def run_backup(root=ROOT, dump=None, reserve_bytes=1024**3):
    """Return the current manifest; injected dump(stream) is only for tests."""
    root = checked(Path(root).absolute(), Path(root).absolute())
    require(root.is_dir(), 'UNSAFE_PATH')
    checked(root, root / '.local/collection-scheduler')
    base = private_directory(root, root / STORE)
    private_directory(root, base / 'generations')
    with exclusive_lock(root, base / 'worker.lock', 'BACKUP_LOCKED'):
        started = stamp()
        status = dict(schemaVersion=1, status='RUNNING', startedAt=started, completedAt=None, errorCode=None)
        atomic_json(root, base / 'last-attempt.json', status)
        try:
            with exclusive_lock(root, root / '.local/collection-scheduler/runner.lock', 'COLLECTION_BUSY', create=False):
                current = read_current(root)
                if (base / 'transaction.json').exists():
                    current = _transaction(root, current)
                    _layout(root, current)
                # A previous week's cleanup is not this week's database backup.
                # Once safe recovery finishes, still create a fresh snapshot.
                result = _fresh_backup(root, current, dump, reserve_bytes)
                status.update(status='SUCCESS', completedAt=stamp())
                atomic_json(root, base / 'last-attempt.json', status)
                return result
        except Exception as error:
            code = str(error) if isinstance(error, Rejected) and str(error) in ERROR_CODES else 'IO_FAILED'
            status.update(status='FAILED', completedAt=stamp(), errorCode=code)
            atomic_json(root, base / 'last-attempt.json', status)
            raise Rejected(code) from None


def main():
    try:
        require(sys.platform == 'linux', 'UNSUPPORTED_RUNTIME')
        import pwd
        require(os.geteuid() == pwd.getpwnam('scsj-trial').pw_uid, 'WRONG_RUNTIME_USER')
        os.umask(0o077)
        def interrupted(_signum, _frame):
            raise Rejected('INTERRUPTED')
        signal.signal(signal.SIGTERM, interrupted)
        signal.signal(signal.SIGINT, interrupted)
        result = run_backup()
        print(json.dumps(dict(status='WEEKLY_DATABASE_BACKUP_VERIFIED', bytes=result['bytes'],
                              sha256=result['sha256'], restoreTested=False,
                              databaseWritten=False, servicesChanged=False)))
        return 0
    except Exception as error:
        code = str(error) if isinstance(error, Rejected) and str(error) in ERROR_CODES else 'BACKUP_FAILED'
        print('STOP: ' + code)
        print('Keep the current backup and private artifacts; do not delete or rerun blindly.')
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
