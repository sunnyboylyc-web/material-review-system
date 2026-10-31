"""Other-system integration example using the official MCP client, never a direct dashboard API."""
import argparse
import asyncio
import json
import httpx2
from pathlib import Path
from urllib.parse import urlsplit, parse_qsl
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client


async def probe(endpoint,project=None,current_start=None,current_end=None,previous_start=None,previous_end=None,token_file=None):
    u=urlsplit(endpoint)
    if u.username or u.password or u.fragment or (u.scheme!='https' and not(u.scheme=='http' and u.hostname in {'127.0.0.1','localhost','::1'})):raise ValueError('MCP_ENDPOINT_REQUIRES_HTTPS_OR_LOOPBACK')
    params=parse_qsl(u.query,keep_blank_values=True)
    if u.query and (len(params)!=1 or params[0][0]!='API_KEY' or not params[0][1]):raise ValueError('MCP_ENDPOINT_QUERY_REQUIRES_SINGLE_API_KEY')
    headers={'Authorization':'Bearer '+Path(token_file).read_text(encoding='ascii').strip()} if token_file else {}
    async with httpx2.AsyncClient(headers=headers,timeout=300,follow_redirects=False) as http_client,streamable_http_client(endpoint,http_client=http_client,max_sse_event_size=4*1024**2) as (read_stream,write_stream):
        async with ClientSession(read_stream,write_stream,read_timeout_seconds=300) as session:
            initialized=await session.initialize()
            listed=await session.list_tools()
            names={tool.name for tool in listed.tools}
            expected={'get_data_guide','list_projects','get_period_review','query_materials','get_material_trend','get_daily_top','get_project_freshness'}
            if names!=expected:raise AssertionError('TOOL_SET_MISMATCH')
            for tool in listed.tools:
                hints=tool.annotations.model_dump(by_alias=True)
                assert hints['readOnlyHint'] is True and hints['destructiveHint'] is False
            async def call(name,args=None):
                result=await session.call_tool(name,args or {})
                if result.is_error:raise RuntimeError('MCP_TOOL_FAILED: '+name)
                return result.structured_content
            guide=await call('get_data_guide');projects=await call('list_projects')
            summary={'status':'MCP_SYSTEM_CLIENT_CONNECTED','protocolVersion':initialized.protocol_version,'tools':sorted(names),
                     'projectCount':len(projects['projects']),'dataMode':projects['dataMode'],'authentication':'url-key' if u.query else ('bearer' if token_file else 'none'),'dashboardApiAccessFromClient':False}
            if project:
                query={'project_id':project,'current_start':current_start,'current_end':current_end,'previous_start':previous_start,'previous_end':previous_end}
                review=await call('get_period_review',{'query':query});rid=review['reportId']
                page=await call('query_materials',{'query':{'report_id':rid,'page_size':10}})
                assert page['snapshotFingerprint']==review['snapshotFingerprint']
                summary.update(status='MCP_REVIEW_AND_DETAILS_VERIFIED',reportId=rid,totalCandidates=page['totalCandidates'],rowsReturned=len(page['rows']),
                    warnings=review['warnings'],fourDatesReturned=review['query'],snapshotFingerprintConsistent=True)
                if page['rows']:
                    trend=await call('get_material_trend',{'report_id':rid,'material_id':page['rows'][0]['materialId']})
                    assert trend['snapshotFingerprint']==review['snapshotFingerprint'];summary['dailySpendPoints']=len(trend['material'].get('dailySpend',[]))
            print(json.dumps(summary,ensure_ascii=False))
            return summary


def main():
    p=argparse.ArgumentParser();p.add_argument('--endpoint',default='http://127.0.0.1:24573/mcp');p.add_argument('--demo',action='store_true')
    p.add_argument('--token-file',help='Local restricted Bearer token file; do not pass a token literal.')
    p.add_argument('--url-file',help='Restricted text file containing the complete URL; keep secret query values out of command lines.')
    args=p.parse_args()
    endpoint=Path(args.url_file).read_text(encoding='utf-8').strip() if args.url_file else args.endpoint
    # SDK transport diagnostics may include request URLs; this example prints only safe summaries.
    import logging
    logging.disable(logging.CRITICAL)
    try:
        if args.demo:asyncio.run(probe(endpoint,'demo-project','2026-01-01','2026-01-07','2025-12-25','2025-12-31',args.token_file))
        else:asyncio.run(probe(endpoint,token_file=args.token_file))
    except Exception:
        print(json.dumps({'status':'MCP_CONNECTION_OR_CALL_FAILED','detailsSuppressedToProtectCredentials':True}));raise SystemExit(1)


if __name__=='__main__':main()
