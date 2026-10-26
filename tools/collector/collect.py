"""Bounded project-owned reader. No network until explicit --execute."""
import argparse
import copy
import hashlib
import json
import os
import re
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path
from urllib import request, parse

PROFILE = Path('backend/src/main/resources/configuration/material-33-v1.json')
TOOL = 'qianchuan_report_uni_promotion_data_get_v1'
ANCHOR_TOOL = 'qianchuan_uni_aweme_authorized_get_v1'
SORT = 'stat_cost_for_roi2'
SHANGHAI = timezone(timedelta(hours=8))
ERROR_CODES = {'INPUT_REJECTED', 'CREDENTIAL_REJECTED', 'TRANSPORT_FAILED', 'PROTOCOL_REJECTED',
               'TOOL_UNAVAILABLE', 'PAGE_REJECTED', 'ROW_REJECTED', 'GROUP_MISMATCH', 'COLLECTION_FAILED',
               'ANCHOR_PAGE_INVALID', 'ANCHOR_NOT_FOUND', 'ANCHOR_AMBIGUOUS', 'ANCHOR_PROVIDER_REJECTED',
               'BACKFILL_THEME_CONFLICT'}
OPTIONAL_DESCRIPTORS = {'roi2_material_video_name', 'roi2_material_video_type'}
RETRY_51010_DELAYS = (5, 10, 20, 40, 60)
MAX_PROJECT_RETRIES = 12


def error_code(error, fallback='COLLECTION_FAILED'):
    value = str(error) if isinstance(error, Rejected) else None
    return value if value in ERROR_CODES else fallback


def value_type(value):
    return ('null' if value is None else 'boolean' if type(value) is bool else
            'integer' if type(value) is int else 'decimal' if isinstance(value, Decimal) else
            'string' if isinstance(value, str) else 'array' if isinstance(value, list) else
            'object' if isinstance(value, dict) else 'other')


def checked(ok, diagnostic, rule, code, **facts):
    diagnostic['rule'] = rule  # Call sites supply fixed labels, never external text.
    diagnostic['facts'] = facts
    require(ok, code)


def code_facts(value):
    # Numeric provider codes only; arbitrary strings must never reach diagnostics.
    return {'codeType': value_type(value), 'numericCode': value if type(value) is int and abs(value) <= 2147483647 else None}


class Rejected(Exception):
    pass


def require(ok, code='INPUT_REJECTED'):
    if not ok:
        raise Rejected(code)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def pairs(items):
    result = {}
    for key, value in items:
        require(key not in result)
        result[key] = value
    return result


def decode(data):
    return json.loads(data, object_pairs_hook=pairs,
                      parse_float=Decimal,
                      parse_constant=lambda _: require(False))


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(',', ':'), allow_nan=False).encode('utf-8')


def private_path(root, value):
    path = Path(value).absolute()
    resolved = path.resolve(strict=True)
    require(resolved == path and resolved.is_relative_to(root / '.local'))
    require((root / '.local').resolve(strict=True) == root / '.local')
    return resolved


def evidence(path):
    data = path.read_bytes()
    return {'absolutePath': str(path), 'sizeBytes': len(data), 'sha256': sha(data)}


def load_plan(root, path, project_id, now=None):
    path = private_path(root, path)
    plan = decode(path.read_bytes())
    require(plan['schemaVersion'] == 'collection-plan-v1'
            and plan['status'] == 'CONFIGURATION_READY_NOT_EXECUTED'
            and plan['networkEnabled'] is False and plan['collectorExecuted'] is False
            and plan['timeZone'] == 'Asia/Shanghai')
    date = datetime.strptime(plan['statDate'], '%Y-%m-%d').date()
    require(date.isoformat() == plan['statDate'] and 1900 <= date.year <= 2200
            and date < (now or datetime.now(SHANGHAI)).astimezone(SHANGHAI).date())
    item = plan['configurationEvidence']
    config_path = private_path(root, item['absolutePath'])
    require(evidence(config_path) == item and item['sha256'] == plan['revision']
            and config_path.name == plan['revision'] + '.json'
            and config_path.parent.name == 'revisions')
    config = decode(config_path.read_bytes())
    require(config['schemaVersion'] in ('project-configuration-v1', 'project-configuration-v2')
            and config['metricProfile'] == 'material-33-v1')
    enabled = []
    for configured in config['projects']:
        require(type(configured.get('deleted', False)) is bool)
        if configured['enabled'] is not True or configured.get('deleted', False):
            continue
        projected = {k: configured[k] for k in ('id', 'name', 'advertiserId', 'douyinIds')}
        if configured.get('publicDouyinId'):
            require(isinstance(configured['publicDouyinId'], str)
                    and re.fullmatch(r'[A-Za-z0-9_.-]{1,64}', configured['publicDouyinId']))
            projected.update(publicDouyinId=configured['publicDouyinId'], douyinIds=[])
        # Old immutable plans remain readable; new plans pin the selected theme.
        if any('storageAccountId' in p for p in plan['projects']):
            projected['storageAccountId'] = configured.get('storageAccountId', configured['advertiserId'])
        if any('collectionTheme' in p for p in plan['projects']):
            projected['collectionTheme'] = configured.get('collectionTheme', 'QUANYU')
        else:
            require(configured.get('collectionTheme', 'QUANYU') == 'QUANYU')
        if any('expectedAwemeId' in p for p in plan['projects']) and len(configured['douyinIds']) == 1:
            projected['expectedAwemeId'] = configured['douyinIds'][0]
        if any('sourceAccounts' in p for p in plan['projects']):
            accounts = [configured.get('storageAccountId', configured['advertiserId'])]
            previous = config.get('previousRevision')
            seen = {plan['revision']}
            while previous is not None:
                require(isinstance(previous, str) and re.fullmatch('[a-f0-9]{64}', previous)
                        and previous not in seen and len(seen) < 10000)
                seen.add(previous)
                history_path = private_path(root, config_path.parent / (previous + '.json'))
                raw = history_path.read_bytes()
                require(sha(raw) == previous)
                history = decode(raw)
                matches = [p for p in history['projects'] if p['id'] == configured['id']]
                require(len(matches) <= 1)
                if matches:
                    account = matches[0].get('storageAccountId', matches[0]['advertiserId'])
                    if account in accounts: accounts.remove(account)
                    accounts.append(account)
                previous = history.get('previousRevision')
            projected['sourceAccounts'] = list(reversed(accounts))
        enabled.append(projected)
    require(enabled == plan['projects'] and len(enabled) == plan['enabledProjectCount'])
    chosen = [p for p in enabled if p['id'] == project_id]
    require(len(chosen) == 1 and re.fullmatch(r'[a-z][a-z0-9-]{0,39}', project_id))
    project = chosen[0]
    require(re.fullmatch(r'[1-9][0-9]{0,29}', project['advertiserId']))
    require(project.get('publicDouyinId') and project['douyinIds'] == [] or
            not project.get('publicDouyinId') and len(project['douyinIds']) == 1
            and re.fullmatch(r'[1-9][0-9]{0,29}', project['douyinIds'][0]))
    profile_bytes = (root / PROFILE).read_bytes()
    item = plan['metricProfileEvidence']
    require(item == {'resource': '/configuration/material-33-v1.json',
                     'sizeBytes': len(profile_bytes), 'sha256': sha(profile_bytes)})
    catalog = decode(profile_bytes)
    selected = catalog['metrics'] if config['schemaVersion'] == 'project-configuration-v1' else config['enabledMetrics']
    require(isinstance(selected, list) and selected == [m for m in catalog['metrics'] if m in selected]
            and set(catalog['currentDashboardRequiredMetrics']).issubset(selected))
    profile = copy.deepcopy(catalog)
    profile['metrics'] = selected
    for group in profile['groups']:
        group['metrics'] = [m for m in group['metrics'] if m in selected]
    require(plan['profile'] == profile and profile['id'] == 'material-33-v1'
            and profile['toolName'] == TOOL and len(profile['dimensions']) == 5
            and profile['excludeZeroSpend'] is False)
    acquisition = {'catalogId': 'material-33-v1', 'catalogSha256': sha(profile_bytes),
                   'enabledMetrics': selected, 'selectionSha256': sha(encoded(selected)),
                   'configurationRevision': plan['revision']}
    require(plan.get('acquisitionContract') == acquisition)
    theme = project.get('collectionTheme', 'QUANYU')
    require(theme in ('QUANYU', 'CHENGFANG'))
    if theme == 'CHENGFANG':
        profile['dataTopic'] = 'OVERALL_ROI_LIVE_MATERIAL_VIDEO'
        profile['fixedFilters'] = {'roi2_material_type_v3': '3'}
    return plan, project, profile, [evidence(path), evidence(config_path), evidence(root / PROFILE)]


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise Rejected('TRANSPORT_FAILED')


class HttpTransport:
    def __init__(self, key):
        self.key = key
        self.last_body = None
        self.safe_body = None
        self.diagnostic = {}
        self.opener = request.build_opener(request.ProxyHandler({}), NoRedirect())

    def rpc(self, method, params):
        self.last_body = None
        self.safe_body = None
        self.diagnostic.update(stage=method if method in ('initialize', 'notifications/initialized', 'tools/list', 'tools/call') else 'invalid-method')
        self.diagnostic.update(rule='HTTP_REQUEST', facts={})
        require(method in ('initialize', 'notifications/initialized', 'tools/list', 'tools/call'),
                'PROTOCOL_REJECTED')
        if method == 'tools/call':
            require(params.get('name') in (TOOL, ANCHOR_TOOL), 'PROTOCOL_REJECTED')
        payload = {'jsonrpc': '2.0', 'method': method, 'params': params}
        if method != 'notifications/initialized':
            payload['id'] = uuid.uuid4().hex
        headers = {'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream',
                   'Tool-Range': json.dumps([TOOL, ANCHOR_TOOL])}
        url = 'https://open.oceanengine.com/qianchuan/mcp?API_KEY=' + parse.quote(self.key, safe='')
        try:
            with self.opener.open(request.Request(url, data=encoded(payload), headers=headers), timeout=150) as response:
                body = response.read(32 * 1024 * 1024 + 1)
            checked(len(body) <= 32 * 1024 * 1024 and self.key.encode() not in body
                    and parse.quote(self.key, safe='').encode() not in body,
                    self.diagnostic, 'BODY_SIZE_AND_CREDENTIAL_ECHO', 'PROTOCOL_REJECTED')
            if not body and method == 'notifications/initialized':
                return {}
            self.diagnostic.update(rule='JSON_DECODE', facts={})
            result = decode(body)
            normalized = json.dumps(result, ensure_ascii=False, default=str).encode('utf-8')
            checked(self.key.encode() not in normalized and parse.quote(self.key, safe='').encode() not in normalized,
                    self.diagnostic, 'DECODED_CREDENTIAL_ECHO', 'PROTOCOL_REJECTED')
            self.safe_body = body
            checked(isinstance(result, dict), self.diagnostic, 'RPC_OBJECT', 'PROTOCOL_REJECTED', actualType=value_type(result))
            checked(result.get('jsonrpc') == '2.0', self.diagnostic, 'RPC_VERSION', 'PROTOCOL_REJECTED', present='jsonrpc' in result, actualType=value_type(result.get('jsonrpc')))
            rpc_error = result.get('error')
            checked('error' not in result, self.diagnostic, 'RPC_ERROR', 'PROTOCOL_REJECTED',
                    errorType=value_type(rpc_error), **code_facts(rpc_error.get('code') if isinstance(rpc_error, dict) else None))
            checked(isinstance(result.get('result'), dict), self.diagnostic, 'RPC_RESULT_OBJECT', 'PROTOCOL_REJECTED', actualType=value_type(result.get('result')))
            checked(method != 'notifications/initialized' and result.get('id') == payload['id'],
                    self.diagnostic, 'RPC_ID_MATCH', 'PROTOCOL_REJECTED', present='id' in result, actualType=value_type(result.get('id')))
            if method == 'tools/call':
                checked(result['result'].get('isError', False) is False, self.diagnostic, 'TOOL_IS_ERROR', 'PROTOCOL_REJECTED', actualType=value_type(result['result'].get('isError')), isError=result['result'].get('isError') if type(result['result'].get('isError')) is bool else None)
            self.last_body = body
            return result
        except Rejected:
            raise
        except Exception:
            raise Rejected('TRANSPORT_FAILED') from None


def numeric(value):
    require(not isinstance(value, bool) and isinstance(value, (str, int, float, Decimal)), 'ROW_REJECTED')
    try:
        result = Decimal(str(value))
        require(result.is_finite(), 'ROW_REJECTED')
        return result
    except InvalidOperation:
        raise Rejected('ROW_REJECTED') from None


def flatten(row, group, date):
    require(isinstance(row, dict) and set(row) == {'dimensions', 'metrics'}, 'ROW_REJECTED')
    result = {}
    for section, expected in (('dimensions', group['dimensions']), ('metrics', group['metrics'])):
        values = row[section]
        optional = OPTIONAL_DESCRIPTORS if section == 'dimensions' else set()
        require(isinstance(values, dict) and set(values).issubset(expected)
                and set(expected) - set(values) <= optional, 'ROW_REJECTED')
        for key in expected:
            value = values.get(key)  # Only absent descriptive dimensions become null; raw input is unchanged.
            if isinstance(value, dict):
                if key in ('stat_time_day', 'roi2_material_video_name', 'material_create_time_v2'):
                    require('ValueStr' in value, 'ROW_REJECTED')
                    value = value['ValueStr']
                else:
                    require('Value' in value, 'ROW_REJECTED')
                    value = value['Value']  # Explicit null stays null, no display fallback.
            require(value is None or isinstance(value, (str, int, float, Decimal)) and not isinstance(value, bool), 'ROW_REJECTED')
            if isinstance(value, float):
                # A float transport has already lost the original decimal lexeme.
                require(False, 'ROW_REJECTED')
            if isinstance(value, Decimal):
                require(value.is_finite(), 'ROW_REJECTED')
            if section == 'metrics' and isinstance(value, (int, Decimal)):
                value = str(value)  # Exact decimal text; no unit conversion or rounding.
            result[key] = value
    require(result['stat_time_day'] == date and isinstance(result['material_id'], str)
            and result['material_id'].strip() != '', 'ROW_REJECTED')
    numeric(result[SORT])
    return result


def write_once(path, value):
    data = encoded(value)
    write_bytes_once(path, data)


def write_bytes_once(path, data):
    with path.open('xb') as stream:
        stream.write(data)
    require(path.read_bytes() == data)


def resolve_anchor(transport, project, run):
    """Resolve exact public ID from the complete authorized list; never first-match fallback."""
    public_id = project.get('publicDouyinId')
    require(isinstance(public_id, str) and re.fullmatch(r'[A-Za-z0-9_.-]{1,64}', public_id))
    diagnostic = transport.diagnostic
    directory = run / 'anchor'
    directory.mkdir()
    matches, seen, baseline, count = [], set(), None, 0
    for page in range(1, 101):
        diagnostic.update(stage='anchor-resolution', rule='ANCHOR_CALL', page=page, facts={})
        response = transport.rpc('tools/call', {'name': ANCHOR_TOOL, 'arguments': {
            'advertiser_id': project['advertiserId'], 'filtering': {'marketing_goal': 'LIVE_PROM_GOODS',
            'scene': 'CREATE', 'search_key_words': public_id}, 'page': page, 'page_size': 100}})
        diagnostic['stage'] = 'anchor-resolution'
        result = response.get('result', {})
        if 'structuredContent' in result:
            business = result['structuredContent']
        elif 'code' in result and isinstance(result.get('data'), dict):
            business = result
        else:
            content = result.get('content')
            checked(isinstance(content, list) and len(content) == 1 and isinstance(content[0], dict)
                    and content[0].get('type') == 'text' and isinstance(content[0].get('text'), str),
                    diagnostic, 'ANCHOR_CONTENT', 'PROTOCOL_REJECTED')
            business = decode(content[0]['text'])
        checked(isinstance(business, dict), diagnostic, 'ANCHOR_BUSINESS_OBJECT', 'PROTOCOL_REJECTED')
        checked(type(business.get('code')) is int and business['code'] == 0, diagnostic,
                'ANCHOR_BUSINESS_CODE', 'ANCHOR_PROVIDER_REJECTED', **code_facts(business.get('code')))
        data = business.get('data')
        checked(isinstance(data, dict), diagnostic, 'ANCHOR_DATA_OBJECT', 'ANCHOR_PAGE_INVALID')
        info, rows = data.get('page_info'), data.get('aweme_id_list')
        checked(isinstance(info, dict) and isinstance(rows, list), diagnostic, 'ANCHOR_PAGE_STRUCTURE', 'ANCHOR_PAGE_INVALID')
        for field in ('page', 'page_size', 'total_page', 'total_number'):
            checked(type(info.get(field)) is int, diagnostic, 'ANCHOR_PAGE_INTEGER', 'ANCHOR_PAGE_INVALID',
                    field=field, actualType=value_type(info.get(field)))
        total, pages = info['total_number'], info['total_page']
        checked(info['page'] == page and info['page_size'] == 100 and total >= 0 and 0 <= pages <= 100
                and (pages == (total + 99) // 100 or total == 0 and pages == 1), diagnostic,
                'ANCHOR_PAGE_VALUES', 'ANCHOR_PAGE_INVALID', actualPage=info['page'], expectedPage=page,
                pageSize=info['page_size'], total=total, pages=pages)
        checked(baseline is None or baseline == (total, pages), diagnostic, 'ANCHOR_TOTAL_DRIFT', 'ANCHOR_PAGE_INVALID')
        baseline = (total, pages)
        checked(len(rows) == min(100, max(0, total - (page - 1) * 100)) and
                ('has_more' not in info or info['has_more'] is (page < pages)), diagnostic,
                'ANCHOR_PAGE_CLOSURE', 'ANCHOR_PAGE_INVALID', rows=len(rows))
        if isinstance(transport, HttpTransport):
            require(transport.last_body is not None, 'PROTOCOL_REJECTED')
            write_bytes_once(directory / ('page_%03d.http.json' % page), transport.last_body)
        else:
            write_once(directory / ('page_%03d.synthetic.json' % page),
                       json.loads(json.dumps(response, default=str)))
        for row in rows:
            checked(isinstance(row, dict), diagnostic, 'ANCHOR_ROW_OBJECT', 'ANCHOR_PAGE_INVALID')
            identifier = row.get('aweme_id')
            # Provider integer IDs are exact integers; floats/Decimal are never repaired.
            identifier = str(identifier) if type(identifier) is int else identifier
            checked(isinstance(identifier, str) and bool(re.fullmatch(r'[1-9][0-9]{0,29}', identifier))
                    and identifier not in seen and isinstance(row.get('aweme_show_id'), str)
                    and isinstance(row.get('aweme_name'), str) and bool(row['aweme_name'].strip())
                    and len(row['aweme_name']) <= 200, diagnostic, 'ANCHOR_ROW_IDENTITY', 'ANCHOR_PAGE_INVALID')
            seen.add(identifier)
            count += 1
            if row['aweme_show_id'] == public_id:
                matches.append(identifier)
        if page >= pages:
            break
    checked(count == baseline[0], diagnostic, 'ANCHOR_COMPLETE_COUNT', 'ANCHOR_PAGE_INVALID')
    checked(bool(matches), diagnostic, 'ANCHOR_EXACT_MATCH', 'ANCHOR_NOT_FOUND', matchCount=len(matches))
    checked(len(matches) == 1, diagnostic, 'ANCHOR_UNIQUE_MATCH', 'ANCHOR_AMBIGUOUS', matchCount=len(matches))
    return matches[0]


def collect(transport, plan, project, profile, run, sleeper=time.sleep, retry_gate=None, backfill_budget=None):
    diagnostic = transport.diagnostic = {'stage': 'initialize', 'rule': 'INITIALIZE', 'facts': {}}
    initialized = transport.rpc('initialize', {'protocolVersion': '2024-11-05', 'capabilities': {},
                                              'clientInfo': {'name': 'scsj-local-collector', 'version': '1'}})
    require(isinstance(initialized.get('result'), dict) and 'error' not in initialized, 'PROTOCOL_REJECTED')
    diagnostic['stage'] = 'notifications/initialized'
    transport.rpc('notifications/initialized', {})
    diagnostic['stage'] = 'tools/list'
    listed = transport.rpc('tools/list', {})
    require(TOOL in [t.get('name') for t in listed.get('result', {}).get('tools', [])], 'TOOL_UNAVAILABLE')
    if project.get('publicDouyinId'):
        require(ANCHOR_TOOL in [t.get('name') for t in listed.get('result', {}).get('tools', [])], 'TOOL_UNAVAILABLE')
        resolved_anchor = resolve_anchor(transport, project, run)
        require(not project.get("expectedAwemeId") or project["expectedAwemeId"] == resolved_anchor, "ANCHOR_AMBIGUOUS")
        transport.resolved_anchor = resolved_anchor
        project = dict(project, douyinIds=[resolved_anchor])
    all_groups, summaries = {}, []
    delayed_retry_count = backfill_budget.get('used', 0) if backfill_budget is not None else 0
    max_retries = 7 if backfill_budget is not None else MAX_PROJECT_RETRIES
    raw_dir = run / 'raw'
    raw_dir.mkdir()
    for group in profile['groups']:
        rows, baseline = [], None
        group_dir = raw_dir / group['name']
        group_dir.mkdir()
        for page in range(1, profile['maxPagesPerGroup'] + 1):
            diagnostic.update(stage='tools/call', group=group['name'], page=page, rule='CALL', facts={})
            args = {'advertiser_id': project['advertiserId'], 'start_time': plan['statDate'] + ' 00:00:00',
                    'end_time': plan['statDate'] + ' 23:59:59', 'data_topic': profile['dataTopic'],
                    'dimensions': group['dimensions'], 'metrics': group['metrics'],
                    'filters': [{'field': 'anchor_id', 'operator': 7, 'values': project['douyinIds']}] +
                               [{'field': k, 'operator': 7, 'values': [v]} for k, v in profile['fixedFilters'].items()],
                    'order_by': [{'field': SORT, 'order_type': 'DESC'}], 'page': page, 'page_size': group['pageSize']}
            delayed_request = False
            page_retry_count = 0
            for attempt in range(3):
                try:
                    raw = transport.rpc('tools/call', {'name': TOOL, 'arguments': args})
                    sc = raw.get('result', {}).get('structuredContent', raw.get('result', {}))
                    checked(isinstance(sc, dict), diagnostic, 'BUSINESS_OBJECT', 'PROTOCOL_REJECTED', actualType=value_type(sc))
                    while ((backfill_budget is not None and backfill_budget.get(sc.get('code'), 0) > 0)
                           or (backfill_budget is None and sc.get('code') == 51010
                               and delayed_retry_count < MAX_PROJECT_RETRIES and page_retry_count < len(RETRY_51010_DELAYS))):
                        provider_code = sc['code']
                        delay = 60 if backfill_budget is not None else RETRY_51010_DELAYS[page_retry_count]
                        if backfill_budget is not None:
                            backfill_budget[provider_code] -= 1
                            backfill_budget['used'] = delayed_retry_count + 1
                        page_retry_count += 1
                        delayed_retry_count += 1
                        delayed_request = True
                        frozen = copy.deepcopy(args)
                        prefix = 'retry-' if delayed_retry_count == 1 else 'retry-%02d-' % delayed_retry_count
                        retry = {'status': 'RETRY_WAIT', 'providerCode': provider_code, 'retryCount': delayed_retry_count,
                                 'pageRetryCount': page_retry_count, 'maxProjectRetries': max_retries,
                                 'delaySeconds': delay, 'group': group['name'], 'page': page,
                                 'requestSha256': sha(encoded(frozen)),
                                 'retryNotBefore': (datetime.now(timezone.utc) + timedelta(seconds=delay)).isoformat()}
                        if isinstance(transport, HttpTransport) and transport.safe_body is not None:
                            write_bytes_once(run / (prefix + str(provider_code) + '-response.http.json'), transport.safe_body)
                        write_once(run / (prefix + 'wait.json'), retry)
                        print(json.dumps({k: retry[k] for k in ('status', 'providerCode', 'delaySeconds', 'group', 'page', 'retryNotBefore', 'retryCount', 'pageRetryCount', 'maxProjectRetries')}), flush=True)
                        sleeper(delay)
                        if retry_gate:
                            retry_gate(delayed_retry_count)
                        require(args == frozen, 'INPUT_REJECTED')
                        write_once(run / (prefix + 'started.json'), dict(status='RETRY_RUNNING', retryCount=delayed_retry_count,
                                   requestSha256=retry['requestSha256']))
                        print(json.dumps(dict(status='RETRY_RUNNING', group=group['name'], page=page, retryCount=delayed_retry_count)), flush=True)
                        raw = transport.rpc('tools/call', {'name': TOOL, 'arguments': frozen})
                        sc = raw.get('result', {}).get('structuredContent', raw.get('result', {}))
                        checked(isinstance(sc, dict), diagnostic, 'BUSINESS_OBJECT', 'PROTOCOL_REJECTED', actualType=value_type(sc))
                        write_once(run / (prefix + 'finished.json'), dict(status='RETRY_RETURNED', retryCount=delayed_retry_count,
                                   group=group['name'], page=page, **code_facts(sc.get('code'))))
                    if delayed_request:
                        break  # Never add legacy transport/40000 retries after a 51010 retry chain.
                    if backfill_budget is None and sc.get('code') == 40000 and attempt < 2:
                        sleeper(3 * (attempt + 1))
                        continue
                    break
                except Rejected as error:
                    if delayed_request or str(error) != 'TRANSPORT_FAILED' or attempt == 2:
                        raise
                    sleeper(3 * (attempt + 1))
            checked(sc.get('code') == 0, diagnostic, 'BUSINESS_CODE_ZERO', 'PROTOCOL_REJECTED',
                    retryCount=delayed_retry_count, pageRetryCount=page_retry_count, maxProjectRetries=max_retries,
                    **code_facts(sc.get('code')))
            checked(isinstance(sc.get('request_id'), str) and bool(sc['request_id']), diagnostic,
                    'REQUEST_ID_PRESENT', 'PROTOCOL_REJECTED', present='request_id' in sc, actualType=value_type(sc.get('request_id')))
            data = sc.get('data', {})
            checked(isinstance(data, dict), diagnostic, 'DATA_OBJECT', 'PROTOCOL_REJECTED', actualType=value_type(data))
            info, values = data.get('page_info', {}), data.get('rows')
            checked(isinstance(values, list), diagnostic, 'ROWS_ARRAY', 'PAGE_REJECTED', actualType=value_type(values))
            checked(isinstance(info, dict), diagnostic, 'PAGE_INFO_OBJECT', 'PAGE_REJECTED', actualType=value_type(info))
            for field in ('page', 'page_size', 'total_number', 'total_page'):
                checked(type(info.get(field)) is int, diagnostic, 'PAGE_INTEGER_TYPE', 'PAGE_REJECTED', field=field, actualType=value_type(info.get(field)), present=field in info)
            for field, expected in (('page', page), ('page_size', group['pageSize'])):
                checked(info[field] == expected, diagnostic, 'PAGE_VALUE_MATCH', 'PAGE_REJECTED', field=field, actual=info[field], expected=expected)
            checked((1 <= info['total_page'] <= profile['maxPagesPerGroup']) or (info['total_page'] == 0 and info['total_number'] == 0 and page == 1 and not values), diagnostic, 'TOTAL_PAGE_RANGE', 'PAGE_REJECTED', actual=info['total_page'], minimum=1, maximum=profile['maxPagesPerGroup'])
            checked(info['total_number'] >= 0 and len(values) <= group['pageSize'], diagnostic, 'ROW_COUNT_RANGE', 'PAGE_REJECTED', total=info['total_number'], rows=len(values), maximum=group['pageSize'])
            current = (info['total_number'], info['total_page'])
            checked(baseline is None or baseline == current, diagnostic, 'PAGE_TOTAL_DRIFT', 'PAGE_REJECTED', actualTotal=current[0], actualPages=current[1], expectedTotal=None if baseline is None else baseline[0], expectedPages=None if baseline is None else baseline[1])
            baseline = current
            has_more = page < info['total_page']
            checked('has_more' not in info or info['has_more'] is has_more, diagnostic, 'HAS_MORE_MATCH', 'PAGE_REJECTED', actualType=value_type(info.get('has_more')), actual=info.get('has_more') if type(info.get('has_more')) is bool else None, expected=has_more)
            if isinstance(transport, HttpTransport):
                require(transport.last_body is not None, 'PROTOCOL_REJECTED')
                write_bytes_once(group_dir / ('page_%03d.http.json' % page), transport.last_body)
            else:
                # Fake transports are never represented as real HTTP evidence.
                write_once(group_dir / ('page_%03d.synthetic.json' % page),
                           json.loads(json.dumps(raw, default=lambda v: str(v) if isinstance(v, Decimal) else v)))
            diagnostic.update(rule='ROW_CONTRACT', facts={})
            rows.extend(flatten(v, group, plan['statDate']) for v in values)
            summaries.append({'group': group['name'], 'request_id': sc['request_id'], 'code': 0,
                              'page': page, 'page_info': dict(info, has_more=has_more), 'raw_rows': len(values)})
            if not has_more:
                checked(len(rows) == info['total_number'], diagnostic, 'FINAL_ROW_COUNT', 'PAGE_REJECTED', actual=len(rows), expected=info['total_number'])
                break
        require(len({str(r['material_id']) for r in rows}) == len(rows), 'ROW_REJECTED')
        all_groups[group['name']] = rows
    base = all_groups[profile['baseGroup']]
    diagnostic.update(stage='merge', rule='GROUP_CONSISTENCY', facts={})
    index = {str(r['material_id']): dict(r) for r in base}
    excluded = 0
    missing_groups = []
    groups_by_name = {group['name']: group for group in profile['groups']}
    for name, rows in all_groups.items():
        if name == profile['baseGroup']:
            continue
        returned_ids = {str(r['material_id']) for r in rows}
        for mid, target in index.items():
            if mid not in returned_ids:
                require(numeric(target[SORT]) == 0, 'GROUP_MISMATCH')
                missing_groups.append({'materialId': mid, 'group': name,
                                       'reason': 'BASE_ZERO_SPEND_GROUP_ROW_ABSENT'})
        for row in rows:
            mid = str(row['material_id'])
            if mid not in index:
                require(numeric(row[SORT]) == 0, 'GROUP_MISMATCH')
                excluded += 1
                continue
            target = index[mid]
            for key, value in row.items():
                if key in target:
                    require(numeric(target[key]) == numeric(value) if key == SORT else target[key] == value,
                            'GROUP_MISMATCH')
                else:
                    target[key] = value
    # Only absent whole-group rows with proven base zero spend qualify. Defer
    # UNKNOWN until all actual rows have merged, so no observed value is replaced.
    for missing in missing_groups:
        target = index[missing['materialId']]
        fields = [key for key in groups_by_name[missing['group']]['metrics'] if key not in target]
        missing['unknownFields'] = fields
        for key in fields:
            target[key] = 'UNKNOWN'
    result = list(index.values())
    require(all(set(row) == set(profile['dimensions'] + profile['metrics']) for row in result), 'ROW_REJECTED')
    return result, summaries, excluded, missing_groups


def execute(root, plan_path, project_id, real=False, credentials=None, transport=None, sleeper=time.sleep, retry_gate=None, backfill=False):
    root = root.resolve(strict=True)
    plan, project, profile, sources = load_plan(root, plan_path, project_id)
    if not real:
        return {'status': 'READY_NOT_EXECUTED', 'networkEnabled': False}
    if transport is None:
        require(credentials is not None, 'CREDENTIAL_REJECTED')
        credential_path = private_path(root, credentials)
        secret = decode(credential_path.read_bytes())
        require(set(secret) == {'apiKey'} and isinstance(secret['apiKey'], str)
                and 8 <= len(secret['apiKey']) <= 8192 and secret['apiKey'].strip() == secret['apiKey'], 'CREDENTIAL_REJECTED')
        transport = HttpTransport(secret['apiKey'])
    runs = root / '.local' / 'collector-runs'
    runs.mkdir(exist_ok=True)
    require(runs.resolve() == runs)
    run = runs / uuid.uuid4().hex
    run.mkdir()
    receipt = {'schemaVersion': 'scsj-collection-receipt-v1', 'status': 'FAILED', 'sources': sources,
               'projectRef': project_id, 'advertiserId': project['advertiserId'], 'statDate': plan['statDate'],
               'configurationRevision': plan['revision'], 'databaseImported': False,
               'collectionTheme': project.get('collectionTheme', 'QUANYU'), 'dataTopic': profile['dataTopic'],
               'normalizationVersion': 'material-v2-exact-decimal-text-v1',
               'transportEvidence': 'HTTP_BYTES' if isinstance(transport, HttpTransport) else 'SYNTHETIC'}
    try:
        if backfill:
            # Routing is exclusive to gap repair, never changes saved configuration.
            budget = {51010: 1, 40100: 2, 40000: 2, 400153: 2, 'used': 0}
            profile = copy.deepcopy(plan['profile'])
            project = dict(project, collectionTheme='QUANYU')
            route = run / 'route-quanyu'; route.mkdir()
            a = collect(transport, plan, project, profile, route, sleeper, retry_gate, budget)
            a_profile = profile
            profile = copy.deepcopy(profile)
            profile['dataTopic'] = 'OVERALL_ROI_LIVE_MATERIAL_VIDEO'
            profile['fixedFilters'] = {'roi2_material_type_v3': '3'}
            project = dict(project, collectionTheme='CHENGFANG')
            route = run / 'route-chengfang'; route.mkdir()
            b = collect(transport, plan, project, profile, route, sleeper, retry_gate, budget)
            # Both complete results are required, including when A already has rows.
            checks = {theme: {'complete': True, 'rows': len(value[0]), 'rowsSha256': sha(encoded(value[0]))}
                      for theme, value in (('QUANYU', a), ('CHENGFANG', b))}
            write_once(run / 'route-checks.json', checks)
            receipt.update(collectionMode='GAP_BACKFILL', routingPolicy='EXCLUSIVE_NONEMPTY_V1', routeChecks=checks)
            receipt['rawEvidence'] = [evidence(f) for f in sorted(run.rglob('*')) if f.is_file()]
            checked(not (a[0] and b[0]), transport.diagnostic, 'EXCLUSIVE_THEME_REQUIRED',
                    'BACKFILL_THEME_CONFLICT', quanyuRows=len(a[0]), chengfangRows=len(b[0]))
            if a[0]:
                rows, pages, excluded, missing_groups = a
                project = dict(project, collectionTheme='QUANYU')
                profile = a_profile
            else:
                rows, pages, excluded, missing_groups = b
            receipt.update(collectionMode='GAP_BACKFILL', collectionTheme=project['collectionTheme'], dataTopic=profile['dataTopic'])
        else:
            rows, pages, excluded, missing_groups = collect(transport, plan, project, profile, run, sleeper, retry_gate)
        transport.diagnostic = {'stage': 'publication', 'rule': 'SOURCE_HASH_AND_OUTPUT', 'facts': {}}
        if isinstance(transport, HttpTransport):
            transport.safe_body = None
        require(all(evidence(Path(e['absolutePath'])) == e for e in sources))
        digest = sha(encoded(rows))
        catalog = profile['provenance']['referenceCatalogVersion']
        version = profile['datasetId'] + '-' + plan['statDate'] + '-' + catalog + '-' + digest[:12]
        counts = {'rawRowCount': sum(p['raw_rows'] for p in pages), 'reportRowCount': len(rows),
                  'zeroSpendExcludedRows': excluded, 'zeroSpendRows': sum(numeric(r[SORT]) == 0 for r in rows),
                  'pageCount': len(pages)}
        common = {'projectRef': project_id, 'advertiserId': project['advertiserId'],
                  'statDate': plan['statDate'], 'paginationComplete': True}
        common['acquisitionContract'] = plan['acquisitionContract']
        common['collectionSelection'] = {'theme': project.get('collectionTheme', 'QUANYU'),
            'dataTopic': profile['dataTopic'], 'configurationRevision': plan['revision'],
            'awemeId': getattr(transport, 'resolved_anchor', project['douyinIds'][0] if project['douyinIds'] else None)}
        material = dict(common, schemaVersion='qianchuan-material-day-standard-2.0.0',
                        datasetId=profile['datasetId'], catalogVersion=catalog, dataVersion=version,
                        dataHash=digest, rows=rows, counts=counts)
        manifest = dict(common, schemaVersion='qianchuan-material-day-manifest-2.0.0', runId=run.name,
                        jobStatus='COMPLETE', fieldCatalogVersion=catalog, publicationDataVersion=version,
                        publicationDataHash=digest, pages=pages, **counts)
        pending = run / 'pending'
        pending.mkdir()
        write_once(pending / 'material_day.json', material)
        write_once(pending / 'manifest.json', manifest)
        pending.rename(run / 'publication')
        receipt.update(status='COLLECTED_NOT_IMPORTED', missingGroupRows=missing_groups,
                       missingGroupPolicy='base-zero-spend-absent-group-unknown-v1',
                       publication=[evidence(run / 'publication' / f)
                       for f in ('manifest.json', 'material_day.json')],
                       rawEvidence=[evidence(f) for f in sorted(run.rglob('*')) if f.is_file()] if backfill else
                           [evidence(f) for f in sorted((run / 'raw').glob('*/*'))])
        if (run / 'anchor').exists():
            receipt['anchorResolution'] = {'status': 'EXACT_UNIQUE_MATCH', 'configurationRevision': plan['revision'],
                                          'resolvedAwemeId': transport.resolved_anchor,
                                          'evidence': [evidence(f) for f in sorted((run / 'anchor').iterdir())]}
    except Exception as error:
        receipt['errorCode'] = error_code(error)
        receipt['diagnostic'] = getattr(transport, 'diagnostic', {'stage': 'unknown'})
        if isinstance(transport, HttpTransport) and transport.safe_body is not None:
            failure_path = run / 'failure-response.http.json'
            write_bytes_once(failure_path, transport.safe_body)
            receipt['failureEvidence'] = evidence(failure_path)
    write_once(run / 'receipt.json', receipt)
    return {'status': receipt['status'], 'receipt': str(run / 'receipt.json'),
            'errorCode': receipt.get('errorCode')}


def scheduler_gate(root, value, sleeper=time.sleep):
    """Sequenced parent permission; an earlier page's permit cannot release a later page."""
    path = Path(value)
    base = root / '.local/collection-scheduler/attempts'
    require(path.is_absolute() and path.parent.parent == base and path.name == 'retry-permit.json')
    require(len(path.parent.name) == 32 and all(c in '0123456789abcdef' for c in path.parent.name))
    require(path.parent.resolve(strict=True) == path.parent and not path.exists() and not path.is_symlink())
    parent = os.getppid()
    deadline = time.monotonic() + 3 * 3600
    last_sequence = 0
    def wait(sequence):
        nonlocal last_sequence
        require(type(sequence) is int and sequence == last_sequence + 1 and sequence <= MAX_PROJECT_RETRIES)
        while True:
            require(os.getppid() == parent and time.monotonic() < deadline, 'COLLECTION_FAILED')
            require(not path.is_symlink())
            if path.exists():
                require(path.resolve(strict=True) == path and path.is_file() and path.stat().st_size <= 64)
                permit = decode(path.read_bytes())
                require(isinstance(permit, dict) and set(permit) == {'resume', 'retryCount'}
                        and permit['resume'] is True and type(permit['retryCount']) is int
                        and 1 <= permit['retryCount'] <= sequence)
                if permit['retryCount'] == sequence:
                    last_sequence = sequence
                    return
            sleeper(1)
    return wait


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--plan', required=True)
    parser.add_argument('--project', required=True)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--credentials')
    parser.add_argument('--retry-gate')
    parser.add_argument('--backfill', action='store_true')
    args = parser.parse_args()
    try:
        root = Path(__file__).resolve().parents[2]
        gate = scheduler_gate(root, args.retry_gate) if args.retry_gate else None
        result = execute(root, args.plan, args.project, args.execute, args.credentials, retry_gate=gate, backfill=args.backfill)
        print(json.dumps(result))
        return 0 if result['status'] != 'FAILED' else 1
    except Exception as error:
        print(json.dumps({'status': 'REJECTED', 'errorCode': error_code(error, 'INPUT_REJECTED')}))
        return 2


if __name__ == '__main__':
    sys.exit(main())
