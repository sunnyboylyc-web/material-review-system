"""D085 standard historical XLSX validation. Offline preview only, never imports."""
from __future__ import annotations

import argparse
import datetime as dt
from decimal import Decimal, InvalidOperation, localcontext
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import uuid
import zipfile
import zlib
from collections.abc import Sequence
import xml.etree.ElementTree as ET
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = json.loads((ROOT / 'contracts/manual-history-upload-v1.json').read_text(encoding='utf-8'))
SOURCE_MAPPING = json.loads((ROOT / 'contracts' / CONTRACT['sourceMapping']).read_text(encoding='utf-8'))
NS = '{http://schemas.openxmlformats.org/spreadsheetml/2006/main}'
REL = '{http://schemas.openxmlformats.org/officeDocument/2006/relationships}'
STATUS = 'STAGED_UNVERIFIED_NOT_IMPORTABLE'


class ConversionError(ValueError):
    def __init__(self, code, message, sheet=None, row=None, column=None):
        self.diagnostic = dict(code=code, message=message, sheet=sheet, row=row, column=column)
        super().__init__(f'{sheet or "工作簿"} 第{row or "—"}行 {column or "—"}列：{message}')


def fail(code, message, sheet=None, row=None, column=None):
    raise ConversionError(code, message, sheet, row, column)


def canonical_json(value):
    """JSON numbers retain raw XLSX decimal precision; never pass through float."""
    if isinstance(value, Decimal):
        if not value.is_finite():
            raise ValueError('Nonfinite decimal')
        return format(value, 'f')
    if isinstance(value, dict):
        return '{' + ','.join(json.dumps(k, ensure_ascii=False) + ':' + canonical_json(v)
                              for k, v in sorted(value.items())) + '}'
    if isinstance(value, (list, tuple, PackedFacts)):
        return '[' + ','.join(map(canonical_json, value)) + ']'
    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(',', ':'))


class PackedFacts(Sequence):
    """Keep validated rows compact; decode only the row being consumed.

    No temporary business files, floats, or lossy numeric conversion. Ordering
    and last-row replacement operate on the packed records, not full dicts.
    """
    def __init__(self):
        self.records = []

    def __len__(self):
        return len(self.records)

    def __getitem__(self, index):
        if isinstance(index, slice):
            return [self[i] for i in range(*index.indices(len(self)))]
        return json.loads(zlib.decompress(self.records[index][1]),
                          parse_float=Decimal, parse_int=Decimal)

    @staticmethod
    def pack(fact):
        return fact['material_id'], zlib.compress(canonical_json(fact).encode('utf-8'), 1)

    def append(self, fact):
        self.records.append(self.pack(fact))

    def __setitem__(self, index, fact):
        self.records[index] = self.pack(fact)

    def sort_by_material(self):
        self.records.sort(key=lambda record: record[0])

    def __eq__(self, other):
        if not isinstance(other, Sequence) or len(self) != len(other):
            return False
        return all(a == b for a, b in zip(self, other))


def col_index(name):
    result = 0
    for char in name:
        result = result * 26 + ord(char) - 64
    return result


def facts_hash(facts):
    digest = hashlib.sha256(b'[')
    encoded = (zlib.decompress(record[1]) for record in facts.records) if isinstance(facts, PackedFacts) else (
        canonical_json(fact).encode('utf-8') for fact in facts)
    for index, content in enumerate(encoded):
        if index:
            digest.update(b',')
        digest.update(content)
    digest.update(b']')
    return digest.hexdigest()


def col_name(index):
    result = ''
    while index:
        index, rem = divmod(index - 1, 26)
        result = chr(65 + rem) + result
    return result


def xml(data):
    if b'\x00' in data:
        fail('XML_ENCODING', '仅支持 UTF-8 XML 编码的 XLSX 文件')
    try:
        data.decode('utf-8-sig')
    except UnicodeDecodeError:
        fail('XML_ENCODING', '仅支持 UTF-8 XML 编码的 XLSX 文件')
    declaration = re.match(br'\s*<\?xml[^>]*encoding=[\"\x27]([^\"\x27]+)', data.removeprefix(b'\xef\xbb\xbf'))
    if declaration and declaration[1].lower() not in (b'utf-8', b'utf8', b'us-ascii'):
        fail('XML_ENCODING', '仅支持 UTF-8 XML 编码的 XLSX 文件')
    if b'<!DOCTYPE' in data.upper() or b'<!ENTITY' in data.upper():
        fail('XML_ENTITY', '禁止 XML 实体或文档类型声明')
    try:
        return ET.fromstring(data)
    except ET.ParseError:
        fail('XML_INVALID', '工作簿 XML 无法解析')


def elements(data, tag=None):
    """Bounded XML traversal: never retain the complete worksheet element tree.

    Security validation happens before use in read_package. Keep a target subtree
    only until its end event, then detach it (clear alone leaves millions of nodes).
    """
    stack = []
    holding = 0
    try:
        for event, node in ET.iterparse(io.BytesIO(data), events=('start', 'end')):
            if event == 'start':
                stack.append(node)
                if tag is not None and node.tag == tag:
                    holding += 1
            else:
                target = tag is not None and node.tag == tag
                if target:
                    yield node
                    holding -= 1
                if not holding:
                    if len(stack) > 1:
                        stack[-2].remove(node)
                    node.clear()
                stack.pop()
    except ET.ParseError:
        fail('XML_INVALID', '工作簿 XML 无法解析')


def validate_xml_stream(data):
    # Reuse the encoding/entity guards without constructing the full tree.
    if b'\x00' in data:
        fail('XML_ENCODING', '仅支持 UTF-8 XML 编码的 XLSX 文件')
    import codecs
    decoder = codecs.getincrementaldecoder('utf-8-sig')()
    try:
        for offset in range(0, len(data), 65536):
            decoder.decode(data[offset:offset + 65536])
        decoder.decode(b'', final=True)
    except UnicodeDecodeError:
        fail('XML_ENCODING', '仅支持 UTF-8 XML 编码的 XLSX 文件')
    declaration = re.match(br'\s*<\?xml[^>]*encoding=[\"\x27]([^\"\x27]+)', data[:1024].removeprefix(b'\xef\xbb\xbf'))
    if declaration and declaration[1].lower() not in (b'utf-8', b'utf8', b'us-ascii'):
        fail('XML_ENCODING', '仅支持 UTF-8 XML 编码的 XLSX 文件')
    for offset in range(0, len(data), 65536):
        chunk = data[max(0, offset - 16):offset + 65536].upper()
        if b'<!DOCTYPE' in chunk or b'<!ENTITY' in chunk:
            fail('XML_ENTITY', '禁止 XML 实体或文档类型声明')
    for _ in elements(data):
        pass


def read_package(source):
    source = Path(source)
    if source.suffix.lower() != '.xlsx':
        fail('FILE_TYPE', '仅接受 .xlsx 文件')
    size = source.stat().st_size
    if size > CONTRACT['limits']['maxFileBytes']:
        fail('FILE_LIMIT', '文件超过 20 MiB')
    raw = source.read_bytes()
    if len(raw) > CONTRACT['limits']['maxFileBytes']:
        fail('FILE_LIMIT', '文件超过 20 MiB')
    try:
        import io
        archive = zipfile.ZipFile(io.BytesIO(raw))
        with archive:
            infos = archive.infolist()
            if len(infos) > CONTRACT['limits']['maxZipEntries']:
                fail('ZIP_ENTRY_LIMIT', 'ZIP 条目过多')
            if sum(i.file_size for i in infos) > CONTRACT['limits']['maxExpandedBytes']:
                fail('ZIP_SIZE_LIMIT', '解压大小超过 100 MiB')
            names = [i.filename for i in infos]
            if len(names) != len(set(names)):
                fail('ZIP_DUPLICATE', 'ZIP 中存在重复路径')
            package = {}
            for info in infos:
                p = PurePosixPath(info.filename)
                if p.is_absolute() or '..' in p.parts or '\\' in info.filename or ':' in info.filename:
                    fail('ZIP_PATH', 'ZIP 含非法路径')
                if info.flag_bits & 1:
                    fail('ENCRYPTED', '不接受加密文件')
                lower = info.filename.lower()
                if any(x in lower for x in ('vbaproject', 'externallinks/', 'embeddings/', 'activex/', 'macrosheets/')):
                    fail('ACTIVE_CONTENT', '不接受宏、嵌入对象或外部链接')
                data = archive.read(info)
                if lower.endswith(('.xml', '.rels')):
                    validate_xml_stream(data)
                    if lower.endswith('.rels'):
                        node = xml(data)
                        for relationship in node:
                            if relationship.get('TargetMode', '').lower() == 'external':
                                # Worksheet hyperlink metadata is never followed or emitted.
                                # Cell text remains the source value; other external relations fail.
                                if (re.fullmatch(r'xl/worksheets/_rels/[^/]+\.xml\.rels', info.filename)
                                        and relationship.get('Type') == 'http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink'):
                                    continue
                                fail('EXTERNAL_LINK', '不接受外部链接')
                    if b'macroEnabled' in data:
                        fail('ACTIVE_CONTENT', '不接受宏工作簿')
                package[info.filename] = data
    except (zipfile.BadZipFile, RuntimeError, NotImplementedError):
        fail('ZIP_INVALID', '文件不是有效的未加密 XLSX')
    return package, {'sizeBytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}


def read_sheets(package):
    try:
        workbook = xml(package['xl/workbook.xml'])
        relationships = {e.get('Id'): e.get('Target') for e in xml(package['xl/_rels/workbook.xml.rels'])}
        definitions = workbook.find(NS + 'sheets')
        names = [s.get('name') for s in definitions]
        if len(names) != 3 or set(names) != set(CONTRACT['sheets'].values()):
            fail('SHEETS', '必须且只能包含填表说明、素材数据、日期声明三个工作表')
        strings = []
        if 'xl/sharedStrings.xml' in package:
            for item in elements(package['xl/sharedStrings.xml'], NS+'si'):
                strings.append(''.join(n.text or '' for n in item.iter(NS + 't')))
        date_styles = set()
        if 'xl/styles.xml' in package:
            styles = xml(package['xl/styles.xml'])
            fmts = {int(n.get('numFmtId')): n.get('formatCode', '') for n in styles.findall(NS+'numFmts/'+NS+'numFmt')}
            for index, node in enumerate(styles.findall(NS+'cellXfs/'+NS+'xf')):
                number = int(node.get('numFmtId', '0'))
                code = re.sub(r'"[^"]*"|\\.|\[[^\]]*\]', '', fmts.get(number, '')).lower()
                if number in set(range(14, 18)) | set(range(27, 37)) | {22, 50, 51, 52, 53, 54, 57, 58} or ('d' in code and ('y' in code or 'm' in code)):
                    date_styles.add(index)
        prop = workbook.find(NS+'workbookPr')
        epoch1904 = prop is not None and prop.get('date1904') in ('1', 'true')
        result = {}
        for definition in definitions:
            name = definition.get('name')
            target = relationships[definition.get(REL+'id')]
            path = target.lstrip('/') if target.startswith('/') else 'xl/'+target
            if '..' in PurePosixPath(path).parts:
                fail('REL_PATH', '工作表路径不合法', name)
            result[name] = SheetCells(package[path], strings, date_styles, epoch1904, name)
        return result
    except (KeyError, TypeError, ValueError, IndexError) as exc:
        if isinstance(exc, ConversionError):
            raise
        fail('STRUCTURE', '工作簿结构、样式或共享字符串不合法')


class SheetCells:
    def __init__(self, data, strings, date_styles, epoch1904, name):
        self.data, self.strings, self.date_styles = data, strings, date_styles
        self.epoch1904, self.name = epoch1904, name

    def get(self, ref, default=None):
        value = default
        for key, entry in self.items():
            if key == ref:
                value = entry
        return value

    def __getitem__(self, ref):
        missing = object()
        result = self.get(ref, missing)
        if result is missing:
            raise KeyError(ref)
        return result

    def items(self):
        name, strings, date_styles, epoch1904 = self.name, self.strings, self.date_styles, self.epoch1904
        previous_row, seen = 0, set()
        try:
            for cell in elements(self.data, NS+'c'):
                ref = cell.get('r', '')
                match = re.fullmatch(r'([A-Z]{1,3})([1-9][0-9]*)', ref)
                if not match:
                    fail('CELL_REF', '单元格坐标不合法', name)
                column, row = match[1], int(match[2])
                if row < previous_row:
                    fail('CELL_ORDER', '工作表行顺序异常，请用表格软件另存为标准 XLSX', name, row, column)
                if row > previous_row:
                    seen.clear()
                    previous_row = row
                if ref in seen:
                    fail('DUPLICATE_CELL', '重复单元格', name, row, column)
                seen.add(ref)
                if cell.find(NS+'f') is not None:
                    fail('FORMULA', '请粘贴数值，不接受公式', name, row, column)
                kind = cell.get('t', 'n')
                text = cell.findtext(NS+'v')
                if text is not None and len(text) > CONTRACT['limits']['maxCellCharacters']:
                    fail('TEXT_LIMIT', '单元格超过 4096 字符', name, row, column)
                if kind == 's':
                    value = strings[int(text)]
                elif kind == 'inlineStr':
                    value = ''.join(n.text or '' for n in cell.iter(NS+'t'))
                elif kind == 'n' and text is not None:
                    try:
                        value = Decimal(text)
                    except InvalidOperation:
                        fail('NUMBER', '无效数值', name, row, column)
                    if not value.is_finite() or abs(value.adjusted()) > 100:
                        fail('NUMBER', '数值超出支持范围', name, row, column)
                elif kind in ('str', 'd'):
                    value = text
                elif text is None:
                    value = None
                else:
                    fail('CELL_TYPE', '不接受布尔值或错误单元格', name, row, column)
                if isinstance(value, str) and len(value) > CONTRACT['limits']['maxCellCharacters']:
                    fail('TEXT_LIMIT', '单元格超过 4096 字符', name, row, column)
                yield ref, (value, kind, int(cell.get('s', '0')) in date_styles, epoch1904)
        except (KeyError, TypeError, ValueError, IndexError) as exc:
            if isinstance(exc, ConversionError):
                raise
            fail('STRUCTURE', '工作簿结构、样式或共享字符串不合法')


def date_value(value, where, excel_date=False, epoch1904=False, allow_time=False):
    if isinstance(value, Decimal) and excel_date:
        if allow_time and value >= 0:
            # Excel stores time as the fractional day; truncate, never round.
            value = Decimal(int(value))
        if value != value.to_integral_value() or value < 0 or value > 2958465 or (not epoch1904 and value == 60):
            fail('DATE', '日期必须为有效整日，不能带时间', *where)
        serial = int(value)
        try:
            value = ((dt.date(1904, 1, 1) + dt.timedelta(days=serial)) if epoch1904 else
                     (dt.date(1899, 12, 31) + dt.timedelta(days=serial - (serial > 60)))).isoformat()
        except OverflowError:
            fail('DATE', '日期超出范围', *where)
    if allow_time and isinstance(value, str) and re.fullmatch(r'\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?', value):
        try:
            value = dt.datetime.fromisoformat(value).date().isoformat()
        except ValueError:
            fail('DATE', '不存在的创建日期或时间', *where)
    if not isinstance(value, str) or not re.fullmatch(r'\d{4}-\d{2}-\d{2}', value):
        fail('DATE', '日期使用 Excel 日期或 YYYY-MM-DD，不带时间', *where)
    try:
        return dt.date.fromisoformat(value).isoformat()
    except ValueError:
        fail('DATE', '不存在的日期', *where)


def repeated_fact_header(values):
    """Recognize labels, never infer a header merely from an invalid date."""
    def label(value):
        return re.sub(r'\s+', '', value).casefold() if isinstance(value, str) else value
    labels = [label(entry[0]) for entry in values]
    date_prefix = labels[0] in (None, '', '统计日期', '文件日期', '日期', '期日')
    if not date_prefix:
        try:
            date_value(values[0][0], ('表头识别', None, 'A'), values[0][2], values[0][3])
            date_prefix = True
        except ConversionError:
            pass
    if not (date_prefix
            and labels[1] == '素材id'
            and labels[2] in ('素材名称', '全域素材视频名称')
            and labels[4] in ('消耗（元）', '消耗(元)', '整体消耗', '消耗')):
        return False
    # Any actual numeric/date-looking value keeps the row in ordinary validation.
    return all(value in (None, '') or (isinstance(value, str)
               and re.search(r'[\u4e00-\u9fffA-Za-z]', value)
               and not re.fullmatch(r'\d{4}[-/]\d{1,2}[-/]\d{1,2}.*', value))
               for value in labels[1:])


def rows(cells, headers, sheet, max_rows):
    if isinstance(cells, SheetCells):
        current, values, count, header_seen = 1, {}, 0, False
        def finish(row, entries):
            nonlocal count, header_seen
            if row == 1:
                for i, expected in enumerate(headers, 1):
                    if entries.get(i, (None,))[0] != expected:
                        fail('HEADER', f'表头必须是“{expected}”', sheet, 1, col_name(i))
                header_seen = True
                return None
            if not header_seen:
                fail('HEADER', '缺少模板表头', sheet, 1, 'A')
            if not entries:
                return None
            count += 1
            if count > max_rows:
                fail('ROW_LIMIT', '数据行数超过模板限制', sheet)
            return row, [entries.get(i, (None, 'n', False, False)) for i in range(1, len(headers)+1)]
        for ref, entry in cells.items():
            match = re.fullmatch(r'([A-Z]+)(\d+)', ref)
            row, col = int(match[2]), col_index(match[1])
            if row != current:
                completed = finish(current, values)
                if completed is not None:
                    yield completed
                current, values = row, {}
            if entry[0] not in (None, ''):
                if col > len(headers):
                    fail('EXTRA_COLUMN', '存在模板外的数据列', sheet, row, match[1])
                values[col] = entry
        completed = finish(current, values)
        if completed is not None:
            yield completed
        return
    for i, expected in enumerate(headers, 1):
        if cells.get(col_name(i)+'1', (None,))[0] != expected:
            fail('HEADER', f'表头必须是“{expected}”', sheet, 1, col_name(i))
    occupied = set()
    for ref, entry in cells.items():
        if entry[0] in (None, ''):
            continue
        m = re.fullmatch(r'([A-Z]+)(\d+)', ref)
        if col_index(m[1]) > len(headers):
            fail('EXTRA_COLUMN', '存在模板外的数据列', sheet, int(m[2]), m[1])
        if int(m[2]) > 1:
            occupied.add(int(m[2]))
    if len(occupied) > max_rows:
        fail('ROW_LIMIT', '数据行数超过模板限制', sheet)
    for row in sorted(occupied):
        yield row, [cells.get(col_name(i)+str(row), (None, 'n', False, False)) for i in range(1, len(headers)+1)]


def convert_workbook(source, *, project_ref, account_id, first_mcp_date, existing_dates=(), upload_mode="HISTORY"):
    if upload_mode not in ("HISTORY", "GAP_FILL"): fail("MODE", "上传模式无效")
    if not isinstance(project_ref, str) or not project_ref.strip() or len(project_ref) > 128:
        fail('PROJECT', '必须明确选择项目')
    if not isinstance(account_id, str) or not re.fullmatch(r'[0-9]{1,64}', account_id):
        fail('ACCOUNT', '账户 ID 必须为数字文本')
    cutoff = (date_value(first_mcp_date, ('采集上下文', None, None)) if first_mcp_date and upload_mode == "HISTORY"
              else dt.datetime.now(ZoneInfo('Asia/Shanghai')).date().isoformat())
    existing = {date_value(d, ('已有日期上下文', None, None)) for d in existing_dates}
    package, source_evidence = read_package(source)
    sheets = read_sheets(package)
    if sheets[CONTRACT['sheets']['instructions']].get('B4', (None,))[0] != CONTRACT['schemaVersion']:
        fail('TEMPLATE_VERSION', '模板版本不匹配，请下载当前模板', CONTRACT['sheets']['instructions'], 4, 'B')
    declarations = {}
    day_sheet = CONTRACT['sheets']['days']
    for row, values in rows(sheets[day_sheet], CONTRACT['dayHeaders'], day_sheet, CONTRACT['limits']['maxDays']):
        value, _, is_date, epoch = values[0]
        day = date_value(value, (day_sheet, row, 'A'), is_date, epoch)
        if day >= cutoff:
            fail('CUTOFF', '历史日期必须早于首个成功 MCP 统计日' if first_mcp_date else '尚无机器采集，只能上传北京时间昨天及更早的日期', day_sheet, row, 'A')
        if day in existing:
            fail('OVERLAP', '该账户日期已有数据，禁止重复或覆盖', day_sheet, row, 'A')
        if day in declarations:
            fail('DUPLICATE_DAY', '同一日期不能重复声明', day_sheet, row, 'A')
        state = CONTRACT['dayStates'].get(values[1][0])
        if not state:
            fail('DAY_STATE', '请选择整日完整或当天无投放', day_sheet, row, 'B')
        declarations[day] = {'state': state, 'row': row, 'facts': PackedFacts()}
    fact_sheet = CONTRACT['sheets']['facts']
    columns = CONTRACT['columns']
    seen = {}
    duplicate_rows_skipped = 0
    for row, values in rows(sheets[fact_sheet], [c['header'] for c in columns], fact_sheet, CONTRACT['limits']['maxFactRows']):
        if repeated_fact_header(values):
            continue
        # User-confirmed leftover manual rows, not an unknown creation date.
        if values[3][0] == '全部':
            continue
        fact = {'roi2_material_video_type': 'UNKNOWN'}
        for i, (spec, entry) in enumerate(zip(columns, values), 1):
            value, kind, is_date, epoch = entry
            where = (fact_sheet, row, col_name(i))
            if spec['field'] == 'material_create_time_v2' and value == '-':
                value = None  # Confirmed manual missing-date marker; retain the fact.
            if value in (None, '', 'UNKNOWN'):
                if spec['required']:
                    fail('REQUIRED', f'{spec["header"]}必填', *where)
                normalized = 'UNKNOWN'
            elif spec['kind'] == 'date':
                normalized = date_value(value, where, is_date, epoch,
                                        allow_time=spec['field'] == 'material_create_time_v2')
            elif spec['kind'] == 'identifier':
                if not isinstance(value, str) or kind not in ('s', 'inlineStr', 'str') or value != value.strip():
                    fail('ID_TEXT', '素材 ID 必须为完整文本，不能是数值或带首尾空格', *where)
                normalized = value
            elif spec['kind'] == 'text':
                if isinstance(value, Decimal):
                    # Names are display text, not identifiers: preserve the stored
                    # numeric representation, including exponent notation.
                    value = str(value)
                if not isinstance(value, str):
                    fail('TEXT', '名称必须为文本', *where)
                normalized = value
            else:
                if not isinstance(value, Decimal):
                    fail('NUMBER', '指标必须为数值，缺失请留空', *where)
                with localcontext() as ctx:
                    ctx.prec = max(64, len(value.as_tuple().digits) + 4)
                    normalized = value * 100 if spec['kind'] == 'percent' else value
            fact[spec['field']] = normalized
        for rule in SOURCE_MAPPING['exactSentinelRules']:
            if fact['material_id'] == rule['workbookMaterialId'] and fact[rule['nameField']] == rule['exactName']:
                fact['material_id'] = rule['canonicalMaterialId']
        if not re.fullmatch(r'[0-9]+|-2', fact['material_id']):
            fail('ID_TEXT', '素材 ID 应为数字文本；“-”仅适用于精确 AIGC 集合名称', fact_sheet, row, 'B')
        if fact['material_id'] == '-2' and not any(fact[rule['nameField']] == rule['exactName'] for rule in SOURCE_MAPPING['exactSentinelRules']):
            fail('ID_TEXT', '“-2”仅适用于精确 AIGC 集合名称', fact_sheet, row, 'B')
        day = fact['stat_time_day']
        if day >= cutoff:
            fail('CUTOFF', '历史日期必须早于首个成功 MCP 统计日' if first_mcp_date else '尚无机器采集，只能上传北京时间昨天及更早的日期', fact_sheet, row, 'A')
        if day in existing:
            fail('OVERLAP', '该账户日期已有数据，禁止重复或覆盖', fact_sheet, row, 'A')
        if day not in declarations:
            declarations[day] = {'state': 'COMPLETE', 'row': None, 'facts': PackedFacts()}
        if len(declarations) > CONTRACT['limits']['maxDays']:
            fail('ROW_LIMIT', '统计日期去重后超过366天，请按日期拆分文件', fact_sheet, row, 'A')
        if declarations[day]['state'] == 'COMPLETE_NO_ROWS':
            fail('EMPTY_CONFLICT', '当天无投放不能包含素材数据', fact_sheet, row, 'A')
        key = (day, fact['material_id'])
        if key in seen:
            declarations[day]['facts'][seen[key]] = fact
            duplicate_rows_skipped += 1
            continue
        seen[key] = len(declarations[day]['facts'])
        declarations[day]['facts'].append(fact)
    if not declarations:
        fail('NO_DAYS', '请填写素材数据，或在日期声明中填写确认无投放的日期', fact_sheet, 2, 'A')
    # Parsing is finished: do not retain expanded XML/shared strings/index while
    # computing day summaries or while the bridge writes the validated rows.
    del sheets, package, seen
    days = []
    for day, info in sorted(declarations.items()):
        if info['state'] == 'COMPLETE' and not info['facts']:
            fail('COMPLETE_WITHOUT_ROWS', '整日完整必须有素材数据', day_sheet, info['row'], 'B')
        facts = info['facts']
        facts.sort_by_material()
        with localcontext() as ctx:
            ctx.prec = 8192
            spend, zero_count = Decimal(0), 0
            for f in facts:
                spend += f['stat_cost_for_roi2']
                zero_count += f['stat_cost_for_roi2'] == 0
        days.append(dict(statDate=day, declaredCompleteness=info['state'], rowCount=len(facts),
                         zeroSpendRowCount=zero_count,
                         totalSpend=spend, facts=facts,
                         contentHash=facts_hash(facts)))
    return dict(schemaVersion='manual-history-upload-preview-1.0.0', mappingVersion=CONTRACT['mappingVersion'],
                sourceType='MANUAL_HISTORY', status=STATUS, databaseImportEligible=False,
                identityStatus='UNVERIFIED_SELECTED_CONTEXT', completenessStatus='DECLARED_NOT_ATTESTED',
                projectRef=project_ref, accountId=account_id, sourceEvidence=source_evidence,
                context=dict(firstMcpDate=first_mcp_date, existingDates=sorted(existing), verifiedAgainstDatabase=False),
                days=days, rowCount=sum(d['rowCount'] for d in days),
                duplicateRowsSkipped=duplicate_rows_skipped,
                duplicatePolicy='KEEP_LAST_FILE_ROW_PER_DAY_MATERIAL')


def write_preview(result, output_dir):
    """Exclusive directory and files: cannot replace any previous run or upload."""
    destination = Path(output_dir).resolve()
    allowed = [ROOT / '.local', ROOT / 'qa/runs']
    if not any(destination.is_relative_to(p.resolve()) and destination != p.resolve() for p in allowed):
        fail('OUTPUT_PATH', '输出仅允许项目 .local 或 qa/runs 下的新隔离目录')
    destination.mkdir(parents=True, exist_ok=False)
    payload = canonical_json(result).encode('utf-8')
    with (destination / 'preview.json').open('xb') as target:
        target.write(payload)
    readback = (destination / 'preview.json').read_bytes()
    if readback != payload:
        fail('READBACK', '预演输出回读不一致')
    return {'status': STATUS, 'sizeBytes': len(payload), 'sha256': hashlib.sha256(payload).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('--project-ref', required=True)
    parser.add_argument('--account-id', required=True)
    parser.add_argument('--first-mcp-date', required=True)
    parser.add_argument('--existing-date', action='append', default=[])
    args = parser.parse_args()
    try:
        result = convert_workbook(args.source, project_ref=args.project_ref, account_id=args.account_id,
                                  first_mcp_date=args.first_mcp_date, existing_dates=args.existing_date)
        destination = ROOT / '.local/history-upload-preview' / uuid.uuid4().hex
        receipt = write_preview(result, destination)
        print(json.dumps(dict(**receipt, output=str(destination)), ensure_ascii=False))
        return 0
    except ConversionError as exc:
        print(json.dumps({'status': 'REJECTED', 'diagnostic': exc.diagnostic}, ensure_ascii=False))
        return 2
    except OSError:
        print(json.dumps({'status': 'REJECTED', 'diagnostic': {'code': 'FILE_IO', 'message': '无法读取输入或写入隔离预演文件，请检查路径与权限'}}, ensure_ascii=False))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
