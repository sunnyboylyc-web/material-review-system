"""Runtime XLSX export using only Python's standard library and the shipped template.

This is the application's file-format encoder, not a workbook-authoring dependency.
No formulas, network, AI, database access or mutation of the template.
"""
from copy import deepcopy
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation, localcontext
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = json.loads((ROOT / 'contracts/manual-history-upload-v1.json').read_text(encoding='utf-8'))
NS = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'
REL = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
ET.register_namespace('x', NS)
ET.register_namespace('r', REL)


def tag(name):
    return '{' + NS + '}' + name


def require(ok, code):
    if not ok:
        raise ValueError(code)


def missing(value):
    return value is None or value == '' or value == 'UNKNOWN'


def excel_day(value, required=False):
    if missing(value) or value == '-':
        require(not required, 'EXPORT_DATE_INVALID')
        return None
    value = str(value)
    iso = value[:10]
    if re.search(r'T.*(?:Z|[+-]\d\d:\d\d)$', value.replace(' ', 'T')):
        instant = datetime.fromisoformat(value.replace(' ', 'T').replace('Z', '+00:00'))
        iso = instant.astimezone(timezone(timedelta(hours=8))).date().isoformat()
    require(re.fullmatch(r'\d{4}-\d{2}-\d{2}', iso), 'EXPORT_DATE_INVALID')
    day = date.fromisoformat(iso)
    serial = (day - date(1899, 12, 31)).days
    return serial + (1 if serial >= 60 else 0)


def project_rows(payload):
    rows, days = payload['rows'], payload['days']
    require(isinstance(rows, list) and len(rows) <= 100000 and isinstance(days, list)
            and 0 < len(days) <= 366, 'EXPORT_INPUT_INVALID')
    expected = {}
    for day in days:
        key, count = day['statDate'], day['rowCount']
        excel_day(key, True)
        require(key not in expected and type(count) is int and count >= 0, 'EXPORT_INPUT_INVALID')
        expected[key] = count
    counts, seen = {}, set()
    for row in rows:
        key = row['stat_time_day']
        require(key in expected, 'EXPORT_DATE_MISMATCH')
        require(isinstance(row['material_id'], str), 'EXPORT_ID_NOT_TEXT')
        identity = (key, row['material_id'])
        require(identity not in seen, 'EXPORT_DUPLICATE')
        seen.add(identity)
        counts[key] = counts.get(key, 0) + 1
        values = []
        for column in CONTRACT['columns']:
            value, kind = row.get(column['field']), column['kind']
            if missing(value):
                require(not column.get('required'), 'EXPORT_REQUIRED_MISSING')
                values.append(None)
            elif kind == 'date':
                values.append(excel_day(value, column.get('required', False)))
            elif kind in ('text', 'identifier'):
                values.append(str(value))
            else:
                require(not isinstance(value, bool) and re.fullmatch(
                    r'[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?', str(value)), 'EXPORT_NUMBER_INVALID')
                try:
                    number = Decimal(str(value))
                except InvalidOperation:
                    raise ValueError('EXPORT_NUMBER_INVALID') from None
                require(number.is_finite() and abs(number.adjusted()) <= 308, 'EXPORT_NUMBER_INVALID')
                with localcontext() as precision:
                    precision.prec = max(34, len(number.as_tuple().digits) + 2)
                    values.append(number / 100 if kind == 'percent' else number)
        yield values
    require(all(counts.get(key, 0) == count for key, count in expected.items()), 'EXPORT_COUNT_MISMATCH')


def column_name(index):
    name = ''
    while index:
        index, remainder = divmod(index - 1, 26)
        name = chr(65 + remainder) + name
    return name


def row_xml(number, values, styles, height=26):
    row = ET.Element(tag('row'), r=str(number), ht=str(height), customHeight='1')
    for i, (value, style) in enumerate(zip(values, styles), 1):
        cell = ET.SubElement(row, tag('c'), r=f'{column_name(i)}{number}', s=str(style))
        if value is None:
            continue
        if isinstance(value, str):
            require(len(value) <= 32767 and not re.search(r'[\x00-\x08\x0b\x0c\x0e-\x1f]', value), 'EXPORT_TEXT_INVALID')
            cell.set('t', 'inlineStr')
            text = ET.SubElement(ET.SubElement(cell, tag('is')), tag('t'))
            text.set('{http://www.w3.org/XML/1998/namespace}space', 'preserve')
            text.text = value  # Literal cell, never a formula even when it begins with '='.
        else:
            ET.SubElement(cell, tag('v')).text = str(value)
    return ET.tostring(row, encoding='utf-8')


def sheet_paths(archive):
    relationships = ET.fromstring(archive.read('xl/_rels/workbook.xml.rels'))
    targets = {r.get('Id'): r.get('Target') for r in relationships}
    workbook = ET.fromstring(archive.read('xl/workbook.xml'))
    return {s.get('name'): (targets[s.get('{' + REL + '}id')].lstrip('/')
            if targets[s.get('{' + REL + '}id')].startswith('/')
            else 'xl/' + targets[s.get('{' + REL + '}id')])
            for s in workbook.find(tag('sheets'))}


def write_sheet(destination, name, original, rows, keep_header):
    sheet = ET.fromstring(original)
    data = sheet.find(tag('sheetData'))
    header = ET.tostring(data[0], encoding='utf-8') if keep_header else b''
    data.clear()
    data.append(ET.Comment('EXPORT_ROWS'))
    # Existing template has no dimension; remove it if a later template adds one.
    dimension = sheet.find(tag('dimension'))
    if dimension is not None:
        sheet.remove(dimension)
    before, after = ET.tostring(sheet, encoding='utf-8', xml_declaration=True).split(b'<!--EXPORT_ROWS-->')
    with destination.open(name, 'w') as stream:
        stream.write(before)
        stream.write(header)
        for row in rows:
            stream.write(row)
        stream.write(after)


def guide_rows(payload, title_styles, note_styles):
    yield row_xml(2, ['项目数据下载', None], title_styles, 34)
    notes = [
        ('模板版本', CONTRACT['schemaVersion']), ('项目', str(payload['projectName'])),
        ('账户ID', str(payload['accountId'])),
        ('选择的日期范围', f"{payload['startDate']} 至 {payload['endDate']}（含首尾两天）"),
        ('数据来源', '包含机器采集和已确认导入的人工历史数据。每个日期只导出当前有效版本，不重复累计。'),
        ('本次内容', f"{len(payload['days'])} 天，{len(payload['rows'])} 条素材记录。每行是一条素材一天的数据。"),
        ('如何更新旧表', '打开“素材数据”，将需要的记录按相同列顺序复制到自己的表格。粘贴前确认旧表没有这些日期，避免重复累计。'),
        ('找回数据的范围', '本文件按系统已保存字段重新生成，不恢复原文件的排版、备注或公式。可留存备份，但已有日期不能重复上传覆盖。'),
        ('素材ID', '已按文本保存，复制时也请保持文本格式，避免长数字变为科学计数法或丢失精度。'),
        ('日期与百分比', '日期可排序。点击率等已转为Excel百分比，例如源值4.31显示4.31%。金额单位为元。'),
        ('空白与零', '空白表示来源未提供，不等于零。零是来源中的真实零值，不会用零填补缺失。'),
        ('第三张表', '“日期声明”列出本次导出的日期。已成功保存但没有素材行的完整日期，标为“当天无投放”。'),
        ('未下载的日期', '下方列出未包含的日期及原因。缺失日期不会补成零。' if payload.get('excludedDates') else '所选日期均有可用的已保存数据。'),
        ('转换规则', '沿用历史模板32列顺序。直接导出每天每条素材的原始指标，不以大屏汇总值重新计算ROI等指标。'),
    ]
    for number, values in enumerate(notes, 4):
        yield row_xml(number, values, note_styles, 42)
    excluded = payload.get('excludedDates', [])
    if excluded:
        yield row_xml(20, ['未包含日期', '原因'], note_styles)
        for number, day in enumerate(excluded, 21):
            yield row_xml(number, [day['statDate'], '没有成功保存的数据，本次不下载'], note_styles)
    source_row = 22 + len(excluded)
    yield row_xml(source_row, ['导出日期', '数据来源'], note_styles)
    for number, day in enumerate(payload['days'], source_row + 1):
        yield row_xml(number, [day['statDate'], '人工历史数据' if day['sourceType'] == 'MANUAL_HISTORY' else '机器采集数据'], note_styles)


def template_styles(source, path, row, count):
    sheet = ET.fromstring(source.read(path))
    cells = {c.get('r'): int(c.get('s', '0')) for c in sheet.iter(tag('c'))}
    return [cells[f'{column_name(i)}{row}'] for i in range(1, count + 1)]


def build(payload, output):
    # Exhaust validation before creating any output. Rows are generated again while streaming.
    for _ in project_rows(payload):
        pass
    template = ROOT / 'backend/src/main/resources/templates/manual-history.xlsx'
    with zipfile.ZipFile(template) as source:
        paths = sheet_paths(source)
        styles = ET.fromstring(source.read('xl/styles.xml'))
        formats, xfs = styles.find(tag('numFmts')), styles.find(tag('cellXfs'))
        fmt_id = max(int(f.get('numFmtId')) for f in formats) + 1
        ET.SubElement(formats, tag('numFmt'), numFmtId=str(fmt_id), formatCode='0.00########')
        formats.set('count', str(len(formats)))
        fact_styles = template_styles(source, paths[CONTRACT['sheets']['facts']], 2, len(CONTRACT['columns']))
        day_styles = template_styles(source, paths[CONTRACT['sheets']['days']], 2, 2)
        title_styles = template_styles(source, paths[CONTRACT['sheets']['instructions']], 2, 2)
        note_styles = template_styles(source, paths[CONTRACT['sheets']['instructions']], 4, 2)
        number_column = next(i for i, c in enumerate(CONTRACT['columns']) if c['kind'] == 'number')
        number_style = deepcopy(xfs[fact_styles[number_column]])
        number_style.set('numFmtId', str(fmt_id))
        number_index = len(xfs)
        xfs.append(number_style)
        xfs.set('count', str(len(xfs)))
        fact_styles = [number_index if c['kind'] == 'number' else fact_styles[i] for i, c in enumerate(CONTRACT['columns'])]
        generators = {
            paths[CONTRACT['sheets']['instructions']]: (guide_rows(payload, title_styles, note_styles), False),
            paths[CONTRACT['sheets']['facts']]: ((row_xml(i, values, fact_styles) for i, values in enumerate(project_rows(payload), 2)), True),
            paths[CONTRACT['sheets']['days']]: ((row_xml(i, [excel_day(day['statDate'], True), '整日完整' if day['rowCount'] else '当天无投放'], day_styles) for i, day in enumerate(payload['days'], 2)), True),
        }
        with zipfile.ZipFile(output, 'x', compression=zipfile.ZIP_DEFLATED) as destination:
            for item in source.infolist():
                if item.filename in generators:
                    rows, keep_header = generators[item.filename]
                    write_sheet(destination, item.filename, source.read(item), rows, keep_header)
                elif item.filename == 'xl/styles.xml':
                    destination.writestr(item, ET.tostring(styles, encoding='utf-8', xml_declaration=True))
                else:
                    destination.writestr(item, source.read(item))


def main():
    require(len(sys.argv) == 3, 'EXPORT_ARGUMENTS_INVALID')
    input_path, output_path = map(lambda s: Path(s).absolute(), sys.argv[1:])
    private = (ROOT / '.local/collected-exports').resolve()
    require(input_path.resolve(strict=True) == input_path and input_path.is_relative_to(private)
            and output_path.parent == input_path.parent and output_path.name == 'output.xlsx', 'EXPORT_PATH_INVALID')
    build(json.loads(input_path.read_text(encoding='utf-8'), parse_float=Decimal), output_path)


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('EXPORT_WORKBOOK_FAILED', file=sys.stderr)
        raise SystemExit(2)
