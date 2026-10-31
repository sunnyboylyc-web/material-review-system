import asyncio
from copy import deepcopy
import json
from pathlib import Path
import threading
import unittest
from http.server import BaseHTTPRequestHandler,HTTPServer
from pydantic import ValidationError

from tools.mcp_api.dashboard import DashboardSource,DashboardService,Reports,PeriodQuery,ColumnFilter,QueryError,composition
from tools.mcp_api.server import MaterialQuery,build_server,transport_security


def metric(n,status='VALID'):return {'status':status,'value':n,'partial':False,'partialReasons':[]}
def row(i):return {'rank':i,'materialId':str(i),'materialName':'虚构原创AI商单'+str(i),'currentSpend':metric(1000-i),'previousSpend':metric(20),
                      'roi':metric(2 if i!=2 else None,'VALID' if i!=2 else 'UNKNOWN_INPUT'),'ctr':metric(.03),'cvr':metric(.01),'cpm':metric(30),
                      'spendShare':metric(.01),'creationDate':{'status':'VERIFIED','date':'2026-01-01'},'dailySpend':[{'date':'2026-01-01','spend':metric(20)}]}
def query(**kw):return PeriodQuery(current_start='2026-01-01',current_end='2026-01-07',previous_start='2025-12-25',previous_end='2025-12-31',project_id='demo',**kw)


class FakeSource:
    synthetic=False
    def __init__(self):self.calls=[];self.version_changes=False;self.wrong_project=False;self.wrong_dates=False;self.partial=False
    def get(self,path,params=None):
        self.calls.append((path,params))
        if path=='/api/projects':return [{'id':'demo','name':'虚构项目','enabled':True}]
        if path.endswith('/freshness'):return {'schemaVersion':'freshness-1.0.0','projectId':'demo','latestMcpStatDate':'2026-01-07'}
        common={'projectId':'wrong' if self.wrong_project else 'demo','snapshotFingerprint':'changed' if self.version_changes and path.endswith('/material-types') else 'same','evidenceKind':'CURRENT_FACTS'}
        def periods(tests=False):
            result=[]
            for k,p in [('CURRENT','current'),('PREVIOUS','previous')]:
                r={'key':k,'range':{'start':params[p+'Start'],'end':params[p+'End']},'groups':[],
                   'coverage':{'state':'PARTIAL' if self.partial else 'COMPLETE','missingDates':['2026-01-02'] if self.partial else []},'pendingMaterialCount':0}
                if tests:r['testRange']={'start':params[p+'TestStart'],'end':params[p+'TestEnd']}
                if self.wrong_dates:r['range']['end']='2026-02-01'
                result.append(r)
            return result
        rows=[row(i) for i in range(1,231)]
        if path.endswith('/review-materials'):return {**common,'schemaVersion':'review-materials-1.0.0','current':{'start':params['currentStart'],'end':params['currentEnd']},'previous':{'start':params['previousStart'],'end':params['previousEnd']},'rows':rows}
        if path.endswith('/review'):return {**common,'schemaVersion':'dashboard-query-1.7.0','periods':periods(),'comparisons':[],'metricsState':'VALID','topMaterials':rows[:params['limit']],'cohortMaterials':{'new':rows,'historical':[],'aigc':[]}}
        if path.endswith('/material-types'):
            typed=[{**row(1),'metrics':{'spend':metric(999),'roi':metric(2),'cpc':metric(1),'ctr':metric(.03),'cvr':metric(.01),'cpm':metric(30),'spendShare':metric(.1)}}]
            return {**common,'schemaVersion':'material-type-analysis-1.0.0','analysis':{'schemaVersion':'material-type-review-2.0.0','periods':periods(True),'comparisons':[],'materials':{'original':typed,'mixed':[],'other':[]},'ai':{'periods':periods(True),'comparisons':[],'materials':{'business':typed,'department':[]}}}}
        if path.endswith('/top'):return {**common,'schemaVersion':'dashboard-query-1.7.0','selectedDate':params['date'],'coverage':{'state':'COMPLETE'},'rows':[{**x,'dayRoi':metric(2)} for x in rows[:200]]}
        raise AssertionError('Unexpected upstream route')


class ReadOnlyQueries(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):self.source=FakeSource();self.service=DashboardService(self.source)
    async def report(self):return await self.service.period(query())
    async def test_projects_do_not_expose_source_configuration(self):
        data=await self.service.projects();self.assertEqual('demo',data['projects'][0]['id']);self.assertNotIn('accountId',str(data))
    async def test_default_test_ranges_and_frozen_report(self):
        r=await self.report();self.assertEqual('2026-01-01',r['query']['current_test_start']);self.assertEqual(230,r['availableGroups']['overall'])
        calls=len(self.source.calls);self.service.materials(r['reportId']);self.service.trend(r['reportId'],'1');self.assertEqual(calls,len(self.source.calls))
    async def test_filters_full_list_before_pagination_beyond_rank200(self):
        r=await self.report();result=self.service.materials(r['reportId'],search='230',page_size=10)
        self.assertEqual(230,result['rows'][0]['rank']);self.assertEqual(1,result['totalMatched']);self.assertEqual(230,result['totalCandidates'])
    async def test_independent_test_ranges_do_not_change_main_period(self):
        q=query(current_test_start='2025-01-01',current_test_end='2026-01-07');r=await self.service.period(q)
        self.assertEqual('2026-01-01',r['boards']['periods'][0]['range']['start']);self.assertEqual('2025-01-01',r['boards']['materialTypes']['periods'][0]['testRange']['start'])
    async def test_version_mismatch_retries_whole_read_then_fails(self):
        self.source.version_changes=True
        with self.assertRaisesRegex(QueryError,'DATA_CHANGED_DURING_QUERY_RETRY'):await self.report()
        self.assertEqual(6,len(self.source.calls));self.assertFalse(self.service.store.items)
    async def test_project_mismatch_fails_without_report(self):
        self.source.wrong_project=True
        with self.assertRaisesRegex(QueryError,'IDENTITY'):await self.report()
        self.assertFalse(self.service.store.items)
    async def test_date_mismatch_rejected(self):
        self.source.wrong_dates=True
        with self.assertRaisesRegex(QueryError,'DATE_RANGE'):await self.report()
    async def test_unknown_is_not_zero_for_numeric_comparison(self):
        r=await self.report();rows=self.service.materials(r['reportId'],filters=[ColumnFilter(field='roi',operator='eq',value=0)])
        self.assertEqual(0,rows['totalMatched'])
        rows=self.service.materials(r['reportId'],filters=[ColumnFilter(field='roi',operator='status',statuses=['UNKNOWN_INPUT'])]);self.assertEqual(1,rows['totalMatched'])
    async def test_percent_filters_are_percentage_points(self):
        r=await self.report();result=self.service.materials(r['reportId'],filters=[ColumnFilter(field='ctr',operator='eq',value=3)])
        self.assertEqual(230,result['totalMatched']);self.assertEqual(.03,result['rows'][0]['ctr']['value'])
    async def test_missing_cpc_is_not_fabricated_for_overall(self):
        r=await self.report()
        with self.assertRaisesRegex(QueryError,'UNAVAILABLE'):self.service.materials(r['reportId'],filters=[ColumnFilter(field='cpc',operator='gt',value=0)])
        self.assertEqual(1,self.service.materials(r['reportId'],'original',filters=[ColumnFilter(field='cpc',operator='eq',value=1)])['totalMatched'])
    async def test_partial_coverage_is_preserved(self):
        self.source.partial=True;r=await self.report();self.assertTrue(r['warnings']);self.assertEqual(['2026-01-02'],r['boards']['periods'][0]['coverage']['missingDates'])
    async def test_query_results_do_not_mutate_cached_rows(self):
        r=await self.report();a=self.service.materials(r['reportId']);a['rows'][0]['roi']['value']=999
        self.assertEqual(2,self.service.materials(r['reportId'])['rows'][0]['roi']['value']);self.assertNotIn('dailySpend',a['rows'][0])
    async def test_daily_scope_and_date(self):
        r=await self.service.daily('demo','2026-01-07');self.assertEqual(10,len(r['boards']['rows']))
        rows=self.service.materials(r['reportId'],search='230');self.assertEqual(0,rows['totalMatched']);self.assertEqual(200,rows['totalCandidates']);self.assertIn('TOP_200',rows['candidateScope'])
    async def test_unavailable_group_not_presented_as_empty(self):
        r=await self.service.daily('demo','2026-01-07')
        with self.assertRaisesRegex(QueryError,'UNAVAILABLE'):self.service.materials(r['reportId'],'original')
    async def test_trend_only_returns_existing_daily_fields(self):
        r=await self.report();v=self.service.trend(r['reportId'],'1');self.assertEqual(['spend'],v['dailyFieldsAvailable'])


class Boundaries(unittest.TestCase):
    def test_public_host_is_explicit_and_keeps_origin_protection(self):
        s=transport_security(['mcp.example.test']);self.assertTrue(s.enable_dns_rebinding_protection)
        self.assertIn('mcp.example.test',s.allowed_hosts);self.assertIn('https://mcp.example.test',s.allowed_origins)
        self.assertNotIn('*',s.allowed_hosts);self.assertNotIn('*',s.allowed_origins)
    def test_public_host_does_not_accept_url_or_wildcard(self):
        for host in ['*','*.example.test','https://example.test','example.test/path','user@example.test','example.test:443']:
            with self.subTest(host=host),self.assertRaises(ValueError):transport_security([host])
    def test_composition_matches_page_division_and_preserves_partial(self):
        groups=[{'key':k,'metrics':{'spend':metric(v),'materialCount':metric(v)}} for k,v in [('overall',10),('new',5),('historical',2),('aigc',1)]]
        groups[1]['metrics']['spend']['partial']=True
        p=composition([{'key':'CURRENT','range':{'start':'2026-01-01','end':'2026-01-07'},'groups':groups}])[0]
        self.assertEqual(.5,p['spend']['new']['value']);self.assertTrue(p['spend']['new']['partial'])
        self.assertAlmostEqual(.2,p['spend']['pendingRemainder']['value'])
        groups[0]['metrics']['spend']=metric(0)
        self.assertEqual('ZERO_DENOMINATOR',composition([{'key':'CURRENT','range':{},'groups':groups}])[0]['spend']['new']['status'])
    def test_large_material_page_fails_explicitly(self):
        service=DashboardService(FakeSource());r=row(1);r['materialName']='x'*(2*1024**2)
        key=service.record('period','demo','snapshot',{}, {'overall':[r]}, {}, [])
        with self.assertRaisesRegex(QueryError,'PAGE_TOO_LARGE'):service.materials(key)
    def test_unexpected_schema_rejected(self):
        service=DashboardService(FakeSource())
        with self.assertRaisesRegex(QueryError,'SCHEMA_UNSUPPORTED'):service.validate({'projectId':'demo','evidenceKind':'CURRENT_FACTS','snapshotFingerprint':'x','schemaVersion':'wrong'},'demo','dashboard-query-1.7.0')
    def test_expiration_and_capacity(self):
        t=[0];s=Reports(ttl=5,max_count=1,max_bytes=100,clock=lambda:t[0]);a=s.put({'x':1});b=s.put({'x':2})
        with self.assertRaisesRegex(QueryError,'EXPIRED'):s.get(a)
        t[0]=6
        with self.assertRaisesRegex(QueryError,'EXPIRED'):s.get(b)
        with self.assertRaisesRegex(QueryError,'TOO_LARGE'):s.put({'x':'a'*200})
    def test_invalid_dates_ranges_and_test_pairs(self):
        for kw in [{'current_test_start':'2026-01-01'},{'current_test_start':'2026-02-30','current_test_end':'2026-03-01'}, {'current_test_start':'2026-01-07','current_test_end':'2026-01-01'}]:
            with self.subTest(kw=kw),self.assertRaises(ValidationError):query(**kw)
        with self.assertRaises(ValidationError):query(top_limit=True)
    def test_invalid_filter_operand_and_page_types(self):
        for v in [float('nan'),float('inf'),True,'text']:
            with self.subTest(v=v),self.assertRaises(ValidationError):ColumnFilter(field='roi',operator='gt',value=v)
        with self.assertRaises(ValidationError):MaterialQuery(report_id='a'*32,page_size=True)
    def test_source_url_no_credentials_arbitrary_http_or_query(self):
        for url in ['http://example.com','https://user:pass@example.com','https://example.com/?token=x','http://127.0.0.1:1/../x']:
            with self.subTest(url=url),self.assertRaises(QueryError):DashboardSource(url)
    def test_upstream_routes_are_get_only_and_allowlisted(self):
        s=DashboardSource('http://127.0.0.1:1')
        for path in ['/api/files','/api/operations/configuration','/api/projects/../files','/api/projects/demo/review/refresh']:
            with self.subTest(path=path),self.assertRaisesRegex(QueryError,'READ_ONLY'):s.get(path)
    def test_redirect_and_html_are_rejected(self):
        class Handler(BaseHTTPRequestHandler):
            mode='redirect'
            def log_message(self,*args):pass
            def do_GET(self):
                if self.mode=='redirect':self.send_response(302);self.send_header('Location','/secret')
                else:self.send_response(200);self.send_header('Content-Type','text/html')
                self.end_headers();self.wfile.write(b'not dashboard JSON')
        h=HTTPServer(('127.0.0.1',0),Handler);t=threading.Thread(target=h.serve_forever,daemon=True);t.start()
        try:
            s=DashboardSource('http://127.0.0.1:'+str(h.server_port))
            with self.assertRaisesRegex(QueryError,'REDIRECT'):s.get('/api/projects')
            Handler.mode='html'
            with self.assertRaisesRegex(QueryError,'NOT_JSON'):s.get('/api/projects')
        finally:h.shutdown();h.server_close();t.join()


class DiscoveryMetadata(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.server=build_server(DashboardService(FakeSource()),'bearer')
        self.tools={t.name:t.model_dump(by_alias=True,exclude_none=True) for t in await self.server.list_tools()}

    async def test_discovery_snapshot_matches_server_and_documents_every_parameter(self):
        p=Path(__file__).resolve().parents[2]/'docs/mcp/tools-schema.json'
        snapshot=json.loads(p.read_text(encoding='utf-8'))
        self.assertEqual(self.tools,{t['name']:t for t in snapshot['tools']})
        def check(schema):
            for name,value in schema.get('properties',{}).items():
                self.assertTrue(value.get('description') or value.get('$ref'),name)
            for value in schema.get('$defs',{}).values():check(value)
        for t in self.tools.values():
            self.assertTrue(t['description']);check(t['inputSchema'])
            self.assertTrue(t['annotations']['readOnlyHint']);self.assertFalse(t['annotations']['destructiveHint'])

    async def test_connector_checks_examples_and_four_required_dates(self):
        from jsonschema import Draft202012Validator,FormatChecker
        schemas={k:v['inputSchema'] for k,v in self.tools.items()}
        examples=json.loads((Path(__file__).resolve().parents[2]/'docs/mcp/synthetic-calls.json').read_text(encoding='utf-8'))
        for item in examples['calls']:
            call=item['request']['params'];Draft202012Validator(schemas[call['name']],format_checker=FormatChecker()).validate(call['arguments'])
        s=schemas['get_period_review'];q={'project_id':'demo-project','current_start':'2026-01-01','current_end':'2026-01-07','previous_start':'2025-12-25','previous_end':'2025-12-31'}
        validator=Draft202012Validator(s,format_checker=FormatChecker())
        for field in ('project_id','current_start','current_end','previous_start','previous_end'):
            missing={k:v for k,v in q.items() if k!=field};self.assertTrue(list(validator.iter_errors({'query':missing})),field)
        for bad in ('2026/01/01','2026-02-30'):
            self.assertTrue(list(validator.iter_errors({'query':{**q,'current_start':bad}})))
        daily=Draft202012Validator(schemas['get_daily_top'],format_checker=FormatChecker())
        self.assertTrue(list(daily.iter_errors({'project_id':'demo','date':'2026-01-01','limit':201})))

    async def test_cross_field_constraints_still_enforced_and_valid_queries_unchanged(self):
        q=query();self.assertEqual(q.current_start,q.current_test_start)
        q.current_end=q.current_start;self.assertEqual(q.current_start,q.ranges().current_end)
        for change in ({'current_end':'2025-12-31'},{'current_test_start':'2025-01-01'}):
            with self.assertRaises(ValidationError):PeriodQuery(**{**query().model_dump(),**change,**({'current_test_end':None} if 'current_test_start' in change else {})})
        guide=(await self.server.call_tool('get_data_guide',{})).structured_content
        self.assertEqual('bearer',guide['connection']['authentication']);self.assertTrue(guide['dateConstraints']['inclusiveEnd'])
        self.assertIn('Uniform application error envelope',guide['errorHandling']['notImplemented'][1])


class SourceServiceIdentity(unittest.TestCase):
    def test_internal_key_is_get_only_not_external_bearer_and_401_never_falls_back(self):
        internal='test-service-'+'q'*40;seen=[]
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_GET(self):
                seen.append((self.path,self.headers.get('X-Material-Read-Key'),self.headers.get('Authorization'),self.headers.get('Cookie')))
                if self.headers.get('X-Material-Read-Key')!=internal:
                    self.send_response(401);self.end_headers();return
                self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(b'[]')
        h=HTTPServer(('127.0.0.1',0),Handler);t=threading.Thread(target=h.serve_forever,daemon=True);t.start()
        try:
            url='http://127.0.0.1:'+str(h.server_port)
            source=DashboardSource(url,service_token=internal);self.assertEqual([],source.get('/api/projects'))
            self.assertEqual([('/api/projects',internal,None,None)],seen)
            with self.assertRaisesRegex(QueryError,'AUTHENTICATION_FAILED'):
                DashboardSource(url,service_token='wrong-service-'+'z'*40).get('/api/projects')
            self.assertEqual(2,len(seen))
            with self.assertRaisesRegex(QueryError,'READ_ONLY'):source.get('/api/operations/configuration')
            self.assertEqual(2,len(seen))
        finally:h.shutdown();h.server_close();t.join()
    def test_malformed_internal_credential_fails_without_network(self):
        for bad in ('short','header\ninjection',' ' * 40):
            with self.assertRaisesRegex(QueryError,'CREDENTIAL_INVALID'):DashboardSource('http://127.0.0.1:1',service_token=bad)


if __name__=='__main__':unittest.main()
