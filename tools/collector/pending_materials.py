"""Durable deferred rows and append-only, compare-and-swap day completion.

Historical fact rows are never updated or removed. A completion publishes a new
fully validated day version whose existing rows are byte-for-byte preserved.
"""
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import uuid

from collection_resilience import creation


def require(value, code='PENDING_EVIDENCE_INVALID'):
    if not value:
        raise RuntimeError(code)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()


def atomic(path, value):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    require(path.parent.resolve() == path.parent and not path.is_symlink())
    temp = path.with_name('.' + uuid.uuid4().hex + '.tmp')
    with temp.open('xb') as stream:
        stream.write(encoded(value)); stream.flush(); os.fsync(stream.fileno())
    os.replace(temp, path)


def private(root, value):
    path = Path(value)
    require(path.resolve(strict=True) == path and path.is_relative_to(root / '.local') and path.is_file())
    return path


def read_evidence(root, item):
    path = private(root, item['absolutePath'])
    data = path.read_bytes()
    require(len(data) == item['sizeBytes'] and sha(data) == item['sha256'])
    return json.loads(data)


class PendingMaterials:
    def __init__(self, root):
        self.root = Path(root)
        self.base = self.root / '.local/collection-scheduler/pending'

    def path(self, project, account, day):
        key = sha((project + ':' + account + ':' + day).encode())
        return self.base / (key + '.json')

    def register(self, state, project, evidence):
        """Only called after the initial day import was independently read back."""
        receipt_path = private(self.root, state['receipt'])
        receipt = json.loads(receipt_path.read_bytes())
        item = receipt.get('creationTimeEvidence')
        if item is None:
            return 0
        source = read_evidence(self.root, item)
        require(source['projectRef'] == state['projectId'] and source['statDate'] == state['statDate'])
        rows = source['pending']
        require(len(rows) == receipt.get('pendingCreationTimeCount', 0))
        if not rows:
            return 0
        path = self.path(state['projectId'], state['accountId'], state['statDate'])
        if path.exists():
            queue = self.load(path)
            require(queue['sourceReceipt'] == evidence(receipt_path), 'PENDING_SOURCE_CHANGED')
        else:
            queue = dict(schemaVersion='pending-materials-v1', projectId=state['projectId'],
                accountId=state['accountId'], statDate=state['statDate'],
                sourceReceipt=evidence(receipt_path), creationTimeEvidence=item,
                items={x['materialId']:dict(status='PENDING', row=x['row']) for x in rows}, attempts=[])
            require(len(queue['items']) == len(rows))
            atomic(path, queue)
        self.publish()
        return sum(x['status'] != 'COMPLETED' for x in queue['items'].values())

    def load(self, path):
        path = private(self.root, path)
        require(path.parent == self.base and path.stat().st_size <= 64 * 1024 * 1024)
        queue = json.loads(path.read_bytes())
        require(queue['schemaVersion'] == 'pending-materials-v1' and
                path == self.path(queue['projectId'], queue['accountId'], queue['statDate']))
        receipt = read_evidence(self.root, queue['sourceReceipt'])
        source = read_evidence(self.root, queue['creationTimeEvidence'])
        require(receipt['status']=='COLLECTED_NOT_IMPORTED' and receipt['creationTimeEvidence'] == queue['creationTimeEvidence'] and
                source['projectRef'] == queue['projectId'] and source['statDate'] == queue['statDate'])
        original = {x['materialId']:x['row'] for x in source['pending']}
        require(set(queue['items']) == set(original))
        for mid, item in queue['items'].items():
            require(item['row'] == original[mid] and item['status'] in ('PENDING', 'COMPLETED', 'MANUAL_REVIEW'))
        initial=read_evidence(self.root,receipt['publication'][1])
        require(initial['projectRef']==queue['projectId'] and initial['statDate']==queue['statDate']
            and sha(encoded(initial['rows']))==initial['dataHash'])
        versions={initial['dataHash']:initial['rows']}
        for proposal in queue.get('proposedVersions',[]):
            material=read_evidence(self.root,proposal['materialEvidence'])
            manifest=read_evidence(self.root,proposal['manifestEvidence'])
            prior=versions.get(proposal['baseHash'])
            require(prior is not None and material['rows'][:len(prior)]==prior
                and sha(encoded(material['rows']))==material['dataHash']==proposal['contentHash']==manifest['publicationDataHash'])
            added=material['rows'][len(prior):]
            require(added and {r['material_id'] for r in added}<=set(original)
                and all(creation(r.get('material_create_time_v2')) and r['stat_time_day']==queue['statDate'] for r in added)
                and len({r['material_id'] for r in material['rows']})==len(material['rows']))
            versions[material['dataHash']]=material['rows']
        return queue

    def find(self, project, account, day):
        path = self.path(project, account, day)
        return (path, self.load(path)) if path.exists() else (path, None)

    def publish(self, today=None):
        today = today or dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).date()
        summary = {}
        for path in sorted(self.base.glob('*.json')):
            queue = self.load(path)
            day = dt.date.fromisoformat(queue['statDate'])
            live = [x for x in queue['items'].values() if x['status'] != 'COMPLETED']
            if not live:
                continue
            value = summary.setdefault(queue['projectId'], dict(projectId=queue['projectId'],
                pendingCount=0, manualCount=0, oldestStatDate=queue['statDate']))
            value['pendingCount'] += len(live)
            value['manualCount'] += sum(x['status'] == 'MANUAL_REVIEW' or (today-day).days > 8 for x in live)
            value['oldestStatDate'] = min(value['oldestStatDate'], queue['statDate'])
        atomic(self.base.parent / 'pending-status.json', dict(schemaVersion='pending-material-status-v1',
            updatedAt=dt.datetime.now(dt.timezone.utc).isoformat(), projects=list(summary.values())))

    def finish(self, path, queue, resolved, evidence):
        for mid, proof in resolved.items():
            require(mid in queue['items'])
            queue['items'][mid].update(status='COMPLETED', resolution=proof)
        queue['attempts'].append(evidence)
        atomic(path, queue)
        self.publish()

    def reconcile_existing(self, project, database):
        """Close externally supplied rows even after automatic recollection expires."""
        accounts = set(project.get('sourceAccounts',[project.get('storageAccountId',project['advertiserId'])]))
        candidates=[]
        for path in self.base.glob('*.json'):
            queue=self.load(path)
            if queue['projectId']==project['id'] and queue['accountId'] in accounts and any(x['status']!='COMPLETED' for x in queue['items'].values()):
                candidates.append((path,queue))
        # Bound morning work; rotate by oldest verification, never delete expired markers.
        candidates.sort(key=lambda item:item[1].get('lastReconciledAt',''))
        for path,queue in candidates[:32]:
            try:
                with database(self.root) as db:
                    current=current_day(db,queue['accountId'],queue['statDate']);db.rollback()
            except RuntimeError as error:
                if str(error)=='PENDING_CURRENT_DAY_MISSING':continue
                raise
            present={r[0]:json.loads(r[2]) for r in current['rows']}
            done={mid:dict(kind='ALREADY_PRESENT',inputVersionId=current['id']) for mid,item in queue['items'].items()
                if item['status']!='COMPLETED' and mid in present and creation(present[mid].get('material_create_time_v2'))}
            queue['lastReconciledAt']=dt.datetime.now(dt.timezone.utc).isoformat()
            self.finish(path,queue,done,dict(status='RECONCILED_EXISTING_DATA',inputVersionId=current['id']))


def current_day(db, account, day, locked=False):
    with db.cursor() as cursor:
        cursor.execute('SELECT v.id,v.content_hash,v.source_type,v.row_count,v.project_ref,s.previous_input_version_id '
            'FROM scsj_fact_day_state s JOIN scsj_fact_input_version v ON v.id=s.current_input_version_id '
            'WHERE s.account_id=%s AND s.stat_date=%s AND v.disposition=\'CURRENT\' '
            'AND v.account_id=s.account_id AND v.stat_date=s.stat_date' + (' FOR UPDATE' if locked else ''), (account, day))
        record = cursor.fetchone()
        require(record is not None, 'PENDING_CURRENT_DAY_MISSING')
        version, digest, kind, count, project, previous = record
        cursor.execute('SELECT material_id,source_row_number,raw_fact_json,row_sha256 FROM scsj_material_day_fact '
                       'WHERE input_version_id=%s AND account_id=%s AND stat_date=%s ORDER BY source_row_number',
                       (version, account, day))
        rows = list(cursor.fetchall())
        require(len(rows) == count and all(r[1] == i+1 and sha(r[2].encode()) == r[3] for i,r in enumerate(rows)),
                'PENDING_CURRENT_ROW_HASH_MISMATCH')
        if kind == 'MCP_AUTO':
            require(sha(encoded([json.loads(r[2]) for r in rows])) == digest, 'PENDING_CURRENT_CONTENT_MISMATCH')
        return dict(id=version, hash=digest, sourceType=kind, projectRef=project, previous=previous, rows=rows)


def append_only_completion(db, account, day, project, expected, rows, material, manifest, run_id, allowed_ids):
    """Caller has validated the complete derived publication using the Java validator.

    The transaction independently enforces an unchanged base and exact append-only
    rows. Existing version payloads and facts remain intact, including rollback data.
    """
    try:
        db.begin()
        before = current_day(db, account, day, locked=True)
        require(before['id'] == expected['id'] and before['hash'] == expected['hash'], 'PENDING_CURRENT_VERSION_CHANGED')
        require(before['sourceType'] == 'MCP_AUTO' and before['projectRef'] == project, 'PENDING_CANNOT_REPLACE_MANUAL_DAY')
        old = before['rows']
        require(len(rows) > len(old) and rows[:len(old)] == [json.loads(r[2]) for r in old], 'PENDING_EXISTING_ROWS_CHANGED')
        ids = [r['material_id'] for r in rows]
        require(len(ids) == len(set(ids)) and all(r['stat_time_day'] == day for r in rows), 'PENDING_ROW_IDENTITY_INVALID')
        require({r['material_id'] for r in rows[len(old):]} <= set(allowed_ids) and
                all(creation(r.get('material_create_time_v2')) for r in rows[len(old):]), 'PENDING_NOT_AUTHORIZED_ADDITIONS')
        require(material['dataHash'] == sha(encoded(rows)) and manifest['publicationDataHash'] == material['dataHash'])
        with db.cursor() as cursor:
            cursor.execute('SELECT COUNT(*) FROM scsj_fact_input_version WHERE account_id=%s AND stat_date=%s AND content_hash=%s',
                           (account, day, material['dataHash']))
            require(cursor.fetchone()[0] == 0, 'PENDING_VERSION_ALREADY_EXISTS_RECONCILE')
            me, fe = material['_evidence'], manifest['_evidence']
            cursor.execute('INSERT INTO scsj_fact_input_version (account_id,stat_date,project_ref,source_type,data_version,'
                'content_hash,schema_version,catalog_version,manifest_path,manifest_size_bytes,manifest_sha256,'
                'material_path,material_size_bytes,material_sha256,disposition,row_count,run_id) '
                'VALUES (%s,%s,%s,\'MCP_AUTO\',%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,\'CURRENT\',%s,%s)',
                (account,day,project,material['dataVersion'],material['dataHash'],material['schemaVersion'],material['catalogVersion'],
                 fe['absolutePath'],fe['sizeBytes'],fe['sha256'],me['absolutePath'],me['sizeBytes'],me['sha256'],len(rows),run_id))
            new_id = cursor.lastrowid
            facts = [(new_id,account,day,r[0],r[1],r[2],r[3]) for r in old]
            for i,row in enumerate(rows[len(old):],len(old)+1):
                raw = encoded(row).decode()
                facts.append((new_id,account,day,row['material_id'],i,raw,sha(raw.encode())))
            cursor.executemany('INSERT INTO scsj_material_day_fact (input_version_id,account_id,stat_date,material_id,'
                'source_row_number,raw_fact_json,row_sha256) VALUES (%s,%s,%s,%s,%s,%s,%s)', facts)
            if before['previous'] is not None:
                cursor.execute('UPDATE scsj_fact_input_version SET disposition=\'ISOLATED\' WHERE id=%s AND disposition=\'PREVIOUS\'',
                               (before['previous'],)); require(cursor.rowcount == 1)
            cursor.execute('UPDATE scsj_fact_input_version SET disposition=\'PREVIOUS\' WHERE id=%s AND disposition=\'CURRENT\'',
                           (before['id'],)); require(cursor.rowcount == 1)
            cursor.execute('UPDATE scsj_fact_day_state SET current_input_version_id=%s,previous_input_version_id=%s '
                'WHERE account_id=%s AND stat_date=%s AND current_input_version_id=%s',
                (new_id,before['id'],account,day,before['id'])); require(cursor.rowcount == 1)
        after = current_day(db, account, day, locked=True)
        require(after['id'] == new_id and after['hash'] == material['dataHash'] and
                after['rows'][:len(old)] == old and len(after['rows']) == len(rows), 'PENDING_READBACK_FAILED')
        require([json.loads(r[2]) for r in after['rows']] == rows, 'PENDING_ALL_FIELDS_READBACK_FAILED')
        db.commit()
        return new_id
    except BaseException:
        db.rollback()
        raise


def connect(root):
    import pymysql
    secret = json.loads(private(root, root/'.local/d218-runtime/db-credentials.json').read_bytes())
    return pymysql.connect(host='127.0.0.1', port=3306, user=secret['username'], password=secret['password'],
                           database='material_dashboard', charset='utf8mb4', read_timeout=90, write_timeout=90)


def complete(ops, state, project, point):
    """Only the morning scheduler calls this; presence of an ordinary day is not a skip."""
    import copy
    from collection_resilience import HistoricalCreationLookup
    registry = PendingMaterials(ops.root)
    path, queue = registry.find(state['projectId'], state['accountId'], state['statDate'])
    if queue is None:
        return 0
    unresolved = {mid for mid,x in queue['items'].items() if x['status'] != 'COMPLETED'}
    if not unresolved:
        return 0
    age = (dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).date()-dt.date.fromisoformat(queue['statDate'])).days
    if not 2 <= age <= 8:
        registry.publish()
        return len(unresolved)
    if point in [x.get('point') for x in queue['attempts']]:
        return len(unresolved)
    queue['attempts'].append(dict(point=point,status='STARTED'))
    atomic(path, queue)
    try:
        with connect(ops.root) as db:
            before = current_day(db, queue['accountId'], queue['statDate']); db.rollback()
        present = {r[0]:json.loads(r[2]) for r in before['rows']}
        already = {mid:dict(kind='ALREADY_PRESENT',inputVersionId=before['id']) for mid in unresolved
                   if mid in present and creation(present[mid].get('material_create_time_v2'))}
        if already:
            registry.finish(path, queue, already, dict(point=point,status='RECONCILED_EXISTING_DATA'))
            unresolved -= set(already)
        if not unresolved:
            return 0
        require(not (unresolved & set(present)), 'PENDING_EXISTING_MATERIAL_UNRESOLVED')
        origin = read_evidence(ops.root, queue['sourceReceipt'])
        old_material = read_evidence(ops.root, origin['publication'][1])
        old_manifest = read_evidence(ops.root, origin['publication'][0])
        authorized={old_material['dataHash']} | {x['contentHash'] for x in queue.get('proposedVersions',[])}
        require(before['sourceType'] == 'MCP_AUTO' and before['projectRef'] == queue['projectId'] and
                before['hash'] in authorized and
                [json.loads(r[2]) for r in before['rows'][:len(old_material['rows'])]] == old_material['rows'],
                'PENDING_BASE_CHANGED_MANUAL_REVIEW')
        original_plan=read_evidence(ops.root,origin['sources'][0])
        original_project=next(x for x in original_plan['projects'] if x['id']==queue['projectId'])
        require((original_project.get('publicDouyinId') or original_project['douyinIds'])
            ==(project.get('publicDouyinId') or project['douyinIds']),'PENDING_BASE_CHANGED_MANUAL_REVIEW')
        lookup=HistoricalCreationLookup(ops.root,project,queue['statDate'])
        lookup.expected_aweme_id=origin.get('anchorResolution',{}).get('resolvedAwemeId') or lookup.expected_aweme_id
        resolved = lookup(unresolved)
        additions, proof = {}, {}
        for mid in sorted(unresolved):
            found = resolved.get(mid)
            if found and found['value'] and not found['conflict']:
                row = dict(queue['items'][mid]['row']); row['material_create_time_v2'] = found['value']
                additions[mid] = row; proof[mid] = dict(kind='VERIFIED_HISTORY',source=found['evidence'])
        rest = unresolved - set(additions)
        source_proofs = []
        conflicts={mid for mid in rest if resolved.get(mid,{}).get('conflict')}
        for mid in conflicts:queue['items'][mid]['status']='MANUAL_REVIEW'
        rest-=conflicts
        if rest:
            temporary = dict(state,plan=state.get('currentPlan') or state.get('plan'),attemptDir=str(ops.root/'.local/collection-scheduler/attempts'/uuid.uuid4().hex),
                             collectionMode='GAP_BACKFILL',completionIds=sorted(rest))
            temporary.pop('receipt', None)
            receipt_path = ops.collect(temporary, lambda *_: None)
            temporary['receipt'] = receipt_path
            ops.validate_source(temporary)
            receipt = json.loads(private(ops.root, receipt_path).read_bytes())
            incoming = read_evidence(ops.root, receipt['publication'][1])
            require(receipt.get('collectionTheme','QUANYU')==origin.get('collectionTheme','QUANYU'),
                    'PENDING_BASE_CHANGED_MANUAL_REVIEW')
            require({x['material_id'] for x in incoming['rows']} <= rest, 'PENDING_SOURCE_SCOPE_CHANGED')
            for row in incoming['rows']:
                require(creation(row.get('material_create_time_v2')), 'PENDING_SOURCE_DATE_INVALID')
                additions[row['material_id']] = row
                proof[row['material_id']] = dict(kind='RECOLLECTED',source=ops.collector.evidence(Path(receipt_path)))
            source_proofs.append(ops.collector.evidence(Path(receipt_path)))
        if not additions:
            registry.finish(path,queue,{},dict(point=point,status='STILL_UNRESOLVED'))
            return len(unresolved)
        destination = ops.root/'.local/collection-scheduler/completions'/uuid.uuid4().hex
        destination.mkdir(parents=True,mode=0o700)
        rows = [json.loads(r[2]) for r in before['rows']] + [additions[mid] for mid in sorted(additions)]
        material, manifest = copy.deepcopy(old_material), copy.deepcopy(old_manifest)
        material.update(rows=rows,dataHash=sha(encoded(rows)),dataVersion='pending-completion-'+uuid.uuid4().hex)
        material['counts'].update(reportRowCount=len(rows),zeroSpendRows=sum(ops.collector.numeric(r['stat_cost_for_roi2'])==0 for r in rows))
        manifest.update(publicationDataHash=material['dataHash'],publicationDataVersion=material['dataVersion'],**material['counts'])
        ops.collector.write_once(destination/'material_day.json',material)
        ops.collector.write_once(destination/'manifest.json',manifest)
        ops.collector.write_once(destination/'provenance.json',dict(queueSource=queue['sourceReceipt'],
            originalVersionId=before['id'],originalContentHash=before['hash'],resolved=proof,newSources=source_proofs))
        config = dict(workspaceRoot=str(ops.root),credentialsPath=ops.baseline['credentialsPath'],schemaName='material_dashboard',
            expectedAccountId=material['advertiserId'],runId='pending-'+uuid.uuid4().hex,
            autoDays=[dict(date=queue['statDate'],inputDirectory=str(destination))],manualDays=[])
        if queue['accountId'] != material['advertiserId']: config['storageAccountId']=queue['accountId']
        atomic(destination/'validate.json',config)
        result, _ = ops.java('LocalFactPreflightMain',[destination/'validate.json',queue['statDate']],
                             'Local source preflight receipt: ')
        require(result['status']=='SOURCE_READY' and result['rowCount']==len(rows),'PENDING_PUBLICATION_PREFLIGHT_FAILED')
        material['_evidence']=ops.collector.evidence(destination/'material_day.json')
        manifest['_evidence']=ops.collector.evidence(destination/'manifest.json')
        queue.setdefault('proposedVersions',[]).append(dict(baseHash=before['hash'],contentHash=material['dataHash'],
            materialEvidence=material['_evidence'],manifestEvidence=manifest['_evidence']))
        atomic(path,queue)
        atomic(destination/'intent.json',dict(expectedVersionId=before['id'],expectedHash=before['hash'],
                                             additions=sorted(additions),runId=config['runId']))
        with connect(ops.root) as db:
            version = append_only_completion(db,queue['accountId'],queue['statDate'],queue['projectId'],before,
                rows,material,manifest,config['runId'],unresolved)
        with connect(ops.root) as db:
            after = current_day(db,queue['accountId'],queue['statDate']);db.rollback()
        require(after['id']==version and after['rows'][:len(before['rows'])]==before['rows'] and
                [json.loads(r[2]) for r in after['rows']]==rows,'PENDING_INDEPENDENT_READBACK_FAILED')
        atomic(destination/'verified.json',dict(status='ALL_FIELDS_VERIFIED',versionId=version,
            oldRowsUnchanged=True,appendedRows=len(additions),contentHash=material['dataHash']))
        registry.finish(path,queue,{mid:dict(proof[mid],inputVersionId=version) for mid in additions},
                        dict(point=point,status='COMPLETED',receipt=ops.collector.evidence(destination/'verified.json')))
        return len(unresolved-set(additions))
    except Exception as error:
        # A post-commit failure is deliberately retained: next slot reconciles current rows first.
        if str(error) in ('PENDING_BASE_CHANGED_MANUAL_REVIEW','PENDING_EXISTING_MATERIAL_UNRESOLVED'):
            for mid in unresolved:queue['items'][mid]['status']='MANUAL_REVIEW'
        registry.finish(path,queue,{},dict(point=point,status='FAILED_KEEP_EVIDENCE',errorType=type(error).__name__))
        return sum(x['status']!='COMPLETED' for x in queue['items'].values())
