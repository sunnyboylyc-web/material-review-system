import datetime as dt
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import collect as c
import test_collect as fixture
import test_scheduled_collection_titania as scheduler_tests
import scheduled_collection_titania as s


class RoutingTests(unittest.TestCase):
    setUpClass = classmethod(fixture.CollectorTest.setUpClass.__func__)
    setUp = fixture.CollectorTest.setUp
    make_plan = fixture.CollectorTest.make_plan
    save_plan = fixture.CollectorTest.save_plan

    def execute(self, fake):
        self.sleeps = []
        result = c.execute(self.root, self.path, 'synthetic', True, transport=fake,
                           sleeper=self.sleeps.append, backfill=True)
        return result, json.loads(Path(result['receipt']).read_bytes())

    def test_both_nonempty_blocks_publication_preserves_both(self):
        fake = fixture.Fake()
        result, receipt = self.execute(fake)
        self.assertEqual(result['status'], 'FAILED')
        self.assertEqual(result['errorCode'], 'BACKFILL_THEME_CONFLICT')
        self.assertFalse((Path(result['receipt']).parent/'publication').exists())
        self.assertTrue(all(x['rows'] > 0 and x['complete'] for x in receipt['routeChecks'].values()))
        for folder in ('route-quanyu','route-chengfang'):
            self.assertTrue(any(folder in e['absolutePath'] for e in receipt['rawEvidence']))

    def test_only_a_nonempty_is_selected_after_b_complete(self):
        def empty_b(response,args):
            if args['data_topic'] == 'OVERALL_ROI_LIVE_MATERIAL_VIDEO':
                data=response['result']['structuredContent']['data']
                data['rows']=[];data['page_info'].update(total_number=0,total_page=0)
        result,receipt=self.execute(fixture.Fake(empty_b))
        self.assertEqual(result['status'],'COLLECTED_NOT_IMPORTED')
        self.assertEqual(receipt['collectionTheme'],'QUANYU')
        self.assertEqual(receipt['routeChecks']['CHENGFANG']['rows'],0)

    def test_conflict_failure_is_verified_before_scheduler_exposes_it(self):
        result,receipt=self.execute(fixture.Fake())
        receipt['transportEvidence']='HTTP_BYTES'
        Path(result['receipt']).write_text(json.dumps(receipt))
        attempt=self.root/'.local/collection-scheduler/attempts'/'synthetic'
        attempt.mkdir(parents=True)
        (attempt/'collector-output.jsonl').write_text(json.dumps(result)+'\n')
        plan,project,_,_=c.load_plan(self.root,self.path,'synthetic')
        state=dict(projectId='synthetic',accountId=project.get('storageAccountId',project['advertiserId']),statDate=plan['statDate'],plan=str(self.path),attemptDir=str(attempt),collectionMode='GAP_BACKFILL')
        ops=object.__new__(s.LinuxOps);ops.root=self.root;ops.collector=c
        self.assertEqual(ops.collection_failure(state),'BACKFILL_THEME_CONFLICT')
        self.assertEqual(state['conflictReceipt'],result['receipt'])
        raw=Path(receipt['rawEvidence'][0]['absolutePath']);raw.write_bytes(raw.read_bytes()+b' ')
        self.assertEqual(ops.collection_failure(state),'PROCESS_FAILED')

    def test_a_nonempty_b_error_cannot_publish_a(self):
        def broken_b(response,args):
            if args['data_topic'] == 'OVERALL_ROI_LIVE_MATERIAL_VIDEO':
                response['result']['structuredContent']['code']=51010
        result,_=self.execute(fixture.Fake(broken_b))
        self.assertEqual(result['status'],'FAILED')
        self.assertEqual(self.sleeps,[60])
        self.assertFalse((Path(result['receipt']).parent/'publication').exists())

    def test_empty_a_routes_b_keeps_a_evidence(self):
        def empty_a(response, args):
            if args['data_topic'] != 'OVERALL_ROI_LIVE_MATERIAL_VIDEO':
                data=response['result']['structuredContent']['data']
                data['rows']=[];data['page_info'].update(total_number=0,total_page=0)
        result, receipt = self.execute(fixture.Fake(empty_a))
        self.assertEqual(result['status'], 'COLLECTED_NOT_IMPORTED')
        self.assertEqual(receipt['collectionTheme'], 'CHENGFANG')
        self.assertTrue(any('route-quanyu' in e['absolutePath'] for e in receipt['rawEvidence']))

    def test_both_empty_publishes_complete_zero_day(self):
        result, receipt = self.execute(fixture.Fake(empty=True))
        self.assertEqual(result['status'], 'COLLECTED_NOT_IMPORTED')
        material=json.loads((Path(result['receipt']).parent/'publication/material_day.json').read_bytes())
        self.assertEqual(material['rows'], [])
        self.assertTrue(material['paginationComplete'])

    def test_each_error_has_bounded_60_second_budget_and_never_routes_after_error(self):
        for code,budget in [(51010,1),(40100,2),(40000,2),(400153,2)]:
            with self.subTest(code=code):
                def failure(response,args):response['result']['structuredContent']['code']=code
                fake=fixture.Fake(failure)
                result,_=self.execute(fake)
                self.assertEqual(result['status'],'FAILED')
                self.assertEqual(self.sleeps,[60]*budget)
                calls=[p['arguments'] for m,p in fake.calls if m=='tools/call']
                self.assertEqual(len(calls),1+budget)
                self.assertTrue(all(a==calls[0] for a in calls))


class SchedulingTests(unittest.TestCase):
    def test_conflict_is_review_required_no_import_no_automatic_retry(self):
        with tempfile.TemporaryDirectory() as tmp:
            ops=scheduler_tests.existing.FakeOps()
            ops.fail[('collect','two')]=s.Rejected('BACKFILL_THEME_CONFLICT')
            runner=s.Runner(Path(tmp).resolve(),ops)
            now=dt.datetime.fromisoformat('2026-10-23T08:00:00+08:00')
            runner.run(now)
            self.assertEqual(ops.calls.count(('collect','two')),7)
            self.assertTrue(all(p['status']=='REVIEW_REQUIRED' for p in runner.public['projects'] if p['projectId']=='two'))
            runner.run(now.replace(minute=30))
            self.assertEqual(ops.calls.count(('collect','two')),7)
            self.assertNotIn(('import','two'),ops.calls)
            ops.present.add('two')
            runner.run(now.replace(minute=30))
            self.assertTrue(all(p['status']=='ALREADY_PRESENT' for p in runner.public['projects'] if p['projectId']=='two'))

    def test_seven_days_excludes_yesterday_today_and_expired(self):
        now=dt.datetime.fromisoformat('2026-10-23T08:00:00+08:00')
        self.assertEqual(s.repair_dates(now),[f'2026-10-{day}' for day in range(15,22)])

    def test_manual_days_skip_and_failed_days_retry_at_next_slot(self):
        with tempfile.TemporaryDirectory() as tmp:
            ops=scheduler_tests.existing.FakeOps()
            ops.present.add('one')
            ops.fail[('collect','two')]=s.Rejected('PROCESS_FAILED')
            runner=s.Runner(Path(tmp).resolve(),ops)
            now=dt.datetime.fromisoformat('2026-10-23T08:00:00+08:00')
            runner.run(now)
            self.assertEqual(ops.calls.count(('collect','two')),7)
            self.assertNotIn(('collect','one'),ops.calls)
            runner.run(now.replace(minute=30))
            self.assertEqual(ops.calls.count(('collect','two')),14)
            self.assertEqual(len(runner.public['projects']),14)
            self.assertEqual({p['statDate'] for p in runner.public['projects']},set(s.repair_dates(now)))

if __name__=='__main__':unittest.main()
