"""Synthetic request/date/queue and real transactional append-only regression tests."""
import contextlib
import datetime as dt
import hashlib
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

import collect as c
import collection_resilience as r
import pending_materials as p
import test_collect as fixture


class CreationCollectionTests(unittest.TestCase):
    setUpClass = classmethod(fixture.CollectorTest.setUpClass.__func__)
    setUp = fixture.CollectorTest.setUp
    make_plan = fixture.CollectorTest.make_plan
    save_plan = fixture.CollectorTest.save_plan

    def execute(self, spend=0, history=None, extra=None):
        def mutation(response,args):
            row=response['result']['structuredContent']['data']['rows'][0]
            row['dimensions'].pop('material_create_time_v2',None)
            row['metrics'][c.SORT]['Value']=spend
            if extra:
                for key,value in extra.items():
                    if key in row['metrics']:row['metrics'][key]['Value']=value
        result=c.execute(self.root,self.path,'synthetic',True,transport=fixture.Fake(mutation),
                         sleeper=lambda _:None,history_lookup=history)
        receipt=c.decode(Path(result['receipt']).read_bytes())
        return result,receipt,Path(result['receipt']).parent

    def test_zero_missing_date_defers_but_raw_response_is_not_padded(self):
        result,receipt,run=self.execute()
        self.assertEqual('COLLECTED_NOT_IMPORTED',result['status'])
        self.assertEqual(1,receipt['pendingCreationTimeCount'])
        self.assertEqual([],c.decode((run/'publication/material_day.json').read_bytes())['rows'])
        proof=c.decode((run/'creation-time-resolution.json').read_bytes())
        self.assertEqual('7',proof['pending'][0]['materialId'])
        raw=c.decode((run/'raw/base/page_001.synthetic.json').read_bytes())
        self.assertNotIn('material_create_time_v2',raw['result']['structuredContent']['data']['rows'][0]['dimensions'])

    def test_positive_missing_date_uses_proven_history_and_keeps_metric(self):
        proof={'inputVersionId':3,'rowSha256':'a'*64,'statDate':'2019-12-31','accountId':'12345678'}
        result,receipt,run=self.execute(2,lambda ids:{'7':{'value':'2019-12-01 10:00:00','conflict':False,'evidence':proof}})
        self.assertEqual('COLLECTED_NOT_IMPORTED',result['status'])
        row=c.decode((run/'publication/material_day.json').read_bytes())['rows'][0]
        self.assertEqual('2',row[c.SORT]);self.assertEqual('2019-12-01 10:00:00',row['material_create_time_v2'])
        evidence=c.decode((run/'creation-time-resolution.json').read_bytes())
        self.assertEqual(proof,evidence['resolutions'][0]['source'])
        self.assertEqual(0,receipt['pendingCreationTimeCount'])

    def test_positive_without_history_or_with_conflict_stops_all_publication(self):
        for history in (None,lambda ids:{'7':{'value':None,'conflict':True}}):
            result,_,run=self.execute(1,history)
            self.assertIn(result['errorCode'],('CREATION_TIME_UNRESOLVED_POSITIVE_OR_UNKNOWN','CREATION_TIME_HISTORY_CONFLICT'))
            self.assertFalse((run/'publication').exists())

    def test_zero_with_orders_or_unknown_metric_is_not_silently_deferred(self):
        for value in (1,None,'UNKNOWN'):
            result,_,run=self.execute(extra={'total_pay_order_count_for_roi2':value})
            self.assertEqual('FAILED',result['status']);self.assertFalse((run/'publication').exists())

    def test_invalid_observed_creation_timestamp_is_rejected_even_for_zero_spend(self):
        def invalid(response,args):
            row=response['result']['structuredContent']['data']['rows'][0]
            if 'material_create_time_v2' in row['dimensions']:row['dimensions']['material_create_time_v2']['ValueStr']='not-a-date'
        result=c.execute(self.root,self.path,'synthetic',True,transport=fixture.Fake(invalid),sleeper=lambda _:None)
        self.assertEqual('ROW_REJECTED',result['errorCode'])
        self.assertFalse((Path(result['receipt']).parent/'publication').exists())

    def test_queue_register_is_idempotent_and_detects_source_tampering(self):
        result,receipt,run=self.execute()
        state=dict(projectId='synthetic',accountId='12345678',statDate='2020-01-01',receipt=result['receipt'])
        registry=p.PendingMaterials(self.root)
        self.assertEqual(1,registry.register(state,self.project,c.evidence))
        self.assertEqual(1,registry.register(state,self.project,c.evidence))
        path,queue=registry.find('synthetic','12345678','2020-01-01')
        registry.finish(path,queue,{'7':{'kind':'SYNTHETIC_VERIFIED'}},{'status':'TESTED'})
        self.assertEqual(0,registry.register(state,self.project,c.evidence))
        (run/'creation-time-resolution.json').write_bytes(b'{}')
        with self.assertRaises(RuntimeError):registry.load(path)

    def test_existing_aggregate_sentinel_preserves_raw_date_and_positive_metrics(self):
        for date in ('-', None, '', 'UNKNOWN'):
            row={'material_id':'-2','material_create_time_v2':date,'stat_cost_for_roi2':'12'}
            def no_lookup(_):raise AssertionError('Aggregate must not request historical creation dates')
            accepted,pending,resolutions=r.resolve_creation_dates([row],no_lookup,c.numeric)
            self.assertEqual([row],accepted);self.assertEqual([],pending);self.assertEqual([],resolutions)
        with self.assertRaisesRegex(RuntimeError,'ROW_REJECTED'):
            r.resolve_creation_dates([dict(row,material_id='7',material_create_time_v2='-')],lambda _: {},c.numeric)

    def test_creation_comparison_normalizes_timezone_and_never_invents(self):
        self.assertEqual('2020-01-01 08:00:00',r.creation('2020-01-01T00:00:00Z'))
        for invalid in (None,'','UNKNOWN','not-a-date'):
            self.assertIsNone(r.creation(invalid))

    def test_legacy_anchor_evidence_verifies_identity_and_rejects_modified_plan(self):
        folder=self.root/'.local/historical/publication';folder.mkdir(parents=True)
        source=folder/'material_day.json';source.write_bytes(c.encoded({'projectRef':'synthetic','rows':[]}))
        anchor=folder.parent/'anchor.json';anchor.write_text('{}')
        receipt=dict(status='COLLECTED_NOT_IMPORTED',transportEvidence='HTTP_BYTES',publication=[c.evidence(source)],
            sources=[c.evidence(self.path)],anchorResolution=dict(status='EXACT_UNIQUE_MATCH',
                resolvedAwemeId='87654321',evidence=[c.evidence(anchor)]))
        (folder.parent/'receipt.json').write_bytes(c.encoded(receipt))
        lookup=r.HistoricalCreationLookup(self.root,self.project,'2020-01-01')
        document=c.decode(source.read_bytes());proof=c.evidence(source)
        self.assertTrue(lookup.source_anchor_matches(source,document,proof['sizeBytes'],proof['sha256']))
        lookup.expected_aweme_id='99999999'
        self.assertFalse(lookup.source_anchor_matches(source,document,proof['sizeBytes'],proof['sha256']))
        lookup.expected_aweme_id='87654321';self.path.write_bytes(self.path.read_bytes()+b' ')
        with self.assertRaises(r.ResilienceRejected):lookup.source_anchor_matches(source,document,proof['sizeBytes'],proof['sha256'])

    def test_manual_data_closes_expired_pending_rows_without_any_recollection(self):
        result,_,_=self.execute();registry=p.PendingMaterials(self.root)
        state=dict(projectId='synthetic',accountId='12345678',statDate='2020-01-01',receipt=result['receipt'])
        registry.register(state,self.project,c.evidence)
        path,queue=registry.find('synthetic','12345678','2020-01-01')
        db=Database();self.addCleanup(db.connection.close)
        row=dict(queue['items']['7']['row'],material_create_time_v2='2019-12-01');raw=p.encoded(row).decode()
        db.connection.execute("INSERT INTO scsj_fact_input_version(id,account_id,stat_date,project_ref,source_type,content_hash,disposition,row_count) VALUES(1,'12345678','2020-01-01','synthetic','MANUAL_HISTORY',?,'CURRENT',1)",(p.sha(p.encoded([row])),))
        db.connection.execute("INSERT INTO scsj_material_day_fact VALUES(1,'12345678','2020-01-01','7',1,?,?)",(raw,p.sha(raw.encode())))
        db.connection.execute("INSERT INTO scsj_fact_day_state VALUES('12345678','2020-01-01',1,NULL)")
        registry.reconcile_existing(self.project,lambda _:contextlib.nullcontext(db))
        _,queue=registry.find('synthetic','12345678','2020-01-01')
        self.assertEqual('COMPLETED',queue['items']['7']['status'])
        self.assertEqual([],json.loads((registry.base.parent/'pending-status.json').read_bytes())['projects'])


class RequestControlTests(unittest.TestCase):
    def test_global_two_second_spacing_and_sixty_second_pause_survive_new_client(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory).resolve();clock=[1000.0];waits=[]
            def sleep(seconds):waits.append(seconds);clock[0]+=seconds
            fake=types.SimpleNamespace(flock=lambda *_:None,LOCK_EX=1)
            with patch.dict(sys.modules,{'fcntl':fake}):
                one=r.SharedRequestControl(root,lambda:clock[0],sleep)
                with one.request():pass
                two=r.SharedRequestControl(root,lambda:clock[0],sleep)
                with two.request():pass
                self.assertEqual(1002.0,clock[0])
                one.pause(60)
                with two.request():pass
                self.assertEqual(1062.0,clock[0]);self.assertEqual(62,sum(waits))

    def test_each_error_has_two_retries_and_global_limit_eight(self):
        with tempfile.TemporaryDirectory() as directory:
            run=Path(directory);budget={51010:2,40100:2,40000:2,400153:2,'used':0};delays=[]
            class Transport:
                control=None
                def rpc(self,method,params):return {'result':{'structuredContent':{'code':params['code']}}}
            t=Transport()
            for code in (51010,40100,40000,400153):
                c.call_with_retry(t,{'code':code},run,budget,'base',1,delays.append,None)
            self.assertEqual([60]*8,delays);self.assertEqual(8,budget['used'])
            c.call_with_retry(t,{'code':51010},run,budget,'video',1,delays.append,None)
            self.assertEqual(8,len(delays))

    def test_rate_limit_cooldown_happens_even_when_retry_budget_exhausted(self):
        with tempfile.TemporaryDirectory() as directory:
            pauses=[]
            class Transport:
                control=types.SimpleNamespace(pause=pauses.append)
                def rpc(self,*_):return {'result':{'structuredContent':{'code':40100}}}
            c.call_with_retry(Transport(),{},Path(directory),{40100:0,'used':8},'anchor',1,lambda _:None,None)
            self.assertEqual([60],pauses)


class Cursor:
    def __init__(self,db):self.db=db;self.cursor=db.connection.cursor()
    def __enter__(self):return self
    def __exit__(self,*_):self.cursor.close()
    def execute(self,sql,args=()):
        sql=sql.replace('%s','?').replace(' FOR UPDATE','')
        self.cursor.execute(sql,args)
        if self.db.break_readback and sql.startswith('UPDATE scsj_fact_day_state'):
            self.db.connection.execute("UPDATE scsj_material_day_fact SET raw_fact_json='{}' WHERE input_version_id=?",(args[0],))
        return self
    def executemany(self,sql,args):self.cursor.executemany(sql.replace('%s','?'),args)
    def fetchone(self):return self.cursor.fetchone()
    def fetchall(self):return self.cursor.fetchall()
    @property
    def rowcount(self):return self.cursor.rowcount
    @property
    def lastrowid(self):return self.cursor.lastrowid


class Database:
    def __init__(self):
        self.connection=sqlite3.connect(':memory:',isolation_level=None);self.break_readback=False
        self.connection.executescript('''CREATE TABLE scsj_fact_input_version (
            id INTEGER PRIMARY KEY AUTOINCREMENT,account_id TEXT,stat_date TEXT,project_ref TEXT,source_type TEXT,
            data_version TEXT,content_hash TEXT,schema_version TEXT,catalog_version TEXT,manifest_path TEXT,
            manifest_size_bytes INTEGER,manifest_sha256 TEXT,material_path TEXT,material_size_bytes INTEGER,
            material_sha256 TEXT,disposition TEXT,row_count INTEGER,run_id TEXT,
            UNIQUE(account_id,stat_date,content_hash));
            CREATE TABLE scsj_material_day_fact(input_version_id INTEGER,account_id TEXT,stat_date TEXT,
            material_id TEXT,source_row_number INTEGER,raw_fact_json TEXT,row_sha256 TEXT,
            UNIQUE(input_version_id,material_id));
            CREATE TABLE scsj_fact_day_state(account_id TEXT,stat_date TEXT,current_input_version_id INTEGER,
            previous_input_version_id INTEGER,PRIMARY KEY(account_id,stat_date));''')
    def cursor(self):return Cursor(self)
    def begin(self):self.connection.execute('BEGIN')
    def commit(self):self.connection.commit()
    def rollback(self):self.connection.rollback()


class CompletionTransactionTests(unittest.TestCase):
    def setUp(self):
        self.db=Database();self.addCleanup(self.db.connection.close)
        self.original={'material_id':'old','stat_time_day':'2020-01-01','material_create_time_v2':'2019-01-01','stat_cost_for_roi2':'5'}
        raw=p.encoded(self.original).decode();digest=p.sha(p.encoded([self.original]))
        self.db.connection.execute("INSERT INTO scsj_fact_input_version(id,account_id,stat_date,project_ref,source_type,content_hash,disposition,row_count) VALUES(1,'123','2020-01-01','synthetic','MCP_AUTO',?,'CURRENT',1)",(digest,))
        self.db.connection.execute("INSERT INTO scsj_material_day_fact VALUES(1,'123','2020-01-01','old',1,?,?)",(raw,p.sha(raw.encode())))
        self.db.connection.execute("INSERT INTO scsj_fact_day_state VALUES('123','2020-01-01',1,NULL)")
        self.before=p.current_day(self.db,'123','2020-01-01')

    def complete(self,mid='new',mutate=False,expected=None,allowed=None):
        rows=[dict(self.original),dict(self.original,material_id=mid,stat_cost_for_roi2='0')]
        if mutate:rows[0]['stat_cost_for_roi2']='999'
        evidence={'absolutePath':'synthetic','sizeBytes':1,'sha256':'a'*64}
        material={'dataHash':p.sha(p.encoded(rows)),'dataVersion':mid,'schemaVersion':'v','catalogVersion':'v','_evidence':evidence}
        manifest={'publicationDataHash':material['dataHash'],'_evidence':evidence}
        return p.append_only_completion(self.db,'123','2020-01-01','synthetic',expected or self.before,
            rows,material,manifest,'synthetic-run',allowed or {mid})

    def test_completion_preserves_every_old_byte_and_allows_idempotent_reconciliation(self):
        new=self.complete();after=p.current_day(self.db,'123','2020-01-01')
        self.assertEqual(new,after['id']);self.assertEqual(self.before['rows'],after['rows'][:1])
        self.assertEqual(self.before['rows'],self.db.connection.execute('SELECT material_id,source_row_number,raw_fact_json,row_sha256 FROM scsj_material_day_fact WHERE input_version_id=1').fetchall())
        with self.assertRaisesRegex(RuntimeError,'PENDING_CURRENT_VERSION_CHANGED'):self.complete()
        self.assertEqual(2,self.db.connection.execute('SELECT COUNT(*) FROM scsj_fact_input_version').fetchone()[0])

    def test_changed_old_row_or_unlisted_new_material_is_rejected_before_writes(self):
        for kwargs in ({'mutate':True},{'allowed':{'different'}}):
            with self.assertRaises(RuntimeError):self.complete(**kwargs)
        self.assertEqual(1,p.current_day(self.db,'123','2020-01-01')['id'])
        self.assertEqual(1,self.db.connection.execute('SELECT COUNT(*) FROM scsj_fact_input_version').fetchone()[0])

    def test_failed_full_readback_rolls_back_every_insert_and_pointer_change(self):
        self.db.break_readback=True
        with self.assertRaises(RuntimeError):self.complete()
        self.assertEqual(self.before,p.current_day(self.db,'123','2020-01-01'))
        self.assertEqual(1,self.db.connection.execute('SELECT COUNT(*) FROM scsj_material_day_fact').fetchone()[0])

    def test_manual_current_day_cannot_be_automatically_replaced(self):
        self.db.connection.execute("UPDATE scsj_fact_input_version SET source_type='MANUAL_HISTORY' WHERE id=1")
        with self.assertRaisesRegex(RuntimeError,'PENDING_CANNOT_REPLACE_MANUAL_DAY'):self.complete()


if __name__=='__main__':unittest.main()
