"""D230 independent stat-date SQL snapshots, using the verified gzip publication transaction.

Each CLI process owns one worker module/store. Production is read-only; restore
SQL deliberately contains no DROP/DELETE/REPLACE/USE and targets an empty schema.
"""
import argparse
import datetime as dt
from decimal import Decimal
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import signal
import time
import weekly_database_backup_titania as worker

SHANGHAI = dt.timezone(dt.timedelta(hours=8))


def dates(start=None, end=None, today=None):
    today = today or dt.datetime.now(SHANGHAI).date()
    if start is None and end is None:
        return (today - dt.timedelta(days=7)).isoformat(), (today - dt.timedelta(days=1)).isoformat()
    try:
        assert isinstance(start, str) and isinstance(end, str)
        assert re.fullmatch(r'\d{4}-\d{2}-\d{2}', start) and re.fullmatch(r'\d{4}-\d{2}-\d{2}', end)
        first, last = dt.date.fromisoformat(start), dt.date.fromisoformat(end)
        assert dt.date(2000, 1, 1) <= first <= last < today
        return start, end
    except (AssertionError, TypeError, ValueError):
        raise worker.Rejected('INVALID_DATE_RANGE') from None


def literal(value):
    if value is None: return 'NULL'
    if isinstance(value, (bytes, bytearray)): return "X'" + value.hex() + "'"
    if isinstance(value, str): return "X'" + value.encode('utf-8').hex() + "'"
    if isinstance(value, (dt.datetime, dt.date, dt.time)): return "'" + value.isoformat(sep=' ') + "'" if isinstance(value, dt.datetime) else "'" + value.isoformat() + "'"
    if isinstance(value, bool): return '1' if value else '0'
    if isinstance(value, (int, Decimal)): return str(value)
    if isinstance(value, float) and math.isfinite(value): return repr(value)
    raise worker.Rejected('UNSUPPORTED_COLUMN_VALUE')


def insert(table, columns, row):
    return ('INSERT INTO `' + table + '` (' + ','.join('`' + c + '`' for c in columns)
            + ') VALUES (' + ','.join(literal(v) for v in row) + ');\n').encode('utf-8')


def export_snapshot(db, stream, start, end, check=lambda: None):
    """All four tables and both streaming passes share ONE repeatable-read snapshot."""
    import pymysql
    tables = worker.TABLES
    with db.cursor() as cursor:
        cursor.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ")
        cursor.execute("SET SESSION time_zone='+00:00'")
        cursor.execute('START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY')
        cursor.execute('SELECT TABLE_NAME,ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN (%s,%s,%s,%s)', tables)
        engines = cursor.fetchall()
        worker.require(len(engines) == 4 and {r[0] for r in engines} == set(tables)
                       and all(r[1] == 'InnoDB' for r in engines), 'DATABASE_PREFLIGHT_FAILED')
        # Explicit closure checks: never emit a dangling pointer or mismatched fact identity/date.
        cursor.execute('SELECT COUNT(*) FROM scsj_material_day_fact f LEFT JOIN scsj_fact_input_version v ON v.id=f.input_version_id WHERE f.stat_date BETWEEN %s AND %s AND (v.id IS NULL OR v.stat_date<>f.stat_date OR v.account_id<>f.account_id)', (start,end))
        worker.require(cursor.fetchone()[0] == 0, 'PERIOD_REFERENCE_MISMATCH')
        cursor.execute('SELECT COUNT(*) FROM scsj_fact_input_version v LEFT JOIN (SELECT input_version_id,COUNT(*) n FROM scsj_material_day_fact WHERE stat_date BETWEEN %s AND %s GROUP BY input_version_id) f ON f.input_version_id=v.id WHERE v.stat_date BETWEEN %s AND %s AND v.row_count<>COALESCE(f.n,0)', (start,end,start,end))
        worker.require(cursor.fetchone()[0] == 0, 'PERIOD_REFERENCE_MISMATCH')
        for pointer in ('current_input_version_id', 'previous_input_version_id'):
            cursor.execute(f'SELECT COUNT(*) FROM scsj_fact_day_state d LEFT JOIN scsj_fact_input_version v ON v.id=d.{pointer} WHERE d.stat_date BETWEEN %s AND %s AND d.{pointer} IS NOT NULL AND (v.id IS NULL OR v.stat_date<>d.stat_date OR v.account_id<>d.account_id)', (start,end))
            worker.require(cursor.fetchone()[0] == 0, 'PERIOD_REFERENCE_MISMATCH')
        definitions = {}
        for table in tables:
            cursor.execute('SHOW CREATE TABLE `' + table + '`')
            definitions[table] = cursor.fetchone()[1]
    stream.write(('-- SCSJ stat-date snapshot ' + start + ' through ' + end + ' (inclusive, Asia/Shanghai)\n'
                  '-- Restore into an EMPTY ISOLATED schema; no DROP/DELETE/REPLACE/USE statements.\n'
                  '-- This is current retained data for this period, not a historical point-in-time restore.\n'
                  "SET NAMES utf8mb4;\nSET TIME_ZONE='+00:00';\nSET SQL_MODE='NO_AUTO_VALUE_ON_ZERO';\n").encode())
    stats = {}
    for table in tables:
        check()
        # CREATE (without IF NOT EXISTS) intentionally refuses an existing target table.
        stream.write((definitions[table] + ';\n').encode('utf-8'))
        query = 'SELECT * FROM `' + table + '`'
        args = ()
        if table != 'scsj_flyway_schema_history':
            query += ' WHERE stat_date BETWEEN %s AND %s'; args = (start, end)
        query += ' ORDER BY ' + ('account_id,stat_date' if table == 'scsj_fact_day_state' else 'installed_rank' if table == 'scsj_flyway_schema_history' else 'id')
        hashes = []
        for pass_number in range(2):
            count = 0; digest = hashlib.sha256()
            with db.cursor(pymysql.cursors.SSCursor) as cursor:
                cursor.execute(query, args)
                columns = [c[0] for c in cursor.description]
                worker.require(all(re.fullmatch(r'[a-z_][a-z0-9_]*', c) for c in columns), 'DATABASE_PREFLIGHT_FAILED')
                for row in cursor:
                    if count % 256 == 0: check()
                    encoded = insert(table, columns, row)
                    digest.update(encoded); count += 1
                    if pass_number == 0: stream.write(encoded)
            hashes.append((count, digest.hexdigest()))
        worker.require(hashes[0] == hashes[1], 'DUMP_READBACK_FAILED')
        stats[table] = {'rows': hashes[0][0], 'insertSha256': hashes[0][1]}
    db.rollback()  # End the READ ONLY transaction; no production mutation.
    stream.write(('-- Table verification ' + json.dumps(stats, sort_keys=True) + '\n').encode())
    stream.write(('-- Dump completed on ' + dt.datetime.now(dt.timezone.utc).strftime('%Y-%m-%d %H:%M:%S') + '\n').encode())
    return stats


def dump(root, stream, start, end):
    import pymysql
    secret = worker.read_json(root, root / '.local/d218-runtime/db-credentials.json')
    worker.require(secret.get('username') == 'scsj_titania' and secret.get('port') == 3306, 'DATABASE_CREDENTIALS_INVALID')
    started = time.monotonic()
    def check():
        worker.require(time.monotonic() - started < 1500, 'DUMP_TIMEOUT')
        worker.require(shutil.disk_usage(root).free > 1024**3, 'DISK_RESERVE_REQUIRED')
    with pymysql.connect(host='127.0.0.1',port=3306,user=secret['username'],password=secret['password'],database=worker.DATABASE,
                         charset='utf8mb4',connect_timeout=10,read_timeout=30,write_timeout=30,autocommit=False) as db:
        return export_snapshot(db, stream, start, end, check)


def run(mode, start=None, end=None, root=worker.ROOT):
    worker.require(mode in ('weekly','custom'), 'INVALID_BACKUP_MODE')
    worker.require(mode != 'custom' or start is not None and end is not None, 'INVALID_DATE_RANGE')
    start, end = dates(start, end)
    # Process-local module configuration; the existing full-backup store is untouched.
    worker.STORE = '.local/database-period-' + mode
    worker.LEGACY_ROOT = worker.STORE + '-no-legacy'
    worker.LEGACY_DATES = ()
    base = worker.private_directory(root, root / worker.STORE)
    with worker.exclusive_lock(root, root / '.local/database-period.lock', 'BACKUP_LOCKED'):
        if not (base / 'legacy.json').exists():
            worker.atomic_json(root, base / 'legacy.json', dict(schemaVersion=1,database=worker.DATABASE,entries=[]))
        return worker.run_backup(root, lambda stream: dump(root, stream, start, end), metadata=dict(scope='STAT_DATE_RANGE',startDate=start,endDate=end))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['weekly','custom'])
    parser.add_argument('--start'); parser.add_argument('--end')
    args = parser.parse_args()
    try:
        import pwd
        worker.require(os.geteuid() == pwd.getpwnam('scsj_titania').pw_uid, 'WRONG_RUNTIME_USER')
        os.umask(0o077)
        def interrupted(*_): raise worker.Rejected('INTERRUPTED')
        signal.signal(signal.SIGTERM, interrupted); signal.signal(signal.SIGINT, interrupted)
        result = run(args.mode, args.start, args.end)
        print(json.dumps({'status':'PERIOD_BACKUP_VERIFIED', 'manifest':result, 'productionWritten':False}))
        return 0
    except Exception:
        print(json.dumps({'status':'PERIOD_BACKUP_FAILED_KEEP_PREVIOUS'})); return 2


if __name__ == '__main__': raise SystemExit(main())
