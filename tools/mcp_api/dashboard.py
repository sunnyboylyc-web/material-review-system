"""Bounded, GET-only adapter. Never opens SQL, user state or collector configuration."""
from __future__ import annotations

import asyncio
from collections import OrderedDict
from copy import deepcopy
from datetime import date, datetime, timedelta, timezone
import json
import math
import re
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import build_opener, HTTPRedirectHandler, ProxyHandler, Request
import uuid

from pydantic import BaseModel, ConfigDict, Field, model_validator
from typing import Literal, Annotated

# JSON Schema can validate shape; real dates and cross-field ordering remain server-validated.
CalendarDate=Annotated[str,Field(pattern=r'^\d{4}-\d{2}-\d{2}$',json_schema_extra={'format':'date'},description='YYYY-MM-DD真实自然日，年份1900—2200，北京时间Asia/Shanghai，起止当日均包含。')]
ProjectId=Annotated[str,Field(pattern=r'^[A-Za-z0-9_-]{1,100}$',description='先调用list_projects，从projects[].id原样取得；不是广告账户ID、抖音号或项目显示名称。')]
ReportId=Annotated[str,Field(pattern=r'^[a-f0-9]{32}$',description='get_period_review或get_daily_top返回的reportId；同一报告后续查询使用同值，最长900秒，可能提前淘汰，失效重新获取报告并从首页开始。')]

GROUPS=('overall','new','historical','aigc','original','mixed','other','business','department')
BASE_FIELDS={'currentSpend','previousSpend','roi','ctr','cvr','cpm','creationDate','spendShare'}
TYPE_FIELDS=BASE_FIELDS|{'cpc'}
DAILY_FIELDS={'dayRoi','annualRoi','windowRoi','recent3Orders','recent3Spend','recent3Cpc','recent3Cpm',
              'recent3Roi','lifetimeSpend','lifetimeRoi','windowSpend','recent3Impressions',
              'recent3Clicks','recent3Ctr','recent3Cvr','creationDate'}
PERCENT_FIELDS={'ctr','cvr','spendShare','recent3Ctr','recent3Cvr'}
MetricField=Literal['currentSpend','previousSpend','roi','ctr','cvr','cpm','cpc','creationDate','spendShare',
    'dayRoi','annualRoi','windowRoi','recent3Orders','recent3Spend','recent3Cpc','recent3Cpm','recent3Roi',
    'lifetimeSpend','lifetimeRoi','windowSpend','recent3Impressions','recent3Clicks','recent3Ctr','recent3Cvr']
Number=float|str


class QueryError(ValueError):
    """Only stable non-sensitive error codes cross the MCP boundary."""


class ColumnFilter(BaseModel):
    model_config=ConfigDict(extra='forbid')
    field: MetricField=Field(description='筛选字段；各组可用字段见get_data_guide.filterFields。百分比字段筛选输入3代表3%，金额元，ROI倍数，creationDate为YYYY-MM-DD。')
    operator: Literal['eq','ne','gt','gte','lt','lte','between','in','status']=Field(description='eq/ne/gt/gte/lt/lte需value；between需value和to且value≤to；in需非空values；status需非空statuses。多个列筛选为AND。')
    value: float|str|None=Field(default=None,description='比较值，或between下界；按field采用数字或日期，不允许布尔值或非有限数字。')
    to: float|str|None=Field(default=None,description='仅between使用的闭区间上界。')
    values: list[float|str]=Field(default_factory=list,max_length=30,json_schema_extra={'default':[]},description='默认空数组；in匹配列表，使用in时至少1项、最多30项。')
    statuses: list[str]=Field(default_factory=list,max_length=10,json_schema_extra={'default':[]},description='默认空数组；status状态列表，使用status时非空，最多10项；允许VALID/UNKNOWN_INPUT/ZERO_DENOMINATOR/INVALID_INPUT/VERIFIED/INCOMPLETE/NOT_APPLICABLE/UNKNOWN。')

    @model_validator(mode='before')
    @classmethod
    def reject_boolean(cls,v):
        if isinstance(v,dict) and any(isinstance(x,bool) for x in [v.get('value'),v.get('to'),*v.get('values',[])]):
            raise ValueError('FILTER_BOOLEAN_NOT_NUMERIC')
        return v

    @model_validator(mode='after')
    def operands(self):
        if self.operator=='status':
            if not self.statuses or any(x not in {'VALID','UNKNOWN_INPUT','ZERO_DENOMINATOR','INVALID_INPUT','VERIFIED','INCOMPLETE','NOT_APPLICABLE','UNKNOWN'} for x in self.statuses):
                raise ValueError('FILTER_STATUS_INVALID')
            return self
        operands=self.values if self.operator=='in' else [self.value]+([self.to] if self.operator=='between' else [])
        if not operands:raise ValueError('FILTER_VALUE_REQUIRED')
        normalized=[]
        for v in operands:
            if self.field=='creationDate':normalized.append(iso_date(v))
            else:
                try:x=float(v)
                except (TypeError,ValueError):raise ValueError('FILTER_NUMBER_INVALID') from None
                if not math.isfinite(x):raise ValueError('FILTER_NUMBER_INVALID')
                normalized.append(x)
        if self.operator=='between' and normalized[0]>normalized[1]:raise ValueError('FILTER_RANGE_REVERSED')
        return self


class PeriodQuery(BaseModel):
    model_config=ConfigDict(extra='forbid',strict=True)
    project_id: ProjectId
    current_start: CalendarDate=Field(description='必填：本期统计开始日，含当天。与current_end组成闭区间，最多3660个自然日。')
    current_end: CalendarDate=Field(description='必填：本期统计结束日，含当天；不得早于current_start。')
    previous_start: CalendarDate=Field(description='必填：上期统计开始日，含当天。与previous_end组成闭区间，最多3660个自然日。')
    previous_end: CalendarDate=Field(description='必填：上期统计结束日，含当天；不得早于previous_start。上期/本期不强制等长、不强制先后或不重叠。')
    current_test_start: CalendarDate|None=Field(default=None,description='可选：本期素材测试开始日，必须与current_test_end成对填非空值；两者省略或均null则默认本期统计起止。仅影响05—06及AI类型统计。')
    current_test_end: CalendarDate|None=Field(default=None,description='可选：本期素材测试结束日，含当天，不早于测试开始，最长3660天；可早于或超出本期统计范围。')
    previous_test_start: CalendarDate|None=Field(default=None,description='可选：上期素材测试开始日，与previous_test_end成对；两者省略或均null默认上期统计起止。')
    previous_test_end: CalendarDate|None=Field(default=None,description='可选：上期素材测试结束日，含当天，不早于测试开始，最长3660天。不是Excel文件上传日期。')
    top_limit: int=Field(default=10,ge=1,le=200,description='看板TOP展示数量，默认10、1—200整数；不限制query_materials的完整候选范围。')

    @model_validator(mode='after')
    def ranges(self):
        for prefix in ('current','previous'):
            first=iso_date(getattr(self,prefix+'_start'));last=iso_date(getattr(self,prefix+'_end'))
            if not 0<=(last-first).days<3660:raise ValueError('DATE_RANGE_INVALID')
            a=getattr(self,prefix+'_test_start');b=getattr(self,prefix+'_test_end')
            if (a is None)!=(b is None):raise ValueError('TEST_RANGE_REQUIRES_BOTH_DATES')
            if a is None:
                setattr(self,prefix+'_test_start',first.isoformat());setattr(self,prefix+'_test_end',last.isoformat())
            elif not 0<=(iso_date(b)-iso_date(a)).days<3660:raise ValueError('TEST_RANGE_INVALID')
        return self

    def params(self,tests=False,limit=False):
        result={}
        for a,b in [('currentStart','current_start'),('currentEnd','current_end'),('previousStart','previous_start'),('previousEnd','previous_end')]:result[a]=getattr(self,b)
        if tests:
            for a,b in [('currentTestStart','current_test_start'),('currentTestEnd','current_test_end'),('previousTestStart','previous_test_start'),('previousTestEnd','previous_test_end')]:result[a]=getattr(self,b)
        if limit:result['limit']=self.top_limit
        return result


def iso_date(value):
    if not isinstance(value,str) or not re.fullmatch(r'\d{4}-\d{2}-\d{2}',value):raise ValueError('DATE_INVALID')
    try:d=date.fromisoformat(value)
    except ValueError:raise ValueError('DATE_INVALID') from None
    if not 1900<=d.year<=2200:raise ValueError('DATE_INVALID')
    return d


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):raise QueryError('UPSTREAM_REDIRECT_REJECTED')


class DashboardSource:
    def __init__(self,base_url,synthetic=False):
        u=urlsplit(base_url)
        if u.username or u.password or u.query or u.fragment or not u.hostname:raise QueryError('SOURCE_URL_INVALID')
        if u.scheme!='https' and not (u.scheme=='http' and u.hostname in {'127.0.0.1','localhost','::1'}):raise QueryError('SOURCE_HTTPS_OR_LOOPBACK_REQUIRED')
        if any(x in u.path for x in ('%','..','\\')):raise QueryError('SOURCE_PATH_INVALID')
        self.base=base_url.rstrip('/');self.synthetic=synthetic
        self.opener=build_opener(ProxyHandler({}),NoRedirect())
        self.limit=32*1024**2

    def get(self,path,params=None):
        if path!='/api/projects' and not re.fullmatch(r'/api/projects/[A-Za-z0-9_-]{1,100}/(?:review|review-materials|material-types|top|freshness)',path):
            raise QueryError('UPSTREAM_ROUTE_NOT_READ_ONLY')
        url=self.base+path+('?' + urlencode(params) if params else '')
        request=Request(url,method='GET',headers={'Accept':'application/json','User-Agent':'MaterialReviewMCP/1.0'})
        try:
            with self.opener.open(request,timeout=90) as response:
                if response.status!=200:raise QueryError('UPSTREAM_STATUS_INVALID')
                if response.headers.get_content_type()!='application/json':raise QueryError('UPSTREAM_NOT_JSON')
                body=response.read(self.limit+1)
                if len(body)>self.limit:raise QueryError('UPSTREAM_RESULT_TOO_LARGE_NARROW_PERIOD')
            return json.loads(body,parse_constant=lambda _:(_ for _ in ()).throw(QueryError('UPSTREAM_NONFINITE_NUMBER')))
        except HTTPError as e:
            raise QueryError({404:'PROJECT_OR_RESOURCE_NOT_FOUND',400:'UPSTREAM_QUERY_REJECTED',503:'UPSTREAM_DATA_UNAVAILABLE'}.get(e.code,'UPSTREAM_HTTP_ERROR')) from None
        except (URLError,TimeoutError,OSError):raise QueryError('UPSTREAM_UNAVAILABLE_OR_TIMEOUT') from None
        except (json.JSONDecodeError,UnicodeError):raise QueryError('UPSTREAM_JSON_INVALID') from None


class Reports:
    """In-memory immutable-by-convention snapshots, bounded by TTL/count/bytes."""
    def __init__(self,ttl=900,max_count=4,max_bytes=64*1024**2,clock=time.monotonic):
        self.ttl=ttl;self.max_count=max_count;self.max_bytes=max_bytes;self.clock=clock
        self.items=OrderedDict();self.lock=threading.RLock()

    def prune(self):
        for k,v in list(self.items.items()):
            if v['deadline']<=self.clock():del self.items[k]

    def put(self,data):
        size=len(json.dumps(data,ensure_ascii=False,allow_nan=False).encode())
        if size>self.max_bytes:raise QueryError('REPORT_TOO_LARGE_NARROW_PERIOD')
        with self.lock:
            self.prune()
            while self.items and (len(self.items)>=self.max_count or sum(x['size'] for x in self.items.values())+size>self.max_bytes):self.items.popitem(last=False)
            key=uuid.uuid4().hex;self.items[key]={'data':data,'deadline':self.clock()+self.ttl,'size':size}
            return key

    def get(self,key):
        with self.lock:
            self.prune();entry=self.items.get(key)
            if entry is None:raise QueryError('REPORT_EXPIRED_OR_EVICTED_REQUERY')
            return entry['data']


def without_daily(row):return {k:v for k,v in row.items() if k!='dailySpend'}


def composition(periods):
    """The presentation-only division used by PeriodReview.vue; never recompute source metrics."""
    result=[]
    for p in periods:
        groups={g['key']:g['metrics'] for g in p.get('groups',[])};values={}
        for field in ('spend','materialCount'):
            by_group={}
            for key in ('new','historical','aigc'):
                n=groups.get(key,{}).get(field,{});d=groups.get('overall',{}).get(field,{})
                value={'value':None,'status':'UNKNOWN_INPUT','partial':bool(n.get('partial') or d.get('partial')),
                       'partialReasons':list(dict.fromkeys(n.get('partialReasons',[])+d.get('partialReasons',[])))}
                if n.get('status')=='VALID' and d.get('status')=='VALID' and n.get('value') is not None and d.get('value') is not None:
                    value.update(status='ZERO_DENOMINATOR' if d['value']==0 else 'VALID',value=None if d['value']==0 else n['value']/d['value'])
                by_group[key]=value
            valid=all(v['status']=='VALID' for v in by_group.values())
            by_group['pendingRemainder']={'status':'VALID' if valid else 'UNKNOWN_INPUT','value':max(0,1-sum(v['value'] for v in by_group.values())) if valid else None}
            values[field]=by_group
        result.append({'key':p['key'],'range':p['range'],'coverage':p.get('coverage'),**values})
    return result


class DashboardService:
    def __init__(self,source,store=None):
        self.source=source;self.store=store or Reports();self.gate=asyncio.Semaphore(2)

    async def get(self,path,params=None):return await asyncio.to_thread(self.source.get,path,params)

    async def projects(self):
        rows=await self.get('/api/projects')
        if not isinstance(rows,list) or len(rows)>1000 or any(not isinstance(x,dict) or not re.fullmatch(r'[A-Za-z0-9_-]{1,100}',str(x.get('id',''))) or x.get('enabled') is not True for x in rows):raise QueryError('PROJECT_LIST_INVALID')
        return {'schemaVersion':'mcp-projects-1.0.0','dataMode':'SYNTHETIC' if self.source.synthetic else 'CURRENT_FACTS',
                'projects':[{k:x[k] for k in ('id','name','enabled','category') if k in x} for x in rows]}

    def validate(self,value,project,schema=None):
        mode='SYNTHETIC' if self.source.synthetic else 'CURRENT_FACTS'
        if not isinstance(value,dict) or value.get('projectId')!=project or value.get('evidenceKind')!=mode or not value.get('snapshotFingerprint'):raise QueryError('UPSTREAM_IDENTITY_OR_EVIDENCE_MISMATCH')
        if schema and value.get('schemaVersion')!=schema:raise QueryError('UPSTREAM_SCHEMA_UNSUPPORTED')

    async def period(self,query:PeriodQuery):
        async with self.gate:
            path='/api/projects/'+query.project_id
            for attempt in range(2):
                review=await self.get(path+'/review',query.params(limit=True))
                types=await self.get(path+'/material-types',query.params(tests=True))
                complete=await self.get(path+'/review-materials',query.params())
                self.validate(review,query.project_id,'dashboard-query-1.7.0')
                self.validate(types,query.project_id,'material-type-analysis-1.0.0')
                self.validate(complete,query.project_id,'review-materials-1.0.0')
                if len({v['snapshotFingerprint'] for v in (review,types,complete)})==1:break
            else:raise QueryError('DATA_CHANGED_DURING_QUERY_RETRY')
            for key,prefix in [('CURRENT','current'),('PREVIOUS','previous')]:
                expected={'start':getattr(query,prefix+'_start'),'end':getattr(query,prefix+'_end')}
                expected_test={'start':getattr(query,prefix+'_test_start'),'end':getattr(query,prefix+'_test_end')}
                rp=next((p for p in review.get('periods',[]) if p['key']==key),{})
                tp=next((p for p in types.get('analysis',{}).get('periods',[]) if p['key']==key),{})
                if rp.get('range')!=expected or tp.get('range')!=expected or tp.get('testRange',tp.get('range'))!=expected_test or complete.get(prefix)!=expected:raise QueryError('UPSTREAM_DATE_RANGE_MISMATCH')
            if types['analysis'].get('schemaVersion')!='material-type-review-2.0.0':raise QueryError('MATERIAL_TYPE_V2_REQUIRED')
            rows=complete.get('rows');cohorts=review.get('cohortMaterials',{});analysis=types['analysis']
            if not isinstance(rows,list) or rows[:query.top_limit]!=review.get('topMaterials'):raise QueryError('COMPLETE_LIST_RANKING_MISMATCH')
            groups={'overall':rows,**{k:cohorts[k] for k in ('new','historical','aigc') if k in cohorts},**analysis.get('materials',{}),**(analysis.get('ai') or {}).get('materials',{})}
            for name,items in groups.items():
                if not isinstance(items,list) or len({str(x.get('materialId')) for x in items})!=len(items):raise QueryError('DUPLICATE_OR_INVALID_MATERIALS')
            summary={k:deepcopy(review[k]) for k in ('schemaVersion','metricsState','periods','comparisons') if k in review}
            summary['topMaterials']=[without_daily(x) for x in review['topMaterials']]
            summary['composition']=composition(review['periods'])
            summary['materialTypes']={k:deepcopy(analysis[k]) for k in ('schemaVersion','periods','comparisons') if k in analysis}
            summary['ai']={k:deepcopy(analysis['ai'][k]) for k in ('periods','comparisons') if k in analysis.get('ai',{})} if analysis.get('ai') else None
            summary['compositionRule']='new/historical/aigc spend or materialCount divided by overall of same period; preserve metric status and partial flags; pending remainder must not be assigned to other.'
            warnings=[]
            for p in review['periods']:
                if p.get('coverage',{}).get('state')!='COMPLETE':warnings.append('STATISTIC_DATE_COVERAGE_INCOMPLETE_'+p['key'])
                if p.get('pendingMaterialCount',0):warnings.append('MATERIAL_CLASSIFICATION_PENDING_'+p['key'])
            for p in analysis['periods']:
                if p.get('unresolvedDateMaterials',0):warnings.append('MATERIAL_DATE_UNRESOLVED_'+p['key'])
            report=self.record('period',query.project_id,review['snapshotFingerprint'],query.model_dump(),groups,summary,warnings)
            return self.describe(report,summary)

    def record(self,kind,project,fingerprint,query,groups,summary,warnings):
        now=datetime.now(timezone.utc);data={'kind':kind,'projectId':project,'snapshotFingerprint':fingerprint,'query':query,
          'createdAt':now.isoformat(),'expiresAt':(now+timedelta(seconds=self.store.ttl)).isoformat(),
          'dataMode':'SYNTHETIC' if self.source.synthetic else 'CURRENT_FACTS','groups':groups,'summary':summary,'warnings':warnings}
        key=self.store.put(data);return key

    def describe(self,key,summary=None):
        data=self.store.get(key)
        return deepcopy({'schemaVersion':'mcp-dashboard-report-1.0.0','reportId':key,**{k:data[k] for k in ('kind','projectId','snapshotFingerprint','query','createdAt','expiresAt','dataMode','warnings')},
                'availableGroups':{g:len(v) for g,v in data['groups'].items()},'boards':summary if summary is not None else data['summary'],
                'readOnly':True,'queryDoesNotCollectOrSave':True})

    async def daily(self,project,day,limit=10):
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,100}',project):raise QueryError('PROJECT_ID_INVALID')
        iso_date(day)
        if not isinstance(limit,int) or isinstance(limit,bool) or not 1<=limit<=200:raise QueryError('LIMIT_INVALID')
        async with self.gate:
            value=await self.get('/api/projects/'+project+'/top',{'date':day,'limit':200})
            self.validate(value,project,'dashboard-query-1.7.0')
            if value.get('selectedDate')!=day or not isinstance(value.get('rows'),list):raise QueryError('UPSTREAM_DAILY_RANGE_INVALID')
            summary={k:v for k,v in value.items() if k not in ('rows','snapshotFingerprint','projectId')}
            summary['rows']=[without_daily(x) for x in value['rows'][:limit]]
            summary['candidateScope']='DAILY_RANKED_TOP_200_MAX_NOT_FULL_MATERIAL_UNIVERSE'
            warnings=['DAILY_SEARCH_IS_LIMITED_TO_UPSTREAM_TOP_200']
            if value.get('coverage',{}).get('state')!='COMPLETE':warnings.append('DAILY_COVERAGE_INCOMPLETE')
            key=self.record('daily',project,value['snapshotFingerprint'],{'date':day,'displayLimit':limit},{'overall':value['rows']},summary,warnings)
            return self.describe(key,summary)

    async def freshness(self,project):
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,100}',project):raise QueryError('PROJECT_ID_INVALID')
        value=await self.get('/api/projects/'+project+'/freshness')
        if not isinstance(value,dict) or value.get('projectId')!=project or value.get('schemaVersion')!='freshness-1.0.0':raise QueryError('UPSTREAM_FRESHNESS_INVALID')
        return {**value,'dataMode':'SYNTHETIC' if self.source.synthetic else 'CURRENT_FACTS','meaning':'Latest automatic source day only; not proof of no gaps or latest manual upload. Use period coverage for missing dates.'}

    def rows(self,key,group):
        report=self.store.get(key)
        if group not in report['groups']:raise QueryError('GROUP_DETAIL_UNAVAILABLE_NOT_EMPTY')
        return report,report['groups'][group]

    @staticmethod
    def metric(row,field,group):
        if field=='creationDate':return row.get(field,{'status':'UNKNOWN','date':None})
        if group in {'original','mixed','other','business','department'}:
            return row.get('previousSpend') if field=='previousSpend' else row.get('metrics',{}).get('spend' if field=='currentSpend' else field)
        return row.get(field)

    def materials(self,key,group='overall',search='',filters=None,offset=0,page_size=20,include_daily_spend=False):
        if group not in GROUPS or len(search)>200:raise QueryError('GROUP_OR_SEARCH_INVALID')
        if not 0<=offset<=100000 or not 1<=page_size<=100:raise QueryError('PAGE_INVALID')
        report,rows=self.rows(key,group)
        allowed=DAILY_FIELDS if report['kind']=='daily' else TYPE_FIELDS if group in {'original','mixed','other','business','department'} else BASE_FIELDS
        filters=filters or []
        if len(filters)>20 or any(f.field not in allowed for f in filters):raise QueryError('FILTER_FIELD_UNAVAILABLE_FOR_GROUP')
        needle=search.strip().casefold()
        def matches(row):
            if needle and not any(needle in str(row.get(n) or '').casefold() for n in ('materialId','materialName')):return False
            for f in filters:
                m=self.metric(row,f.field,group)
                if not isinstance(m,dict):raise QueryError('UPSTREAM_METRIC_MISSING')
                if f.operator=='status':
                    if m.get('status') not in f.statuses:return False
                    continue
                state='VERIFIED' if f.field=='creationDate' else 'VALID'
                actual=m.get('date' if f.field=='creationDate' else 'value')
                if m.get('status')!=state or actual is None:return False
                def operand(v):return str(v) if f.field=='creationDate' else float(v)/(100 if f.field in PERCENT_FIELDS else 1)
                if f.operator=='in':ok=actual in [operand(x) for x in f.values]
                else:
                    a=operand(f.value)
                    ok={'eq':lambda:actual==a,'ne':lambda:actual!=a,'gt':lambda:actual>a,'gte':lambda:actual>=a,'lt':lambda:actual<a,'lte':lambda:actual<=a,'between':lambda:a<=actual<=operand(f.to)}[f.operator]()
                if not ok:return False
            return True
        matched=[x for x in rows if matches(x)];selected=matched[offset:offset+page_size]
        result=[deepcopy(x) if include_daily_spend else deepcopy(without_daily(x)) for x in selected]
        output={'reportId':key,'projectId':report['projectId'],'snapshotFingerprint':report['snapshotFingerprint'],'dataMode':report['dataMode'],
                'group':group,'totalCandidates':len(rows),'totalMatched':len(matched),'offset':offset,'pageSize':page_size,
                'nextOffset':offset+page_size if offset+page_size<len(matched) else None,'rows':result,'parentAggregatesUnchanged':True,
                'candidateScope':'ALL_ELIGIBLE_CURRENT_PERIOD_MATERIALS' if report['kind']=='period' else 'DAILY_RANKED_TOP_200_MAX',
                'percentFilterUnit':'percentage points: 3 means 3%; returned metric 0.03 means 3%',
                'dailySpendIncluded':include_daily_spend,'warnings':report['warnings']}
        if len(json.dumps(output,ensure_ascii=False,allow_nan=False).encode())>2*1024**2:raise QueryError('PAGE_TOO_LARGE_REDUCE_SIZE_OR_DISABLE_DAILY_SPEND')
        return output

    def trend(self,key,material_id,group='overall'):
        report,rows=self.rows(key,group)
        row=next((x for x in rows if x['materialId']==material_id),None)
        if row is None:raise QueryError('MATERIAL_NOT_IN_THIS_REPORT_SCOPE')
        return {'reportId':key,'projectId':report['projectId'],'snapshotFingerprint':report['snapshotFingerprint'],
                'dataMode':report['dataMode'],'group':group,'query':report['query'],'material':deepcopy(row),
                'dailyFieldsAvailable':['spend'],'note':'Daily spend is returned as provided by the calculation service. No per-day ROI/click/order figures are fabricated; coverage and metric states remain authoritative.','warnings':report['warnings']}
