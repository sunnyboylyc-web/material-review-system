"""Opt-in official-client integration against the pure in-memory Java calculator demo."""
import json
import os
import unittest
from urllib.request import Request,urlopen
from urllib.error import HTTPError

from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client

ENDPOINT=os.environ.get('MCP_TEST_ENDPOINT')


@unittest.skipUnless(ENDPOINT,'Set MCP_TEST_ENDPOINT to the synthetic demo MCP, never a production endpoint.')
class Protocol(unittest.IsolatedAsyncioTestCase):
    async def test_readonly_dashboard_workflow_with_real_calculator(self):
        async with streamable_http_client(ENDPOINT) as (r,w):
            async with ClientSession(r,w,read_timeout_seconds=60) as s:
                initialized=await s.initialize();self.assertEqual('2025-11-25',initialized.protocol_version)
                listed=await s.list_tools();self.assertEqual(7,len(listed.tools))
                for tool in listed.tools:
                    self.assertTrue(tool.annotations.read_only_hint);self.assertFalse(tool.annotations.destructive_hint)
                    self.assertIsNotNone(tool.output_schema)
                async def call(name,args=None):
                    result=await s.call_tool(name,args or {});self.assertFalse(result.is_error,result.content);self.assertIsInstance(result.structured_content,dict)
                    return result.structured_content
                guide=await call('get_data_guide');self.assertEqual('none',guide['connection']['authentication'])
                resources=await s.list_resources();self.assertEqual('dashboard://guide',str(resources.resources[0].uri))
                await s.read_resource('dashboard://guide')
                projects=await call('list_projects');self.assertEqual('SYNTHETIC',projects['dataMode'])
                q={'project_id':'demo-project','current_start':'2026-01-01','current_end':'2026-01-07','previous_start':'2025-12-25','previous_end':'2025-12-31'}
                review=await call('get_period_review',{'query':q});rid=review['reportId'];self.assertEqual('SYNTHETIC',review['dataMode'])
                self.assertIn('composition',review['boards']);self.assertIn('ai',review['boards'])
                self.assertGreater(review['availableGroups']['overall'],200)
                page=await call('query_materials',{'query':{'report_id':rid,'search':'SEARCH225','page_size':10}})
                self.assertEqual(1,page['totalMatched']);self.assertGreater(page['rows'][0]['rank'],200)
                a=await call('query_materials',{'query':{'report_id':rid,'page_size':10}})
                b=await call('query_materials',{'query':{'report_id':rid,'page_size':10,'offset':a['nextOffset']}})
                self.assertFalse({v['materialId'] for v in a['rows']}&{v['materialId'] for v in b['rows']})
                self.assertEqual(review['snapshotFingerprint'],b['snapshotFingerprint'])
                ai=await call('query_materials',{'query':{'report_id':rid,'group':'business','filters':[{'field':'cpc','operator':'gt','value':0}]}})
                self.assertGreater(ai['totalMatched'],0)
                trend=await call('get_material_trend',{'report_id':rid,'material_id':'SEARCH225'});self.assertEqual(7,len(trend['material']['dailySpend']))
                bad=await s.call_tool('query_materials',{'query':{'report_id':rid,'filters':[{'field':'cpc','operator':'gt','value':0}]}})
                self.assertTrue(bad.is_error)
                bad=await s.call_tool('get_period_review',{'query':{**q,'current_start':'2026-02-30'}});self.assertTrue(bad.is_error)
                expanded=await call('get_period_review',{'query':{**q,'current_test_start':'2025-01-01','current_test_end':'2026-01-07'}})
                self.assertEqual(review['boards']['periods'],expanded['boards']['periods'])
                daily=await call('get_daily_top',{'project_id':'demo-project','date':'2026-01-07'})
                self.assertEqual(10,len(daily['boards']['rows']));self.assertIn('DAILY_SEARCH_IS_LIMITED_TO_UPSTREAM_TOP_200',daily['warnings'])
                latest=await call('get_project_freshness',{'project_id':'demo-project'});self.assertEqual('2026-01-15',latest['latestMcpStatDate'])

    def test_untrusted_browser_origin_is_rejected_without_any_key(self):
        request=Request(ENDPOINT,data=json.dumps({'jsonrpc':'2.0','id':1,'method':'tools/list'}).encode(),
            headers={'Content-Type':'application/json','Accept':'application/json, text/event-stream','Origin':'http://untrusted.example'},method='POST')
        with self.assertRaises(HTTPError) as cm:urlopen(request,timeout=5)
        self.assertEqual(403,cm.exception.code)


if __name__=='__main__':unittest.main()
