"""Server-owned upload context bridge; no network/database or import authorization."""
import json
import sys
import hashlib
from pathlib import Path
from decimal import Decimal, localcontext


def resource_guard():
    """Bound conversion allocations before importing/parsing a workbook.

    This is a process address-space ceiling, not an independent service cgroup.
    """
    if sys.platform == 'linux':
        import resource
        cap = 256 * 1024 * 1024
        soft, hard = resource.getrlimit(resource.RLIMIT_AS)
        if hard != resource.RLIM_INFINITY:
            cap = min(cap, hard)
        if soft != resource.RLIM_INFINITY:
            cap = min(cap, soft)
        resource.setrlimit(resource.RLIMIT_AS, (cap, cap))


# CLI only: importing this module for read-only environment checks does not
# change the caller's limits. The emergency reserve allows a safe exit on OOM.
if __name__ == '__main__':
    resource_guard()
    _emergency_reserve = bytearray(1024 * 1024)
from convert_upload_template import convert_workbook, canonical_json, ConversionError


def run(context_path, output_path):
    context_path, output_path = Path(context_path).resolve(), Path(output_path).resolve()
    root = Path(__file__).resolve().parents[2] / '.local/history-uploads'
    if not context_path.is_relative_to(root.resolve()) or output_path.parent != context_path.parent:
        raise ValueError()
    context = json.loads(context_path.read_text(encoding='utf-8'))
    source = context_path.parent / 'upload.xlsx'
    try:
        result = convert_workbook(source, project_ref=context['projectId'], account_id=context['accountId'],
                                  first_mcp_date=context['firstMcpDate'], existing_dates=context['existingDates'], upload_mode=context.get('uploadMode', 'HISTORY'))
        days = []
        total = Decimal(0)
        for day in result['days']:
            directory = context_path.parent / day['statDate']
            directory.mkdir()
            expected = hashlib.sha256()
            size = 0
            with (directory / 'facts.jsonl').open('xb') as stream:
                for row in day['facts']:
                    line = (canonical_json(row) + '\n').encode('utf-8')
                    stream.write(line)
                    expected.update(line)
                    size += len(line)
            with (directory / 'facts.jsonl').open('rb') as stream:
                if hashlib.file_digest(stream, 'sha256').hexdigest() != expected.hexdigest():
                    raise ValueError()
            with localcontext() as precision:
                precision.prec = 8192
                total += day['totalSpend']
            days.append({k: v for k, v in day.items() if k not in ('facts', 'totalSpend')} |
                        {'totalSpend': format(day['totalSpend'], 'f'), 'factsSizeBytes': size,
                         'factsSha256': expected.hexdigest()})
        output = {'status': 'PREVIEW_READY', 'mappingVersion': result['mappingVersion'],
                  'duplicateRowsSkipped': result['duplicateRowsSkipped'], 'duplicatePolicy': result['duplicatePolicy'],
                  'days': days, 'rowCount': result['rowCount'], 'totalSpend': format(total, 'f')}
    except ConversionError as error:
        output = {'status': 'VALIDATION_FAILED', 'diagnostic': error.diagnostic,
                  'days': [], 'rowCount': 0, 'totalSpend': '0'}
    with output_path.open('xb') as stream:
        stream.write(canonical_json(output).encode('utf-8'))


if __name__ == '__main__':
    try:
        run(*sys.argv[1:])
    except MemoryError:
        _emergency_reserve = None
        print('HISTORY_CONVERSION_RESOURCE_LIMIT', flush=True)
        raise SystemExit(75)
    except Exception:
        print('HISTORY_CONVERSION_FAILED')
        raise SystemExit(2)
