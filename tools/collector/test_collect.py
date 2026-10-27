import copy
import io
import json
from pathlib import Path
import tempfile
import unittest
from decimal import Decimal
from unittest.mock import patch

import collect as c


class Fake:
    def __init__(self, mutation=None, empty=False):
        self.calls = []
        self.mutation = mutation
        self.empty = empty

    def rpc(self, method, params):
        self.calls.append((method, params))
        if method == 'tools/list':
            return {'result': {'tools': [{'name': c.TOOL}]}}
        if method != 'tools/call':
            return {'result': {}}
        args = params['arguments']
        dims = {k: {'Value': '7', 'ValueStr': 'sample'} for k in args['dimensions']}
        dims['stat_time_day']['ValueStr'] = args['start_time'][:10]
        if 'material_create_time_v2' in dims:dims['material_create_time_v2']['ValueStr']='2019-12-01 00:00:00'
        metrics = {k: {'Value': 0} for k in args['metrics']}
        row = {'dimensions': dims, 'metrics': metrics}
        response = {'result': {'structuredContent': {'code': 0, 'request_id': 'synthetic-request',
                    'data': {'page_info': {'page': 1, 'page_size': args['page_size'],
                    'total_page': 1, 'total_number': 0 if self.empty else 1},
                    'rows': [] if self.empty else [row]}}}}
        if self.mutation:
            self.mutation(response, args)
        return response


class CollectorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repo = Path(__file__).resolve().parents[2]
        cls.runs = cls.repo / 'qa' / 'runs' / 'collector-unit'
        cls.runs.mkdir(exist_ok=True, parents=True)

    def setUp(self):
        self.root = Path(tempfile.mkdtemp(dir=self.runs)).resolve()
        (self.root / '.local' / 'config' / 'revisions').mkdir(parents=True)
        profile_path = self.root / c.PROFILE
        profile_path.parent.mkdir(parents=True)
        profile_path.write_bytes((self.repo / c.PROFILE).read_bytes())
        self.profile = c.decode(profile_path.read_bytes())
        self.project = {'id': 'synthetic', 'name': 'Synthetic', 'advertiserId': '12345678',
                        'douyinIds': ['87654321']}
        self.make_plan(self.profile['metrics'])

    def make_plan(self, selected):
        config = {'schemaVersion': 'project-configuration-v2', 'metricProfile': 'material-33-v1',
                  'enabledMetrics': selected, 'projects': [dict(self.project, enabled=True)]}
        content = c.encoded(config)
        revision = c.sha(content)
        config_path = self.root / '.local/config/revisions' / (revision + '.json')
        config_path.write_bytes(content)
        profile_bytes = (self.root / c.PROFILE).read_bytes()
        effective = copy.deepcopy(self.profile)
        effective['metrics'] = selected
        for group in effective['groups']:
            group['metrics'] = [m for m in group['metrics'] if m in selected]
        self.plan = {'schemaVersion': 'collection-plan-v1', 'status': 'CONFIGURATION_READY_NOT_EXECUTED',
                     'networkEnabled': False, 'collectorExecuted': False, 'timeZone': 'Asia/Shanghai',
                     'statDate': '2020-01-01', 'configurationEvidence': c.evidence(config_path),
                     'revision': revision, 'projects': [self.project], 'enabledProjectCount': 1,
                     'metricProfileEvidence': {'resource': '/configuration/material-33-v1.json',
                     'sizeBytes': len(profile_bytes), 'sha256': c.sha(profile_bytes)}, 'profile': effective,
                     'acquisitionContract': {'catalogId': 'material-33-v1', 'catalogSha256': c.sha(profile_bytes),
                     'enabledMetrics': selected, 'selectionSha256': c.sha(c.encoded(selected)),
                     'configurationRevision': revision}}
        self.path = self.root / '.local/plan.json'
        self.save_plan()

    def save_plan(self):
        self.path.write_bytes(c.encoded(self.plan))

    def run_fake(self, fake=None):
        return c.execute(self.root, self.path, 'synthetic', True, transport=fake or Fake(), sleeper=lambda _: None)

    def test_selected_chengfang_uses_only_its_filters_and_keeps_fields(self):
        self.project['collectionTheme'] = 'CHENGFANG'
        self.make_plan(self.profile['metrics'])
        fake = Fake()
        result = self.run_fake(fake)
        self.assertEqual(result['status'], 'COLLECTED_NOT_IMPORTED')
        receipt = c.decode(Path(result['receipt']).read_bytes())
        self.assertEqual(receipt['collectionTheme'], 'CHENGFANG')
        for method, params in fake.calls:
            if method != 'tools/call': continue
            args = params['arguments']
            self.assertEqual(args['data_topic'], 'OVERALL_ROI_LIVE_MATERIAL_VIDEO')
            self.assertEqual({f['field'] for f in args['filters']}, {'anchor_id', 'roi2_material_type_v3'})
        material = c.decode((Path(result['receipt']).parent/'publication/material_day.json').read_bytes())
        self.assertEqual(len(material['rows'][0]), 33)
        self.assertEqual(material['collectionSelection']['theme'], 'CHENGFANG')

    def test_zero_page_is_only_valid_for_a_closed_empty_result(self):
        def empty_zero(response,args): response['result']['structuredContent']['data']['page_info']['total_page']=0
        result=self.run_fake(Fake(empty_zero,empty=True))
        self.assertEqual(result['status'],'COLLECTED_NOT_IMPORTED')
        self.assertEqual(self.run_fake(Fake(empty_zero))['errorCode'],'PAGE_REJECTED')

    def test_theme_plan_tampering_and_invalid_theme_rejected(self):
        self.project['collectionTheme'] = 'CHENGFANG'
        self.make_plan(self.profile['metrics'])
        self.plan['projects'][0] = dict(self.project, collectionTheme='QUANYU')
        self.save_plan()
        with self.assertRaises(c.Rejected): c.load_plan(self.root,self.path,'synthetic')
        self.project['collectionTheme'] = 'AUTO'
        self.make_plan(self.profile['metrics'])
        with self.assertRaises(c.Rejected): c.load_plan(self.root,self.path,'synthetic')

    def test_dry_run_no_network_or_credentials(self):
        with patch.object(c, 'HttpTransport', side_effect=AssertionError()):
            self.assertEqual('READY_NOT_EXECUTED', c.execute(self.root, self.path, 'synthetic')['status'])
        self.assertFalse((self.root / '.local/collector-runs').exists())

    def test_success_zero_preserved_and_unique_runs(self):
        first = self.run_fake()
        second = self.run_fake()
        self.assertNotEqual(first['receipt'], second['receipt'])
        self.assertEqual('COLLECTED_NOT_IMPORTED', first['status'])
        pub = Path(first['receipt']).parent / 'publication'
        material = c.decode((pub / 'material_day.json').read_bytes())
        self.assertEqual(33, len(material['rows'][0]))
        self.assertEqual(1, material['counts']['zeroSpendRows'])
        self.assertEqual(self.plan['acquisitionContract'], material['acquisitionContract'])
        self.assertEqual(c.sha(c.encoded(material['rows'])), material['dataHash'])

    def test_empty_day(self):
        result = self.run_fake(Fake(empty=True))
        self.assertEqual('COLLECTED_NOT_IMPORTED', result['status'])

    def test_optional_metric_removal(self):
        selected = [m for m in self.profile['metrics'] if m != 'video_like_count_for_roi2']
        self.make_plan(selected)
        result = self.run_fake()
        self.assertEqual('COLLECTED_NOT_IMPORTED', result['status'])
        material = c.decode((Path(result['receipt']).parent / 'publication/material_day.json').read_bytes())
        self.assertEqual(32, len(material['rows'][0]))
        self.assertNotIn('video_like_count_for_roi2', material['rows'][0])

    def test_required_metric_reject(self):
        self.make_plan([m for m in self.profile['metrics'] if m != c.SORT])
        with self.assertRaises(c.Rejected):
            self.run_fake()

    def test_plan_tamper_reject(self):
        self.plan['projects'][0]['advertiserId'] = '99999999'
        self.save_plan()
        with self.assertRaises(c.Rejected):
            self.run_fake()

    def test_config_hash_tamper(self):
        Path(self.plan['configurationEvidence']['absolutePath']).write_bytes(b'{}')
        with self.assertRaises(c.Rejected):
            self.run_fake()

    def test_profile_and_contract_tamper(self):
        self.plan['acquisitionContract']['selectionSha256'] = '0' * 64
        self.save_plan()
        with self.assertRaises(c.Rejected):
            self.run_fake()

    def test_wrong_project_and_future(self):
        with self.assertRaises(c.Rejected):
            c.execute(self.root, self.path, 'other')
        self.plan['statDate'] = '2200-01-01'
        self.save_plan()
        with self.assertRaises(c.Rejected):
            self.run_fake()

    def test_missing_key_not_padded(self):
        def mutate(raw, args):
            del raw['result']['structuredContent']['data']['rows'][0]['metrics'][args['metrics'][-1]]
        result = self.run_fake(Fake(mutate))
        self.assertEqual('ROW_REJECTED', result['errorCode'])
        self.assertFalse((Path(result['receipt']).parent / 'publication').exists())

    def test_missing_descriptors_normalized_null_without_mutating_input(self):
        group = self.profile['groups'][0]
        response = Fake().rpc('tools/call', {'arguments': dict(group, start_time='2020-01-01', page_size=100)})
        row = response['result']['structuredContent']['data']['rows'][0]
        for key in c.OPTIONAL_DESCRIPTORS:
            row['dimensions'].pop(key)
        original = copy.deepcopy(row)
        result = c.flatten(row, group, '2020-01-01')
        self.assertEqual(original, row)
        self.assertTrue(all(result[key] is None for key in c.OPTIONAL_DESCRIPTORS))
        self.assertEqual('0', result[c.SORT])
        for key in ('stat_time_day', 'material_id'):
            with self.subTest(key=key):
                invalid = copy.deepcopy(row)
                invalid['dimensions'].pop(key)
                with self.assertRaises(c.Rejected):
                    c.flatten(invalid, group, '2020-01-01')
        row['dimensions']['unexpected'] = None
        with self.assertRaises(c.Rejected):
            c.flatten(row, group, '2020-01-01')

    def test_missing_descriptors_full_collection_keeps_raw_absence(self):
        def mutate(raw, args):
            for row in raw['result']['structuredContent']['data']['rows']:
                for key in c.OPTIONAL_DESCRIPTORS:
                    row['dimensions'].pop(key, None)
        result = self.run_fake(Fake(mutate))
        self.assertEqual('COLLECTED_NOT_IMPORTED', result['status'])
        run = Path(result['receipt']).parent
        material = c.decode((run/'publication/material_day.json').read_bytes())
        self.assertEqual(33, len(material['rows'][0]))
        self.assertTrue(all(material['rows'][0][key] is None for key in c.OPTIONAL_DESCRIPTORS))
        raw = c.decode((run/'raw/base/page_001.synthetic.json').read_bytes())
        dims = raw['result']['structuredContent']['data']['rows'][0]['dimensions']
        self.assertTrue(c.OPTIONAL_DESCRIPTORS.isdisjoint(dims))

    def test_unknown_spend_rejected_not_zero(self):
        def mutate(raw, args):
            raw['result']['structuredContent']['data']['rows'][0]['metrics'][c.SORT]['Value'] = None
        self.assertEqual('ROW_REJECTED', self.run_fake(Fake(mutate))['errorCode'])

    def test_group_difference_rejected(self):
        def mutate(raw, args):
            raw['result']['structuredContent']['data']['rows'][0]['metrics'][c.SORT]['Value'] = 1
            if args['page_size'] == 200:
                raw['result']['structuredContent']['data']['rows'][0]['dimensions']['material_id']['Value'] = '8'
        self.assertEqual('GROUP_MISMATCH', self.run_fake(Fake(mutate))['errorCode'])

    def test_zero_spend_missing_whole_group_preserved_unknown(self):
        def mutate(raw, args):
            data = raw['result']['structuredContent']['data']
            if 'video_like_count_for_roi2' in args['metrics']:
                data['rows'] = []
                data['page_info']['total_number'] = 0
            else:
                for key in ('live_show_count_for_roi2_v2', 'total_ecpm_for_roi2'):
                    if key in args['metrics']:
                        data['rows'][0]['metrics'][key]['Value'] = 17
        result = self.run_fake(Fake(mutate))
        self.assertEqual('COLLECTED_NOT_IMPORTED', result['status'])
        run = Path(result['receipt']).parent
        material = c.decode((run / 'publication/material_day.json').read_bytes())
        row = material['rows'][0]
        self.assertEqual(set(self.profile['dimensions'] + self.profile['metrics']), set(row))
        self.assertEqual('0', row[c.SORT])
        self.assertEqual('17', row['live_show_count_for_roi2_v2'])
        self.assertEqual('17', row['total_ecpm_for_roi2'])
        video_fields = [k for k in self.profile['groups'][1]['metrics'] if k != c.SORT]
        self.assertTrue(all(row[k] == 'UNKNOWN' for k in video_fields))
        receipt = c.decode(Path(result['receipt']).read_bytes())
        self.assertEqual([{'materialId': '7', 'group': 'video',
                           'reason': 'BASE_ZERO_SPEND_GROUP_ROW_ABSENT',
                           'unknownFields': video_fields}], receipt['missingGroupRows'])
        raw = c.decode((run / 'raw/video/page_001.synthetic.json').read_bytes())
        self.assertEqual([], raw['result']['structuredContent']['data']['rows'])
        before = {p: p.read_bytes() for p in run.rglob('*') if p.is_file()}
        self.assertEqual('COLLECTED_NOT_IMPORTED', self.run_fake(Fake(mutate))['status'])
        self.assertTrue(all(p.read_bytes() == value for p, value in before.items()))

    def test_positive_spend_missing_whole_group_rejected(self):
        def mutate(raw, args):
            data = raw['result']['structuredContent']['data']
            data['rows'][0]['metrics'][c.SORT]['Value'] = '0.000000000001'
            if 'video_like_count_for_roi2' in args['metrics']:
                data['rows'] = []
                data['page_info']['total_number'] = 0
        result = self.run_fake(Fake(mutate))
        self.assertEqual('GROUP_MISMATCH', result['errorCode'])
        self.assertFalse((Path(result['receipt']).parent / 'publication').exists())

    def test_zero_spend_existing_malformed_group_still_rejected(self):
        def mutate(raw, args):
            if 'video_like_count_for_roi2' in args['metrics']:
                del raw['result']['structuredContent']['data']['rows'][0]['metrics']['video_like_count_for_roi2']
        self.assertEqual('ROW_REJECTED', self.run_fake(Fake(mutate))['errorCode'])

    def test_missing_group_only_marks_selected_fields_unknown(self):
        # Canonical catalog ordering is required by the configuration contract.
        self.make_plan([m for m in self.profile['metrics'] if m in self.profile['currentDashboardRequiredMetrics']])
        def mutate(raw, args):
            if args['page_size'] == 200:
                data = raw['result']['structuredContent']['data']
                data['rows'] = []
                data['page_info']['total_number'] = 0
        result = self.run_fake(Fake(mutate))
        self.assertEqual('COLLECTED_NOT_IMPORTED', result['status'])
        receipt = c.decode(Path(result['receipt']).read_bytes())
        self.assertEqual(4, len(receipt['missingGroupRows']))
        self.assertTrue(all(item['unknownFields'] == [] for item in receipt['missingGroupRows']))
        row = c.decode((Path(result['receipt']).parent / 'publication/material_day.json').read_bytes())['rows'][0]
        self.assertEqual(10, len(row))
        self.assertNotIn('UNKNOWN', row.values())

    def test_page_incomplete(self):
        def mutate(raw, args):
            raw['result']['structuredContent']['data']['page_info']['total_number'] = 2
        self.assertEqual('PAGE_REJECTED', self.run_fake(Fake(mutate))['errorCode'])

    def test_failure_diagnostic_whitelist(self):
        for field, value, rule in [('page_size', 'sensitive-string', 'PAGE_INTEGER_TYPE'),
                                   ('page_size', 200, 'PAGE_VALUE_MATCH'),
                                   ('has_more', True, 'HAS_MORE_MATCH')]:
            def mutate(raw, args):
                raw['result']['structuredContent']['data']['page_info'][field] = value
            result = self.run_fake(Fake(mutate))
            receipt = c.decode(Path(result['receipt']).read_bytes())
            self.assertEqual(rule, receipt['diagnostic']['rule'])
            self.assertEqual('base', receipt['diagnostic']['group'])
            self.assertEqual(1, receipt['diagnostic']['page'])
            self.assertNotIn('sensitive-string', json.dumps(receipt))
            self.assertFalse((Path(result['receipt']).parent / 'publication').exists())

    def test_business_string_code_not_recorded(self):
        def mutate(raw, args):
            raw['result']['structuredContent']['code'] = 'secret-arbitrary-code'
        result = self.run_fake(Fake(mutate))
        receipt = c.decode(Path(result['receipt']).read_bytes())
        self.assertEqual('BUSINESS_CODE_ZERO', receipt['diagnostic']['rule'])
        self.assertEqual('string', receipt['diagnostic']['facts']['codeType'])
        self.assertIsNone(receipt['diagnostic']['facts']['numericCode'])
        self.assertNotIn('secret-arbitrary-code', json.dumps(receipt))

    def test_failure_http_evidence_safe_only(self):
        for unsafe in (False, True):
            client = c.HttpTransport('synthetic-secret-value')
            def open_response(req, timeout):
                payload = c.decode(req.data)
                return io.BytesIO(c.encoded({'jsonrpc': '2.0', 'id': payload.get('id'),
                    'error': {'code': -32000, 'message': 'synthetic-secret-value' if unsafe else 'untrusted-private-only'}}))
            with patch.object(client.opener, 'open', side_effect=open_response):
                result = self.run_fake(client)
            receipt = c.decode(Path(result['receipt']).read_bytes())
            self.assertEqual(not unsafe, 'failureEvidence' in receipt)
            self.assertNotIn('untrusted-private-only', json.dumps(receipt))
            self.assertNotIn('synthetic-secret-value', json.dumps(receipt))
            self.assertFalse((Path(result['receipt']).parent / 'publication').exists())
            if not unsafe:
                self.assertEqual(-32000, receipt['diagnostic']['facts']['numericCode'])
                item = receipt['failureEvidence']
                self.assertEqual(item, c.evidence(Path(item['absolutePath'])))

    def test_transport_error_sanitized(self):
        class Broken(Fake):
            def rpc(self, *args):
                raise RuntimeError('sensitive-key-and-url')
        result = self.run_fake(Broken())
        self.assertEqual('COLLECTION_FAILED', result['errorCode'])
        self.assertNotIn('sensitive', Path(result['receipt']).read_text())

    def test_external_plan_rejected(self):
        path = self.root / 'external-plan.json'
        path.write_bytes(self.path.read_bytes())
        with self.assertRaises(c.Rejected):
            c.execute(self.root, path, 'synthetic')

    def test_unknown_optional_value_preserved(self):
        def mutate(raw, args):
            if 'video_like_count_for_roi2' in args['metrics']:
                raw['result']['structuredContent']['data']['rows'][0]['metrics']['video_like_count_for_roi2']['Value'] = None
        result = self.run_fake(Fake(mutate))
        material = c.decode((Path(result['receipt']).parent / 'publication/material_day.json').read_bytes())
        self.assertIsNone(material['rows'][0]['video_like_count_for_roi2'])

    def test_duplicate_rows(self):
        def mutate(raw, args):
            data = raw['result']['structuredContent']['data']
            data['rows'].append(copy.deepcopy(data['rows'][0]))
            data['page_info']['total_number'] = 2
        self.assertEqual('ROW_REJECTED', self.run_fake(Fake(mutate))['errorCode'])

    def test_extra_zero_row_retained_as_raw_evidence(self):
        def mutate(raw, args):
            if args['page_size'] == 200:
                data = raw['result']['structuredContent']['data']
                extra = copy.deepcopy(data['rows'][0])
                extra['dimensions']['material_id']['Value'] = '8'
                data['rows'].append(extra)
                data['page_info']['total_number'] = 2
        result = self.run_fake(Fake(mutate))
        material = c.decode((Path(result['receipt']).parent / 'publication/material_day.json').read_bytes())
        self.assertEqual(4, material['counts']['zeroSpendExcludedRows'])
        self.assertEqual(1, material['counts']['reportRowCount'])

    def test_retry_transient_then_success(self):
        class Retry(Fake):
            failed = False
            def rpc(self, method, params):
                if method == 'tools/call' and not self.failed:
                    self.failed = True
                    return {'result': {'structuredContent': {'code': 51010}}}
                return super().rpc(method, params)
        self.assertEqual('COLLECTED_NOT_IMPORTED', self.run_fake(Retry())['status'])

    def test_51010_waits_5_and_retries_exact_frozen_request(self):
        requests = []
        class Retry(Fake):
            def rpc(self, method, params):
                if method == 'tools/call':
                    requests.append(copy.deepcopy(params))
                    if len(requests) == 1:
                        return {'result': {'structuredContent': {'code': 51010}}}
                return super().rpc(method, params)
        sleeps = []
        def wait(seconds):
            sleeps.append(seconds)
            self.assertEqual(1, len(requests))
        gates = []
        def gate(sequence):
            self.assertEqual(1, sequence)
            self.assertEqual([60], sleeps)
            self.assertEqual(1, len(requests))
            gates.append('parent-permitted')
        result = c.execute(self.root, self.path, 'synthetic', True, transport=Retry(), sleeper=wait, retry_gate=gate)
        self.assertEqual('COLLECTED_NOT_IMPORTED', result['status'])
        self.assertEqual([60], sleeps)
        self.assertEqual(['parent-permitted'], gates)
        self.assertEqual(requests[0], requests[1])
        run = Path(result['receipt']).parent
        self.assertEqual('RETRY_WAIT', c.decode((run/'retry-wait.json').read_bytes())['status'])
        self.assertEqual(0, c.decode((run/'retry-finished.json').read_bytes())['numericCode'])

    def test_persistent_51010_only_three_calls_and_no_publication(self):
        calls = []
        class Always(Fake):
            def rpc(self, method, params):
                if method == 'tools/call':
                    calls.append(params)
                    return {'result': {'structuredContent': {'code': 51010}}}
                return super().rpc(method, params)
        sleeps = []
        result = c.execute(self.root, self.path, 'synthetic', True, transport=Always(), sleeper=sleeps.append)
        self.assertEqual('FAILED', result['status'])
        self.assertEqual(3, len(calls))
        self.assertEqual([60, 60], sleeps)
        self.assertTrue(all(item == calls[0] for item in calls))
        receipt = c.decode(Path(result['receipt']).read_bytes())
        self.assertEqual(self.project['id'], receipt['projectRef'])
        self.assertEqual(self.project['advertiserId'], receipt['advertiserId'])
        self.assertEqual(self.plan['statDate'], receipt['statDate'])
        self.assertFalse((Path(result['receipt']).parent/'publication').exists())

    def test_51010_later_group_gets_independent_page_retry(self):
        calls = []
        class Later(Fake):
            def rpc(self, method, params):
                if method == 'tools/call':
                    calls.append(params)
                    if len(calls) in (1, 3):
                        return {'result': {'structuredContent': {'code': 51010}}}
                return super().rpc(method, params)
        sleeps = []
        result = c.execute(self.root, self.path, 'synthetic', True, transport=Later(), sleeper=sleeps.append)
        self.assertEqual('COLLECTED_NOT_IMPORTED', result['status'])
        self.assertEqual([60, 60], sleeps)
        self.assertEqual(7, len(calls))
        run = Path(result['receipt']).parent
        self.assertEqual(1, c.decode((run/'retry-wait.json').read_bytes())['retryCount'])
        self.assertEqual(2, c.decode((run/'retry-02-wait.json').read_bytes())['retryCount'])
        self.assertEqual(0, c.decode((run/'retry-02-finished.json').read_bytes())['numericCode'])

    def test_four_error_budgets_shared_across_groups_stop_at_eight(self):
        attempts={}
        class EachGroup(Fake):
            def rpc(self,method,params):
                if method=='tools/call':
                    key=tuple(params['arguments']['metrics'])
                    if key not in attempts:attempts[key]=0
                    group=list(attempts).index(key);attempts[key]+=1
                    if attempts[key]<=2 or group==4:
                        return {'result':{'structuredContent':{'code':[51010,40100,40000,400153,51010][group]}}}
                return super().rpc(method,params)
        sleeps,gates=[],[]
        result=c.execute(self.root,self.path,'synthetic',True,transport=EachGroup(),sleeper=sleeps.append,retry_gate=gates.append)
        self.assertEqual('FAILED',result['status']);self.assertEqual([60]*8,sleeps)
        self.assertEqual(list(range(1,9)),gates);self.assertEqual([3,3,3,3,1],list(attempts.values()))
        run=Path(result['receipt']).parent
        self.assertTrue((run/'retry-08-finished.json').exists());self.assertFalse((run/'retry-09-wait.json').exists())
        self.assertEqual(8,c.decode((run/'receipt.json').read_bytes())['diagnostic']['facts']['retryCount'])

    def test_second_page_persistent_failure_stops_at_page_budget(self):
        calls = []
        class LaterFailure(Fake):
            def rpc(self, method, params):
                if method == 'tools/call':
                    calls.append(copy.deepcopy(params))
                    if len(calls) == 1 or len(calls) >= 3:
                        return {'result': {'structuredContent': {'code': 51010}}}
                return super().rpc(method, params)
        sleeps = []
        result = c.execute(self.root, self.path, 'synthetic', True, transport=LaterFailure(), sleeper=sleeps.append)
        self.assertEqual('FAILED', result['status'])
        self.assertEqual([60, 60], sleeps)
        self.assertEqual(4, len(calls))
        self.assertEqual(calls[2], calls[3])
        run = Path(result['receipt']).parent
        self.assertFalse((run/'retry-03-wait.json').exists())
        facts = c.decode((run/'receipt.json').read_bytes())['diagnostic']['facts']
        self.assertEqual(2, facts['retryCount'])
        self.assertEqual(0, facts['pageRetryCount'])

    def test_51010_later_page_can_retry_without_repeating_previous_page(self):
        attempts = {}
        class TwoPages(Fake):
            def rpc(self, method, params):
                if method != 'tools/call':
                    return super().rpc(method, params)
                args = params['arguments']
                base = 'material_create_time_v2' in args['dimensions']
                key = (base, args['page'])
                attempts[key] = attempts.get(key, 0) + 1
                if base and attempts[key] == 1:
                    return {'result': {'structuredContent': {'code': 51010}}}
                response = super().rpc(method, params)
                if base:
                    data = response['result']['structuredContent']['data']
                    data['page_info'].update(page=args['page'], total_page=2, total_number=2)
                    data['rows'][0]['dimensions']['material_id']['Value'] = str(6 + args['page'])
                return response
        sleeps = []
        result = c.execute(self.root, self.path, 'synthetic', True, transport=TwoPages(), sleeper=sleeps.append)
        self.assertEqual('COLLECTED_NOT_IMPORTED', result['status'])
        self.assertEqual([60, 60], sleeps)
        self.assertEqual(2, attempts[(True, 1)])
        self.assertEqual(2, attempts[(True, 2)])

    def test_interrupted_wait_does_not_retry_or_publish(self):
        class Always(Fake):
            def rpc(self, method, params):
                if method == 'tools/call':
                    return {'result': {'structuredContent': {'code': 51010}}}
                return super().rpc(method, params)
        def interrupted(seconds):
            raise RuntimeError('synthetic interruption')
        result = c.execute(self.root, self.path, 'synthetic', True, transport=Always(), sleeper=interrupted)
        run = Path(result['receipt']).parent
        self.assertEqual('FAILED', result['status'])
        self.assertTrue((run/'retry-wait.json').exists())
        self.assertFalse((run/'retry-started.json').exists())
        self.assertFalse((run/'publication').exists())

    def test_missing_tool_stops_before_fetch(self):
        class Missing(Fake):
            def rpc(self, method, params):
                if method == 'tools/list':
                    return {'result': {'tools': []}}
                return super().rpc(method, params)
        fake = Missing()
        self.assertEqual('TOOL_UNAVAILABLE', self.run_fake(fake)['errorCode'])
        self.assertFalse(any(m == 'tools/call' for m, _ in fake.calls))

    def test_multiple_anchors_rejected(self):
        self.project['douyinIds'].append('99999999')
        self.make_plan(self.profile['metrics'])
        with self.assertRaises(c.Rejected):
            self.run_fake()

    def test_multipage_drift_stops(self):
        def mutate(raw, args):
            info = raw['result']['structuredContent']['data']['page_info']
            info['page'] = args['page']
            info['total_page'] = 2
            info['total_number'] = 2 if args['page'] == 1 else 3
        self.assertEqual('PAGE_REJECTED', self.run_fake(Fake(mutate))['errorCode'])

    def test_exact_metric_decimal_text(self):
        def mutate(raw, args):
            metrics = raw['result']['structuredContent']['data']['rows'][0]['metrics']
            metrics[c.SORT]['Value'] = Decimal('12.345678901234567890123456789')
            for key, value in [('video_like_count_for_roi2', Decimal('1e-07')),
                               ('video_follow_count_for_roi2', 123456789012345678901234567890),
                               ('video_avg_watch_duration_for_roi2', None)]:
                if key in metrics:
                    metrics[key]['Value'] = value
        result = self.run_fake(Fake(mutate))
        self.assertEqual('COLLECTED_NOT_IMPORTED', result['status'])
        material = c.decode((Path(result['receipt']).parent / 'publication/material_day.json').read_bytes())
        row = material['rows'][0]
        self.assertEqual('12.345678901234567890123456789', row[c.SORT])
        self.assertEqual('1E-7', row['video_like_count_for_roi2'])
        self.assertEqual('123456789012345678901234567890', row['video_follow_count_for_roi2'])
        self.assertIsNone(row['video_avg_watch_duration_for_roi2'])
        self.assertEqual('SYNTHETIC', c.decode(Path(result['receipt']).read_bytes())['transportEvidence'])

    def test_numeric_id_rejected(self):
        for value in (123, Decimal('123.0'), 1.2, None, ''):
            def mutate(raw, args):
                raw['result']['structuredContent']['data']['rows'][0]['dimensions']['material_id']['Value'] = value
            with self.subTest(value=value):
                self.assertEqual('ROW_REJECTED', self.run_fake(Fake(mutate))['errorCode'])

    def test_http_bytes_independent_evidence(self):
        client = c.HttpTransport('synthetic-secret-value')
        bodies = []
        fake = Fake()
        def open_response(req, timeout):
            payload = c.decode(req.data)
            if payload['method'] == 'notifications/initialized':
                return io.BytesIO(b'')
            response = fake.rpc(payload['method'], payload['params'])
            response.update(jsonrpc='2.0', id=payload['id'])
            body = json.dumps(response, indent=3).encode()
            if payload['method'] == 'tools/call':
                body = body.replace(b'"Value": 0', b'"Value": 0.12345678901234567890123456789')
                bodies.append(body)
            return io.BytesIO(body)
        with patch.object(client.opener, 'open', side_effect=open_response):
            result = self.run_fake(client)
        self.assertEqual('COLLECTED_NOT_IMPORTED', result['status'])
        receipt = c.decode(Path(result['receipt']).read_bytes())
        self.assertEqual('HTTP_BYTES', receipt['transportEvidence'])
        observed = [Path(item['absolutePath']).read_bytes() for item in receipt['rawEvidence']]
        self.assertEqual({c.sha(b) for b in bodies}, {c.sha(b) for b in observed})
        material = c.decode((Path(result['receipt']).parent / 'publication/material_day.json').read_bytes())
        self.assertEqual('0.12345678901234567890123456789', material['rows'][0][c.SORT])


    def pending_plan(self, deleted=False):
        self.project.update(publicDouyinId='public.account', douyinIds=[])
        self.make_plan(self.profile['metrics'])
        if deleted:
            config = c.decode(Path(self.plan['configurationEvidence']['absolutePath']).read_bytes())
            config['projects'][0]['deleted'] = True
            data = c.encoded(config)
            revision = c.sha(data)
            path = self.root / '.local/config/revisions' / (revision + '.json')
            path.write_bytes(data)
            self.plan['configurationEvidence'] = c.evidence(path)
            self.plan['revision'] = revision
            self.plan['acquisitionContract']['configurationRevision'] = revision
            self.plan['projects'] = []
            self.plan['enabledProjectCount'] = 0
            self.save_plan()

    def test_pending_dry_run_offline(self):
        self.pending_plan()
        with patch.object(c, 'HttpTransport', side_effect=AssertionError()):
            self.assertEqual('READY_NOT_EXECUTED', c.execute(self.root, self.path, 'synthetic')['status'])

    def test_pending_plan_anchor_injection_rejected(self):
        self.pending_plan()
        self.plan['projects'][0]['douyinIds'] = ['99999999']
        self.save_plan()
        with self.assertRaises(c.Rejected):
            self.run_fake()

    def test_deleted_project_cannot_collect(self):
        self.pending_plan(deleted=True)
        with self.assertRaises(c.Rejected):
            self.run_fake()

    def test_pending_anchor_resolution_success(self):
        self.pending_plan()
        fake = AnchorFake()
        result = self.run_fake(fake)
        self.assertEqual('COLLECTED_NOT_IMPORTED', result['status'])
        report_calls = [p for m, p in fake.calls if m == 'tools/call' and p['name'] == c.TOOL]
        self.assertEqual(5, len(report_calls))
        self.assertTrue(all(p['arguments']['filters'][0]['values'] == ['98765432'] for p in report_calls))
        receipt = c.decode(Path(result['receipt']).read_bytes())
        self.assertEqual('EXACT_UNIQUE_MATCH', receipt['anchorResolution']['status'])
        self.assertEqual('98765432', receipt['anchorResolution']['resolvedAwemeId'])
        self.assertEqual(self.plan['revision'], receipt['anchorResolution']['configurationRevision'])
        self.assertEqual(1, len(receipt['anchorResolution']['evidence']))
        publication = Path(result['receipt']).parent / 'publication'
        self.assertTrue((publication / 'manifest.json').is_file())
        self.assertEqual(1, len(c.decode((publication / 'material_day.json').read_bytes())['rows']))

    def test_pending_anchor_rejections_stop_before_reports(self):
        self.pending_plan()
        for mode, error in [('not-found', 'ANCHOR_NOT_FOUND'), ('ambiguous', 'ANCHOR_AMBIGUOUS'),
                            ('bad-page', 'ANCHOR_PAGE_INVALID'), ('bad-id', 'ANCHOR_PAGE_INVALID'),
                            ('duplicate', 'ANCHOR_PAGE_INVALID'), ('provider', 'ANCHOR_PROVIDER_REJECTED')]:
            fake = AnchorFake(mode)
            result = self.run_fake(fake)
            self.assertEqual(error, result['errorCode'], mode)
            self.assertFalse(any(m == 'tools/call' and p['name'] == c.TOOL for m, p in fake.calls))
            self.assertFalse((Path(result['receipt']).parent / 'publication').exists())

    def test_pending_anchor_multipage_exact_later(self):
        self.pending_plan()
        fake = AnchorFake('multipage')
        self.assertEqual('COLLECTED_NOT_IMPORTED', self.run_fake(fake)['status'])
        self.assertEqual(2, sum(m == 'tools/call' and p['name'] == c.ANCHOR_TOOL for m, p in fake.calls))

    def test_pending_anchor_pagination_drift(self):
        self.pending_plan()
        self.assertEqual('ANCHOR_PAGE_INVALID', self.run_fake(AnchorFake('drift'))['errorCode'])

    def test_pending_text_content_response(self):
        self.pending_plan()
        class TextAnchor(AnchorFake):
            def rpc(self, method, params):
                result = super().rpc(method, params)
                if method == 'tools/call' and params['name'] == c.ANCHOR_TOOL:
                    return {'result': {'content': [{'type': 'text', 'text': json.dumps(result['result']['structuredContent'])}]}}
                return result
        self.assertEqual('COLLECTED_NOT_IMPORTED', self.run_fake(TextAnchor())['status'])

    def test_public_change_never_reuses_old_anchor(self):
        self.project.update(publicDouyinId='public.account', douyinIds=['11111111'])
        self.make_plan(self.profile['metrics'])
        self.plan['projects'][0]['douyinIds'] = []
        self.save_plan()
        fake = AnchorFake()
        self.assertEqual('COLLECTED_NOT_IMPORTED', self.run_fake(fake)['status'])
        self.assertTrue(any(m == 'tools/call' and p['name'] == c.ANCHOR_TOOL for m, p in fake.calls))
        self.assertTrue(all(p['arguments']['filters'][0]['values'] == ['98765432'] for m, p in fake.calls
                            if m == 'tools/call' and p['name'] == c.TOOL))


class AnchorFake(Fake):
    def __init__(self, mode='success'):
        super().__init__()
        self.mode = mode

    def rpc(self, method, params):
        if method == 'tools/list':
            return {'result': {'tools': [{'name': c.TOOL}, {'name': c.ANCHOR_TOOL}]}}
        if method != 'tools/call' or params['name'] != c.ANCHOR_TOOL:
            return super().rpc(method, params)
        self.calls.append((method, params))
        args = params['arguments']
        assert args['advertiser_id'] == '12345678'
        assert args['filtering']['search_key_words'] == 'public.account'
        rows = [{'aweme_id': '98765432', 'aweme_show_id': 'public.account', 'aweme_name': 'Synthetic'}]
        total, pages = 1, 1
        if self.mode == 'not-found':
            rows[0]['aweme_show_id'] = 'other'
        if self.mode in ('ambiguous', 'duplicate'):
            rows.append(dict(rows[0], aweme_id='98765433' if self.mode == 'ambiguous' else '98765432'))
            total = 2
        if self.mode == 'bad-id':
            rows[0]['aweme_id'] = Decimal('98765432.0')
        if self.mode in ('multipage', 'drift'):
            total, pages = 101, 2
            if args['page'] == 1:
                rows = [{'aweme_id': str(10000000+i), 'aweme_show_id': 'other'+str(i), 'aweme_name': 'Synthetic'} for i in range(100)]
            elif self.mode == 'drift':
                total = 102
        business = {'code': 500 if self.mode == 'provider' else 0, 'data': {'aweme_id_list': rows,
                    'page_info': {'page': args['page'], 'page_size': 200 if self.mode == 'bad-page' else 100,
                                  'total_page': pages, 'total_number': total}}}
        return {'result': {'structuredContent': business}}


class TransportTest(unittest.TestCase):
    def call(self, change=None, method='tools/call', body_override=None):
        client = c.HttpTransport('synthetic-secret-value')
        def open_response(req, timeout):
            payload = c.decode(req.data)
            response = {'jsonrpc': '2.0', 'id': payload.get('id'), 'result': {}}
            if change:
                change(response)
            body = body_override if body_override is not None else c.encoded(response)
            return io.BytesIO(body)
        with patch.object(client.opener, 'open', side_effect=open_response):
            return client.rpc(method, {'name': c.TOOL} if method == 'tools/call' else {})

    def test_valid_envelope(self):
        self.assertEqual('2.0', self.call()['jsonrpc'])

    def test_wrong_id(self):
        with self.assertRaisesRegex(c.Rejected, '^PROTOCOL_REJECTED$'):
            self.call(lambda r: r.update(id='other-request'))

    def test_missing_id(self):
        with self.assertRaisesRegex(c.Rejected, '^PROTOCOL_REJECTED$'):
            self.call(lambda r: r.pop('id'))

    def test_wrong_protocol(self):
        with self.assertRaisesRegex(c.Rejected, '^PROTOCOL_REJECTED$'):
            self.call(lambda r: r.update(jsonrpc='1.0'))

    def test_rpc_error(self):
        with self.assertRaisesRegex(c.Rejected, '^PROTOCOL_REJECTED$'):
            self.call(lambda r: r.update(error={'message': 'untrusted'}))

    def test_tool_error(self):
        with self.assertRaisesRegex(c.Rejected, '^PROTOCOL_REJECTED$'):
            self.call(lambda r: r['result'].update(isError=True))

    def test_credential_echo_rejected(self):
        with self.assertRaisesRegex(c.Rejected, '^PROTOCOL_REJECTED$'):
            self.call(lambda r: r['result'].update(message='synthetic-secret-value'))

    def test_notification_empty(self):
        self.assertEqual({}, self.call(method='notifications/initialized', body_override=b''))

    def test_empty_request_response_rejected(self):
        with self.assertRaises(c.Rejected):
            self.call(body_override=b'')

    def test_sse_not_silent_success(self):
        with self.assertRaises(c.Rejected):
            self.call(body_override=b'data: {"jsonrpc":"2.0"}\n\n')

    def test_json_escaped_credential_echo_rejected(self):
        with self.assertRaisesRegex(c.Rejected, '^PROTOCOL_REJECTED$'):
            self.call(body_override=b'{"jsonrpc":"2.0","id":"wrong","result":{"message":"synthetic-\\u0073ecret-value"}}')


if __name__ == '__main__':
    unittest.main()
