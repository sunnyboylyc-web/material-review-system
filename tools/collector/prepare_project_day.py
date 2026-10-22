"""Prepare one project's verified collection for existing local repair CLI; never import."""
import argparse
from pathlib import Path
import subprocess
import json
import os
import shutil
from uuid import uuid4
import collect


def mysql_runtime(root, executable=None, defaults_file=None):
    """Explicit portable client settings; legacy defaults remain Windows-only."""
    collect.require(bool(executable) == bool(defaults_file))
    if executable is None:
        collect.require(os.name == 'nt')
        executable = str(root/'.local/toolchains/mysql-8.4.11-winx64/bin/mysql.exe')
        defaults_file = str(root/'.local/mysql-dev-20261013/client.ini')
    resolved = shutil.which(executable)
    collect.require(resolved is not None)
    client = Path(resolved).resolve(strict=True)
    collect.require(client.is_file())
    # Connection secrets stay inside the project's private directory, never arguments.
    defaults = collect.private_path(root, defaults_file)
    collect.require(defaults.is_file())
    return client, defaults


def fingerprint(root, account, day, executable=None, defaults_file=None, schema='SCSJ_LOCAL_FACTS'):
    collect.require(schema in ('SCSJ_LOCAL_FACTS', 'SCSJ_LOCAL_CLOUD'))
    collect.require(account.isdigit() and len(account) <= 30)
    from datetime import date
    collect.require(date.fromisoformat(day).isoformat() == day)
    where = f" WHERE NOT (account_id='{account}' AND stat_date='{day}')"
    query = 'SET SESSION TRANSACTION READ ONLY; START TRANSACTION WITH CONSISTENT SNAPSHOT;'
    for table, order in [('SCSJ_FACT_INPUT_VERSION', 'id'), ('SCSJ_MATERIAL_DAY_FACT', 'id'),
                         ('SCSJ_FACT_DAY_STATE', 'account_id,stat_date')]:
        query += f'SELECT * FROM {schema}.{table}{where} ORDER BY {order};'
    query += 'ROLLBACK;'
    client, defaults = mysql_runtime(root, executable, defaults_file)
    result = subprocess.run([str(client),
        '--defaults-extra-file='+str(defaults), '--batch', '--raw', '--skip-column-names', '--execute', query],
        cwd=root, capture_output=True, timeout=90)
    collect.require(result.returncode == 0)
    return collect.sha(result.stdout)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--plan', required=True)
    p.add_argument('--project', required=True)
    p.add_argument('--receipt', required=True)
    p.add_argument('--connection-baseline', required=True)
    p.add_argument('--verify-preparation')
    p.add_argument('--mysql-executable', help='MySQL client path or executable name; pair with defaults file')
    p.add_argument('--mysql-defaults-file', help='Private MySQL option file under this workspace .local')
    a = p.parse_args()
    root = Path(__file__).resolve().parents[2]
    plan, project, _, sources = collect.load_plan(root, a.plan, a.project)
    receipt_path = collect.private_path(root, a.receipt)
    receipt = collect.decode(receipt_path.read_bytes())
    collect.require(receipt['status'] == 'COLLECTED_NOT_IMPORTED'
        and receipt['transportEvidence'] == 'HTTP_BYTES' and receipt['sources'] == sources
        and receipt['configurationRevision'] == plan['revision'])
    for item in receipt['publication'] + receipt['rawEvidence'] + receipt['sources']:
        path = Path(item['absolutePath']).resolve(strict=True)
        collect.require(path.is_relative_to(root))
        collect.require(collect.evidence(path) == item)
    directory = receipt_path.parent/'publication'
    material = collect.decode((directory/'material_day.json').read_bytes())
    collect.require(material['projectRef'] == project['id']
        and material['advertiserId'] == project['advertiserId'] and material['statDate'] == plan['statDate'])
    baseline_path = collect.private_path(root, a.connection_baseline)
    baseline = collect.decode(baseline_path.read_bytes())
    collect.require(baseline['schemaName'] in ('SCSJ_LOCAL_FACTS', 'SCSJ_LOCAL_CLOUD')
        and Path(baseline['workspaceRoot']).resolve() == root)
    digest = fingerprint(root, project['advertiserId'], plan['statDate'],
                         a.mysql_executable, a.mysql_defaults_file, baseline['schemaName'])
    if a.verify_preparation:
        before_path = collect.private_path(root, a.verify_preparation)
        before = collect.decode(before_path.read_bytes())
        collect.require(before['receipt'] == collect.evidence(receipt_path)
            and before['baseline'] == collect.evidence(baseline_path)
            and before['otherFactsFingerprint'] == digest)
        result = dict(status='OTHER_FACTS_UNCHANGED', preparation=collect.evidence(before_path))
        output = before_path.parent/('after-'+uuid4().hex+'.json')
    else:
        run = root/'.local/authorized-collection'/uuid4().hex
        run.mkdir()
        config = {k: baseline[k] for k in ('workspaceRoot', 'credentialsPath', 'schemaName')}
        config.update(expectedAccountId=project['advertiserId'], runId='project-day-'+uuid4().hex,
            autoDays=[dict(date=plan['statDate'], inputDirectory=str(directory))], manualDays=[])
        config_path = run/'single-day.json'
        collect.write_once(config_path, config)
        result = dict(status='PREPARED_NOT_IMPORTED', receipt=collect.evidence(receipt_path),
            baseline=collect.evidence(baseline_path), config=collect.evidence(config_path),
            otherFactsFingerprint=digest, rowCount=len(material['rows']))
        output = run/'preparation.json'
    collect.write_once(output, result)
    print(json.dumps(dict(status=result['status'], receipt=str(output),
        config=result.get('config', {}).get('absolutePath'))))


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('PROJECT_DAY_PREPARATION_REJECTED')
        raise SystemExit(2)
