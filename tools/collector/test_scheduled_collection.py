import datetime as dt
import json
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from scheduled_collection import Runner, LinuxOps, Rejected, save, slot, sha, SCHEMA, locked_run


class FakeOps:
    def __init__(self):
        self.calls = []
        self.projects = [{'id': 'one', 'advertiserId': '101'}, {'id': 'two', 'advertiserId': '102'}]
        self.fail = {}
        self.present = set()
        self.recovered = None

    def call(self, method, state=None):
        key = state['projectId'] if state else None
        self.calls.append((method, key))
        if (method, key) in self.fail:
            raise self.fail[(method, key)]

    def plan(self, day):
        return Path('/synthetic-plan'), self.projects

    def exists(self, project, day):
        return project['id'] in self.present

    def collect(self, state, progress):
        self.call('collect', state)
        progress('RETRY_WAIT', '2026-10-16T01:10:00+00:00')
        progress('COLLECTING')
        return '/synthetic-receipt/' + state['projectId']

    def recover_collection(self, state):
        self.call('recover', state)
        return self.recovered

    def validate_source(self, state):
        self.call('validate', state)
        state['rows'] = 3

    def prepare(self, state):
        self.call('prepare', state)
        state['importConfig'] = '/synthetic-config'

    def backup(self, day):
        self.call('backup')

    def import_source(self, state):
        self.call('import', state)

    def verify(self, state):
        self.call('verify', state)
        return 3


class SchedulerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.ops = FakeOps()
        self.runner = Runner(self.root, self.ops)

    def tearDown(self):
        self.temp.cleanup()

    def now(self, hour=9):
        return dt.datetime(2026, 10, 16, hour-8, 0, 15, tzinfo=dt.timezone.utc)

    def state(self, project='one'):
        return next(json.loads(p.read_text()) for p in (self.runner.base / 'days/2026-10-15').glob('*.json')
                    if json.loads(p.read_text())['projectId'] == project)

    def test_slot_is_shanghai_yesterday_no_catchup(self):
        self.assertEqual(slot(self.now()), ('2026-10-15', '2026-10-16T09'))
        self.assertEqual(slot(self.now(12)), ('2026-10-15', '2026-10-16T12'))
        self.assertEqual(slot(self.now(14).replace(minute=20)), ('2026-10-15', '2026-10-16T14:20'))
        for hour in (9, 10, 11, 12, 14):
            self.assertEqual(slot(self.now(hour).replace(minute=4, second=59)), slot(self.now(hour)))
            with self.assertRaises(Rejected):
                slot(self.now(hour).replace(minute=5, second=0))
        for now in (self.now(8), self.now(15), self.now(13), self.now(14).replace(minute=19, second=59),
                    self.now(14).replace(minute=25), self.now(14).replace(minute=20) + dt.timedelta(days=1),
                    self.now(13) - dt.timedelta(days=1), self.now(14) + dt.timedelta(days=1)):
            with self.assertRaises(Rejected):
                slot(now)
        self.assertEqual(slot(self.now(14).replace(minute=24, second=59)),
                         slot(self.now(14).replace(minute=20)))

    def test_five_minute_window_keeps_failed_attempt_deduplicated(self):
        self.ops.fail[('collect', 'one')] = Rejected('PROCESS_FAILED')
        self.runner.run(self.now())
        self.runner.run(self.now().replace(minute=4, second=59))
        self.assertEqual(self.ops.calls.count(('collect', 'one')), 1)

    def test_oneoff_slot_retries_failed_but_skips_verified(self):
        self.ops.fail[('collect', 'one')] = Rejected('PROCESS_FAILED')
        self.runner.run(self.now(12))
        self.ops.fail.clear()
        self.assertEqual(self.runner.run(self.now(14).replace(minute=20))['status'], 'COMPLETE')
        self.assertEqual(self.ops.calls.count(('collect', 'one')), 2)
        self.assertEqual(self.ops.calls.count(('collect', 'two')), 1)

    def test_1420_skips_every_verified_project_without_collection(self):
        self.runner.run(self.now(14))
        before = list(self.ops.calls)
        self.assertEqual(self.runner.run(self.now(14).replace(minute=20))['status'], 'COMPLETE')
        self.assertEqual(self.ops.calls, before)

    def test_1420_has_new_identity_and_deduplicates_until_142459(self):
        self.ops.fail[('collect', 'one')] = Rejected('PROCESS_FAILED')
        # A 14:00 failed attempt must not consume the new 14:20 slot.
        self.runner.run(self.now(12))
        state = self.state()
        state['attempts'][-1] = '2026-10-16T14'
        save(self.runner.state_path(state), state)
        self.runner.run(self.now(14).replace(minute=20))
        self.runner.run(self.now(14).replace(minute=24, second=59))
        self.assertEqual(self.ops.calls.count(('collect', 'one')), 2)
        self.assertEqual(self.ops.calls.count(('collect', 'two')), 1)
        self.ops.fail.clear()
        self.runner.run(self.now(14))
        self.assertEqual(self.ops.calls.count(('collect', 'one')), 2)
        self.assertEqual(self.ops.calls.count(('collect', 'two')), 1)

    def test_slot_uses_shanghai_not_input_timezone(self):
        same_instant = self.now().astimezone(dt.timezone(dt.timedelta(hours=-7)))
        self.assertEqual(slot(same_instant), slot(self.now()))

    def test_pipeline_and_success_skips_later_slots(self):
        result = self.runner.run(self.now())
        self.assertEqual(result['status'], 'COMPLETE')
        self.assertEqual([c for c in self.ops.calls if c[0] == 'import'], [('import', 'one'), ('import', 'two')])
        original = len(self.ops.calls)
        self.runner.run(self.now(10))
        self.assertEqual(len(self.ops.calls), original)
        self.assertNotIn('accountId', json.dumps(result))
        self.assertNotIn('/synthetic', json.dumps(result))

    def test_collect_failure_isolated_later_slot_retries_once(self):
        self.ops.fail[('collect', 'one')] = Rejected('PROCESS_FAILED')
        self.assertEqual(self.runner.run(self.now())['status'], 'PARTIAL')
        self.runner.run(self.now())
        self.assertEqual(self.ops.calls.count(('collect', 'one')), 1)
        self.ops.fail.clear()
        self.assertEqual(self.runner.run(self.now(10))['status'], 'COMPLETE')
        self.assertEqual(self.ops.calls.count(('collect', 'one')), 2)
        self.assertEqual(self.ops.calls.count(('collect', 'two')), 1)

    def test_source_changed_stops_before_import_and_keeps_source(self):
        self.ops.fail[('validate', 'one')] = Rejected('SOURCE_CHANGED')
        self.runner.run(self.now())
        self.assertNotIn(('import', 'one'), self.ops.calls)
        self.runner.run(self.now(10))
        self.assertEqual(self.ops.calls.count(('collect', 'one')), 1)
        self.assertEqual(self.state()['errorCode'], 'SOURCE_CHANGED')

    def test_preexisting_unknown_facts_never_collected_or_overwritten(self):
        self.ops.present.add('one')
        self.runner.run(self.now())
        self.assertEqual(self.state()['status'], 'ALREADY_PRESENT')
        self.assertNotIn(('collect', 'one'), self.ops.calls)
        self.assertNotIn(('import', 'one'), self.ops.calls)

    def test_import_exception_requires_review_no_replay(self):
        self.ops.fail[('import', 'one')] = Rejected('PROCESS_FAILED')
        self.runner.run(self.now())
        self.assertEqual(self.state()['status'], 'REVIEW_REQUIRED')
        self.runner.run(self.now(10))
        self.assertEqual(self.ops.calls.count(('import', 'one')), 1)

    def test_abrupt_import_interruption_verifies_exact_saved_config_only(self):
        self.runner.run(self.now())
        state = self.state()
        state['status'] = 'IMPORTING'
        save(self.runner.state_path(state), state)
        self.ops.calls.clear()
        self.runner.run(self.now(10))
        self.assertEqual(self.state()['status'], 'VERIFIED')
        self.assertEqual(self.ops.calls, [('validate', 'one'), ('verify', 'one')])

    def test_interrupted_import_readback_mismatch_needs_review(self):
        self.runner.run(self.now())
        state = self.state()
        state['status'] = 'VERIFYING'
        save(self.runner.state_path(state), state)
        self.ops.fail[('verify', 'one')] = Rejected('READBACK_NOT_MATCHED')
        self.runner.run(self.now(10))
        self.assertEqual(self.state()['status'], 'REVIEW_REQUIRED')

    def test_interrupted_collection_without_receipt_never_blindly_replays(self):
        self.runner.run(self.now())
        state = self.state()
        state['status'] = 'RETRY_WAIT'
        state.pop('receipt')
        save(self.runner.state_path(state), state)
        self.runner.run(self.now(10))
        self.assertEqual(self.state()['status'], 'REVIEW_REQUIRED')
        self.assertEqual(self.ops.calls.count(('collect', 'one')), 1)

    def test_recover_published_collection_resumes_import_without_collection(self):
        self.runner.run(self.now())
        state = self.state()
        state['status'] = 'COLLECTING'
        state.pop('receipt')
        save(self.runner.state_path(state), state)
        self.ops.recovered = '/synthetic-recovered'
        self.runner.run(self.now(10))
        self.assertEqual(self.ops.calls.count(('collect', 'one')), 1)
        self.assertEqual(self.state()['status'], 'VERIFIED')

    def test_untrusted_exception_text_not_in_status(self):
        self.ops.fail[('collect', 'one')] = ValueError('secret data')
        self.runner.run(self.now())
        self.assertNotIn('secret data', (self.runner.base / 'status.json').read_text())
        self.assertEqual(self.state()['errorCode'], 'STEP_FAILED')

    def test_backup_failure_no_import_other_project_attempted(self):
        self.ops.fail[('backup', None)] = Rejected('BACKUP_FAILED')
        self.runner.run(self.now())
        self.assertFalse(any(c[0] == 'import' for c in self.ops.calls))
        self.assertIn(('collect', 'two'), self.ops.calls)

    def test_corrupt_state_preserved_and_other_project_continues(self):
        state = dict(projectId='one', accountId='101', statDate='2026-10-15')
        path = self.runner.state_path(state)
        save(path, {'broken': True})
        before = path.read_bytes()
        result = self.runner.run(self.now())
        self.assertEqual(result['status'], 'REVIEW_REQUIRED')
        self.assertEqual(path.read_bytes(), before)
        self.assertIn(('import', 'two'), self.ops.calls)

    def test_global_plan_error_publishes_failed(self):
        with patch.object(self.ops, 'plan', side_effect=Rejected('PRECHECK_REJECTED')):
            with self.assertRaises(Rejected):
                self.runner.run(self.now())
        self.assertEqual(json.loads((self.runner.base / 'status.json').read_text())['status'], 'FAILED')

    def test_credential_init_failure_replaces_previous_success_without_secret(self):
        save(self.runner.base / 'status.json', {'status': 'COMPLETE', 'statDate': '2026-10-14'})
        with self.assertRaises(Exception):
            locked_run(self.root, '2026-10-15')
        status = json.loads((self.runner.base / 'status.json').read_text())
        self.assertEqual(status['status'], 'FAILED')
        self.assertEqual(status['statDate'], '2026-10-15')
        self.assertEqual(status['projects'], [])


class AdapterTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.ops = LinuxOps.__new__(LinuxOps)
        self.ops.root = self.root
        self.ops.client = self.root / '.local/client.cnf'
        self.ops.baseline = {'credentialsPath': str(self.root / '.local/db-credentials.json')}

    def tearDown(self):
        self.temp.cleanup()

    def test_database_check_scoped_exact_account_day_three_tables(self):
        with patch.object(self.ops, 'command', return_value='0\n') as call:
            self.assertFalse(self.ops.exists({'advertiserId': '101'}, '2026-10-15'))
            query = call.call_args.args[0][-1]
            self.assertEqual(query.count("account_id='101' AND stat_date='2026-10-15'"), 3)
            self.assertIn(SCHEMA + '.SCSJ_MATERIAL_DAY_FACT', query)
        with self.assertRaises(Rejected):
            self.ops.exists({'advertiserId': "101' OR 1=1"}, '2026-10-15')

    def test_persistent_backup_once_per_day_and_changed_dump_rejected(self):
        def command(args, timeout, log=None, **kwargs):
            log.write_bytes(b'SYNTHETIC_SQL_ONLY')
            return ''
        with patch.object(self.ops, 'command', side_effect=command) as call, patch('scheduled_collection.shutil.disk_usage') as disk:
            disk.return_value.free = 5 * 1024**3
            self.ops.backup('2026-10-15')
            self.ops.backup('2026-10-15')
            self.assertEqual(call.call_count, 1)
            disk.return_value.free = 512 * 1024**2
            with self.assertRaisesRegex(Rejected, 'DISK_RESERVE_REQUIRED'):
                self.ops.backup('2026-10-15')
            disk.return_value.free = 5 * 1024**3
            directory = self.root / '.local/collection-scheduler/backups/2026-10-15'
            next(directory.glob('*.sql')).write_bytes(b'CHANGED')
            with self.assertRaisesRegex(Rejected, 'BACKUP_CHANGED'):
                self.ops.backup('2026-10-15')

    def test_prepare_rejects_existing_config_wrong_identity(self):
        state = dict(attemptDir=str(self.root / '.local/attempt'), receipt=str(self.root / '.local/run/receipt.json'),
                     statDate='2026-10-15', accountId='101', rows=3, plan='synthetic', projectId='alpha')
        from unittest.mock import Mock
        self.ops.collector = Mock()
        self.ops.collector.load_plan.return_value = ({}, {'advertiserId': '101'}, {}, [])
        save(Path(state['attemptDir']) / 'import.json', {'expectedAccountId': 'OTHER'})
        with self.assertRaisesRegex(Rejected, 'IMPORT_CONFIG_CHANGED'):
            self.ops.prepare(state)

    def test_verify_rejects_config_changed_before_jvm(self):
        config = self.root / '.local/config.json'
        save(config, {'synthetic': 1})
        state = dict(importConfig=str(config), importConfigSha256=sha(config), rows=3)
        save(config, {'synthetic': 2})
        with patch.object(self.ops, 'java') as java:
            with self.assertRaisesRegex(Rejected, 'IMPORT_CONFIG_CHANGED'):
                self.ops.verify(state)
            java.assert_not_called()

    def test_failed_collection_output_not_reused_as_success(self):
        directory = self.root / '.local/attempt'
        save(directory / 'collector-output.jsonl', {'status': 'FAILED', 'receipt': '/outside'})
        self.assertIsNone(self.ops.recover_collection({'attemptDir': str(directory)}))

    def test_twelve_waits_require_matching_previous_permit_and_yield_each_time(self):
        state = dict(attemptDir=str(self.root / '.local/attempt'), plan='/synthetic', projectId='one')
        permit = Path(state['attemptDir']) / 'retry-permit.json'
        events = []
        self.ops.yield_project = lambda: events.append('yield')
        def command(args, timeout, progress, log):
            self.assertEqual(timeout, 2400)
            for count in range(1, 13):
                progress('RETRY_WAIT', '2026-10-16T03:05:00Z', count)
                self.assertEqual(json.loads(permit.read_bytes()), {'resume': True, 'retryCount': count})
            with self.assertRaisesRegex(Rejected, 'RETRY_SEQUENCE_INVALID'):
                progress('RETRY_WAIT', None, 13)
        with patch.object(self.ops, 'command', side_effect=command), patch.object(self.ops, 'recover_collection', return_value='/receipt'):
            self.ops.collect(state, lambda *args: None)
        self.assertEqual(events, ['yield'] * 12)

    def failure_fixture(self):
        state = self.source_fixture()
        state['attemptDir'] = str(self.root / '.local/collection-scheduler/attempts' / ('b' * 32))
        original = json.loads(Path(state['receipt']).read_text())
        path = self.root / '.local/collector-runs' / ('a' * 32) / 'receipt.json'
        raw = path.parent / 'failure-response.http.json'
        save(raw, {'code': 51010, 'message': 'SECRET_NOT_FOR_PUBLIC'})
        original.update(status='FAILED', errorCode='PROTOCOL_REJECTED',
            projectRef='one', advertiserId='101', statDate='2026-10-15',
            failureEvidence=self.ops.collector.evidence(raw),
            diagnostic={'stage': 'tools/call', 'rule': 'BUSINESS_CODE_ZERO', 'group': 'base', 'page': 2,
                        'facts': {'codeType': 'integer', 'numericCode': 51010, 'retryCount': 3}})
        save(path, original)
        save(Path(state['attemptDir']) / 'collector-output.jsonl',
             {'status': 'FAILED', 'errorCode': 'PROTOCOL_REJECTED', 'receipt': str(path)})
        return state, path

    def test_failed_receipt_whitelisted_public_code(self):
        state, _ = self.failure_fixture()
        self.assertEqual(self.ops.collection_failure(state), 'PLATFORM_51010_BASE_PAGE_2_RETRIES_3')

    def test_failed_receipt_allows_twelve_retries(self):
        state, path = self.failure_fixture()
        receipt = json.loads(path.read_text())
        receipt['diagnostic']['facts']['retryCount'] = 12
        save(path, receipt)
        self.assertEqual(self.ops.collection_failure(state), 'PLATFORM_51010_BASE_PAGE_2_RETRIES_12')

    def test_collect_maps_only_process_failure_not_other_errors(self):
        state, _ = self.failure_fixture()
        with patch.object(self.ops, 'command', side_effect=Rejected('PROCESS_FAILED')):
            with self.assertRaisesRegex(Rejected, 'PLATFORM_51010_BASE_PAGE_2_RETRIES_3'):
                self.ops.collect(state, lambda *args: None)
        with patch.object(self.ops, 'command', side_effect=Rejected('STEP_TIMEOUT')):
            with self.assertRaisesRegex(Rejected, 'STEP_TIMEOUT'):
                self.ops.collect(state, lambda *args: None)

    def test_previous_permit_tampering_stops_second_resume(self):
        state = dict(attemptDir=str(self.root / '.local/attempt'), plan='/synthetic', projectId='one')
        permit = Path(state['attemptDir']) / 'retry-permit.json'
        self.ops.yield_project = lambda: None
        def command(args, timeout, progress, log):
            progress('RETRY_WAIT', None, 1)
            save(permit, {'resume': True, 'retryCount': 3})
            progress('RETRY_WAIT', None, 2)
        with patch.object(self.ops, 'command', side_effect=command):
            with self.assertRaisesRegex(Rejected, 'RETRY_PERMIT_CHANGED'):
                self.ops.collect(state, lambda *args: None)

    def test_failure_detail_rejects_bad_identity_hash_and_untrusted_fields(self):
        state, path = self.failure_fixture()
        original = json.loads(path.read_text())
        for field, value in [('group', 'SECRET'), ('page', True), ('page', 0)]:
            receipt = json.loads(json.dumps(original))
            receipt['diagnostic'][field] = value
            save(path, receipt)
            self.assertEqual(self.ops.collection_failure(state), 'PROCESS_FAILED')
        save(path, original)
        state['accountId'] = '999'
        self.assertEqual(self.ops.collection_failure(state), 'PROCESS_FAILED')
        state['accountId'] = '101'
        (path.parent / 'failure-response.http.json').write_bytes(b'changed')
        self.assertEqual(self.ops.collection_failure(state), 'PROCESS_FAILED')

    def test_failure_detail_rejects_outside_receipt_and_wrong_retry_count(self):
        state, path = self.failure_fixture()
        receipt = json.loads(path.read_text())
        receipt['diagnostic']['facts']['retryCount'] = 13
        save(path, receipt)
        self.assertEqual(self.ops.collection_failure(state), 'PROCESS_FAILED')
        save(Path(state['attemptDir']) / 'collector-output.jsonl',
             {'status': 'FAILED', 'errorCode': 'PROTOCOL_REJECTED', 'receipt': str(self.root / 'outside.json')})
        self.assertEqual(self.ops.collection_failure(state), 'PROCESS_FAILED')

    def source_fixture(self):
        def evidence(path):
            return {'absolutePath': str(path), 'sizeBytes': path.stat().st_size, 'sha256': sha(path)}
        revision = self.root / '.local/config/revisions/revision.json'
        save(revision, {'synthetic': True})
        save(revision.parent.parent / 'current.json', {'revision': 'revision'})
        plan = self.root / '.local/plan.json'
        save(plan, {'synthetic': True})
        document = {'revision': 'revision', 'statDate': '2026-10-15',
                    'configurationEvidence': {'absolutePath': str(revision)}}
        publication = self.root / '.local/collector-runs/run/publication'
        save(publication / 'manifest.json', {'synthetic': True})
        save(publication / 'material_day.json', dict(advertiserId='101', projectRef='one', statDate='2026-10-15', rows=[]))
        receipt = publication.parent / 'receipt.json'
        save(receipt, dict(status='COLLECTED_NOT_IMPORTED', databaseImported=False, transportEvidence='HTTP_BYTES',
            sources=[evidence(plan)], configurationRevision='revision',
            publication=[evidence(publication / n) for n in ('manifest.json', 'material_day.json')], rawEvidence=[]))
        self.ops.collector = SimpleNamespace(evidence=evidence, load_plan=lambda *args:
            (document, {'advertiserId': '101'}, {}, [evidence(plan)]))
        return dict(plan=str(plan), receipt=str(receipt), projectId='one', accountId='101', statDate='2026-10-15')

    def test_source_validates_exact_publication_hash_and_identity(self):
        state = self.source_fixture()
        self.ops.validate_source(state)
        self.assertEqual(state['rows'], 0)
        path = Path(state['receipt']).parent / 'publication/material_day.json'
        save(path, {'changed': True})
        with self.assertRaisesRegex(Rejected, 'SOURCE_PUBLICATION_CHANGED'):
            self.ops.validate_source(state)

    def test_source_rejects_omitted_publication_evidence(self):
        state = self.source_fixture()
        path = Path(state['receipt'])
        receipt = json.loads(path.read_text())
        receipt['publication'] = []
        save(path, receipt)
        with self.assertRaisesRegex(Rejected, 'SOURCE_PUBLICATION_CHANGED'):
            self.ops.validate_source(state)

    def test_prepare_exact_contract_preflight_and_persist_hash(self):
        state = self.source_fixture()
        state.update(attemptDir=str(self.root / '.local/attempt'), rows=0)
        with patch.object(self.ops, 'java', return_value=({'status': 'SOURCE_READY', 'rowCount': 0}, Path('/receipt'))):
            self.ops.prepare(state)
        config = Path(state['importConfig'])
        self.assertEqual(sha(config), state['importConfigSha256'])
        parsed = json.loads(config.read_text())
        self.assertEqual(parsed['expectedAccountId'], '101')
        self.assertEqual(parsed['manualDays'], [])
        self.assertEqual(parsed['autoDays'][0]['date'], '2026-10-15')


if __name__ == '__main__':
    unittest.main()
