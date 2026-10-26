"""Synthetic OOXML fixtures only. No workbook authoring libraries or business data."""
from contextlib import redirect_stdout
import io
from decimal import Decimal
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile
from xml.sax.saxutils import escape

import convert_upload_template as c


def fixture(path, facts=None, days=None, additions=None, overrides=None, extra_sheet=False):
    headers = [s['header'] for s in c.CONTRACT['columns']]
    facts = facts if facts is not None else [['2026-01-02', '12345678901234567890', '测试素材', '', Decimal('0'), '', Decimal('0.0431')]]
    days = days if days is not None else [['2026-01-02', '整日完整']]
    sheet_rows = [[['说明仅供阅读'], [], [], ['模板版本',c.CONTRACT['schemaVersion']]], [headers]+facts, [c.CONTRACT['dayHeaders']]+days]
    names = list(c.CONTRACT['sheets'].values())
    if extra_sheet:
        names.append('未知页')
        sheet_rows.append([['未导入数据']])
    package = {
        '[Content_Types].xml': '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>',
        'xl/workbook.xml': f'<workbook xmlns="{c.NS[1:-1]}" xmlns:r="{c.REL[1:-1]}"><sheets>'+''.join(f'<sheet name="{n}" sheetId="{i}" r:id="rId{i}"/>' for i,n in enumerate(names,1))+'</sheets></workbook>',
        'xl/_rels/workbook.xml.rels': '<Relationships>'+''.join(f'<Relationship Id="rId{i}" Target="worksheets/sheet{i}.xml"/>' for i in range(1,len(names)+1))+'</Relationships>',
        'xl/styles.xml': f'<styleSheet xmlns="{c.NS[1:-1]}"><cellXfs><xf numFmtId="0"/><xf numFmtId="14"/></cellXfs></styleSheet>',
    }
    for i, rows in enumerate(sheet_rows,1):
        nodes = []
        for r, values in enumerate(rows,1):
            cells = []
            for col, value in enumerate(values,1):
                if value is None:
                    continue
                ref = c.col_name(col)+str(r)
                if isinstance(value, tuple):
                    kind, value = value
                    cells.append(f'<c r="{ref}" {"s=\"1\"" if kind == "date" else ""}>'+ (f'<f>{value}</f><v>123</v>' if kind == 'formula' else f'<v>{value}</v>')+'</c>')
                elif isinstance(value,(int,Decimal)):
                    cells.append(f'<c r="{ref}"><v>{value}</v></c>')
                else:
                    cells.append(f'<c r="{ref}" t="inlineStr"><is><t>{escape(value)}</t></is></c>')
            nodes.append('<row>'+''.join(cells)+'</row>')
        package[f'xl/worksheets/sheet{i}.xml'] = f'<worksheet xmlns="{c.NS[1:-1]}"><sheetData>'+''.join(nodes)+'</sheetData></worksheet>'
    package.update(additions or {})
    package.update(overrides or {})
    with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED) as z:
        for name,value in package.items():
            z.writestr(name,value)
    return path


class UploadTests(unittest.TestCase):
    def setUp(self):
        root = c.ROOT/'qa/runs'
        root.mkdir(exist_ok=True,parents=True)
        self.directory = tempfile.TemporaryDirectory(prefix='d085-unit-',dir=root)
        self.root = Path(self.directory.name)
        self.path = self.root/'synthetic.xlsx'

    def tearDown(self):
        self.directory.cleanup()

    def convert(self, **kwargs):
        options = dict(project_ref='synthetic_project', account_id='123456', first_mcp_date='2026-01-10')
        options.update(kwargs)
        return c.convert_workbook(self.path, **options)

    def reject(self, code, **kwargs):
        with self.assertRaises(c.ConversionError) as context:
            self.convert(**kwargs)
        self.assertEqual(code,context.exception.diagnostic['code'])
        return context.exception.diagnostic

    def test_success_precision_zero_unknown_long_id_and_nonimportable(self):
        fixture(self.path)
        result = self.convert()
        fact = result['days'][0]['facts'][0]
        self.assertEqual('12345678901234567890',fact['material_id'])
        self.assertEqual(Decimal(0),fact['stat_cost_for_roi2'])
        self.assertEqual('UNKNOWN',fact['total_prepay_and_pay_order_roi2'])
        self.assertEqual(Decimal('4.3100'),fact['live_cvr_rate_for_roi2_v2'])
        self.assertEqual('UNKNOWN',fact['roi2_material_video_type'])
        self.assertFalse(result['databaseImportEligible'])
        self.assertEqual(c.STATUS,result['status'])
        self.assertFalse(result['context']['verifiedAgainstDatabase'])

    def test_decimal_json_never_float(self):
        value = Decimal('12345678901234567890.12345678901234567890')
        fixture(self.path,facts=[['2026-01-02','1','',None,value]])
        encoded = c.canonical_json(self.convert())
        decoded = json.loads(encoded,parse_float=Decimal,parse_int=Decimal)
        self.assertEqual(value,decoded['days'][0]['totalSpend'])

    def test_empty_day_and_unlisted_days_not_generated(self):
        fixture(self.path, days=[['2026-01-02','整日完整'],['2026-01-04','当天无投放']])
        result = self.convert()
        self.assertEqual(['2026-01-02','2026-01-04'],[d['statDate'] for d in result['days']])
        self.assertEqual([],result['days'][1]['facts'])

    def test_no_mcp(self):
        fixture(self.path)
        result = self.convert(first_mcp_date=None)
        self.assertIsNone(result['context']['firstMcpDate'])
        self.assertEqual(1, result['rowCount'])

    def test_no_mcp_rejects_today_and_existing_dates(self):
        today = c.dt.datetime.now(c.ZoneInfo('Asia/Shanghai')).date().isoformat()
        fixture(self.path, facts=[[today, '1', '测试', '', Decimal('0')]], days=[[today, '整日完整']])
        self.reject('CUTOFF', first_mcp_date=None)
        fixture(self.path)
        self.reject('OVERLAP', first_mcp_date=None, existing_dates=['2026-01-02'])

    def test_incremental_hash_identical(self):
        facts = [{'a': Decimal('0.1234567890123456789'), 'b': '测试'}, {'a': None}]
        self.assertEqual(c.hashlib.sha256(c.canonical_json(facts).encode()).hexdigest(), c.facts_hash(facts))

    def test_instruction_after_version_still_validated(self):
        fixture(self.path)
        with zipfile.ZipFile(self.path) as z:
            package = {name: z.read(name) for name in z.namelist()}
        package['xl/worksheets/sheet1.xml'] = package['xl/worksheets/sheet1.xml'].replace(b'</sheetData>', b'<row><c r="A5"><f>1+1</f><v>2</v></c></row></sheetData>')
        with zipfile.ZipFile(self.path, 'w', zipfile.ZIP_DEFLATED) as z:
            for name, data in package.items(): z.writestr(name, data)
        self.reject('FORMULA')

    def test_many_formatted_empty_rows_stay_lazy(self):
        fixture(self.path)
        with zipfile.ZipFile(self.path) as z:
            package = {name: z.read(name) for name in z.namelist()}
        extra = ''.join('<row>' + ''.join(f'<c r="{c.col_name(col)}{row}" s="0"/>' for col in range(1, 30)) + '</row>' for row in range(3, 5003))
        package['xl/worksheets/sheet2.xml'] = package['xl/worksheets/sheet2.xml'].replace(b'</sheetData>', extra.encode() + b'</sheetData>')
        with zipfile.ZipFile(self.path, 'w', zipfile.ZIP_DEFLATED) as z:
            for name, data in package.items(): z.writestr(name, data)
        parsed, _ = c.read_package(self.path)
        sheets = c.read_sheets(parsed)
        self.assertIsInstance(sheets[c.CONTRACT['sheets']['facts']], c.SheetCells)
        self.assertEqual(1, self.convert()['rowCount'])

    def test_overlap(self):
        fixture(self.path)
        self.reject('OVERLAP',existing_dates=['2026-01-02'])

    def test_cutoff_equal_or_after(self):
        fixture(self.path)
        for cutoff in ['2026-01-02','2026-01-01']:
            self.reject('CUTOFF',first_mcp_date=cutoff)

    def test_duplicate_day(self):
        fixture(self.path,days=[['2026-01-02','整日完整']]*2)
        self.reject('DUPLICATE_DAY')

    def test_duplicate_material(self):
        fixture(self.path,facts=[['2026-01-02','1','',None,1]]*2)
        result = self.convert()
        self.assertEqual(1, result['rowCount'])
        self.assertEqual(1, result['duplicateRowsSkipped'])
        self.assertEqual(Decimal(1), result['days'][0]['totalSpend'])

    def test_duplicate_different_amount_keeps_last(self):
        fixture(self.path, facts=[['2026-01-02','1','',None,1], ['2026-01-02','1','',None,2]])
        result = self.convert()
        self.assertEqual(Decimal(2), result['days'][0]['totalSpend'])
        self.assertEqual(1, result['rowCount'])
        self.assertEqual(1, result['duplicateRowsSkipped'])

    def test_duplicate_unknown_is_not_zero(self):
        fixture(self.path, facts=[['2026-01-02','1','',None,1,''], ['2026-01-02','1','',None,1,0]])
        result = self.convert()
        self.assertEqual(Decimal(0), result['days'][0]['facts'][0]['total_prepay_and_pay_order_roi2'])

    def test_last_unknown_does_not_keep_earlier_number(self):
        fixture(self.path, facts=[['2026-01-02','1','old',None,8,10], ['2026-01-02','1','new',None,0,'']])
        result = self.convert()
        fact = result['days'][0]['facts'][0]
        self.assertEqual(Decimal(0), fact['stat_cost_for_roi2'])
        self.assertEqual('UNKNOWN', fact['total_prepay_and_pay_order_roi2'])
        self.assertEqual('new', fact['roi2_material_video_name'])

    def test_same_material_on_other_day_not_deduplicated(self):
        fixture(self.path, facts=[['2026-01-02','1','',None,1], ['2026-01-03','1','',None,1]], days=[])
        result = self.convert()
        self.assertEqual(2, result['rowCount'])
        self.assertEqual(0, result['duplicateRowsSkipped'])

    def test_empty_conflict(self):
        fixture(self.path,days=[['2026-01-02','当天无投放']])
        self.reject('EMPTY_CONFLICT')

    def test_complete_without_rows(self):
        fixture(self.path,facts=[])
        self.reject('COMPLETE_WITHOUT_ROWS')

    def test_inferred_dates_and_explicit_empty_day(self):
        fixture(self.path,days=[['2026-01-03','当天无投放']])
        self.assertEqual(['2026-01-02','2026-01-03'],[d['statDate'] for d in self.convert()['days']])

    def test_optional_sheet_content_deduplicates_dates(self):
        fixture(self.path,days=[],facts=[['2026-01-02',str(i),'',None,1] for i in range(100)])
        result=self.convert()
        self.assertEqual(1,len(result['days']))
        self.assertEqual(100,result['days'][0]['rowCount'])
        self.assertFalse(result['databaseImportEligible'])

    def test_inferred_dates_keep_cutoff_and_overlap_checks(self):
        fixture(self.path,days=[])
        self.reject('CUTOFF',first_mcp_date='2026-01-02')
        self.reject('OVERLAP',existing_dates=['2026-01-02'])

    def test_inferred_days_do_not_fill_gaps(self):
        fixture(self.path,days=[],facts=[['2026-01-02','1','',None,0],['2026-01-04','2','',None,1]])
        self.assertEqual(['2026-01-02','2026-01-04'],[d['statDate'] for d in self.convert()['days']])

    def test_inferred_day_limit(self):
        fixture(self.path,days=[],facts=[['2026-01-02','1','',None,0],['2026-01-04','2','',None,1]])
        with patch.dict(c.CONTRACT['limits'],maxDays=1):
            self.reject('ROW_LIMIT')

    def test_numeric_id_location(self):
        fixture(self.path,facts=[['2026-01-02',123,'',None,1]])
        error = self.reject('ID_TEXT')
        self.assertEqual(('素材数据',2,'B'),(error['sheet'],error['row'],error['column']))

    def test_formula_and_cached_value_rejected(self):
        fixture(self.path,facts=[['2026-01-02','1','',None,('formula','1+1')]])
        self.reject('FORMULA')

    def test_header_and_extra_columns(self):
        fixture(self.path,facts=[['2026-01-02','1','',None,1]+[None]*27+['extra']])
        self.reject('EXTRA_COLUMN')
        fixture(self.path,overrides={'xl/worksheets/sheet2.xml':f'<worksheet xmlns="{c.NS[1:-1]}"/>'})
        self.reject('HEADER')

    def test_unknown_sheet(self):
        fixture(self.path,extra_sheet=True)
        self.reject('SHEETS')

    def test_excel_date_and_strict_date(self):
        fixture(self.path,facts=[[('date',46024),'1','',None,1]])
        self.assertEqual('2026-01-02',self.convert()['days'][0]['statDate'])
        fixture(self.path,facts=[['2026-1-2','1','',None,1]])
        self.reject('DATE')

    def test_creation_timestamp_takes_date(self):
        for value in [('date', Decimal('46023.99999999')), '2026-01-01 23:59:59', '2026-01-01T09:30:03.123']:
            with self.subTest(value=value):
                fixture(self.path, facts=[['2026-01-02','1','',value,1]], days=[])
                self.assertEqual('2026-01-01', self.convert()['days'][0]['facts'][0]['material_create_time_v2'])

    def test_stat_and_declaration_timestamp_still_rejected(self):
        for value in [('date', Decimal('46024.5')), '2026-01-02 12:00:00']:
            fixture(self.path, facts=[[value,'1','',None,1]], days=[])
            self.reject('DATE')
            fixture(self.path, days=[[value,'整日完整']])
            self.reject('DATE')

    def test_creation_invalid_times_and_excel_epochs(self):
        for value in [('date', Decimal('60.5')), ('date', Decimal('-0.5')), '2026-02-30 12:00:00', '2026-01-01 25:00:00', '2026-01-01T12:00:00+08:00']:
            fixture(self.path, facts=[['2026-01-02','1','',value,1]])
            self.reject('DATE')
        self.assertEqual('1904-01-01', c.date_value(Decimal('0.75'), ('素材数据',2,'D'), True, True, True))

    def test_repeated_headers_skip_variants_keep_template_order(self):
        actual = ['2026-01-02','1','测试素材',None,Decimal('12.34')]
        for day_label in [None,'','统计日期','文件日期','日期','期日','2026-01-02',('date',46024)]:
            header = [day_label,'素材id','全域素材视频名称','素材创建时间','整体消耗','净成交ROI']
            fixture(self.path,facts=[header,actual,header],days=[])
            result=self.convert()
            self.assertEqual(1,result['rowCount'])
            self.assertEqual(Decimal('12.34'),result['days'][0]['totalSpend'])
        fixture(self.path,facts=[[s['header'] for s in c.CONTRACT['columns']],actual],days=[])
        self.assertEqual(1,self.convert()['rowCount'])

    def test_bad_data_is_not_silently_skipped_as_header(self):
        for row in [['日期','123','名称',None,1], ['日期','素材ID','素材名称',None,1],
                    ['日期','素材ID','素材名称','2026-01-01','整体消耗']]:
            fixture(self.path,facts=[row],days=[])
            self.reject('DATE')
        fixture(self.path,facts=[[s['header'] for s in c.CONTRACT['columns']]],days=[])
        self.reject('NO_DAYS')

    def test_manual_all_creation_rows_are_excluded_not_unknown(self):
        fixture(self.path,facts=[['2026-01-02','1','普通素材','全部',999],
                                ['2026-01-02','2','全部',None,Decimal('12.34')]],days=[])
        result=self.convert()
        self.assertEqual(1,result['rowCount'])
        self.assertEqual(Decimal('12.34'),result['days'][0]['totalSpend'])
        self.assertEqual('2',result['days'][0]['facts'][0]['material_id'])
        fixture(self.path,facts=[['2026-01-02','1','普通素材','全部',999]],days=[])
        self.reject('NO_DAYS')
        fixture(self.path,facts=[['2026-01-02','1','普通素材','全部未知',999]],days=[])
        self.reject('DATE')

    def test_numeric_names_remain_display_text_without_dropping_facts(self):
        for name in [Decimal('7.67044E+18'), Decimal('0'), Decimal('12.50')]:
            fixture(self.path,facts=[['2026-01-02','123',name,None,Decimal('12.34')]],days=[])
            result=self.convert()
            self.assertEqual(1,result['rowCount'])
            self.assertEqual(str(name),result['days'][0]['facts'][0]['roi2_material_video_name'])
            self.assertEqual(Decimal('12.34'),result['days'][0]['totalSpend'])
        fixture(self.path,facts=[['2026-01-02',Decimal('123'),Decimal('7.67044E+18'),None,1]],days=[])
        self.reject('ID_TEXT')

    def test_aigc_exact_sentinel(self):
        fixture(self.path,facts=[['2026-01-02','-','AIGC动态创意素材集合','-',1]])
        self.assertEqual('UNKNOWN',self.convert()['days'][0]['facts'][0]['material_create_time_v2'])
        fixture(self.path,facts=[['2026-01-02','1','普通素材','-',1]])
        result = self.convert()
        self.assertEqual('UNKNOWN', result['days'][0]['facts'][0]['material_create_time_v2'])
        self.assertEqual(Decimal(1), result['days'][0]['totalSpend'])
        self.assertEqual(1, result['rowCount'])
        fixture(self.path,facts=[['-','1','普通素材','-',1]],days=[])
        self.reject('DATE')
        fixture(self.path,facts=[['2026-01-02','1','普通素材','2026-02-30',1]])
        self.reject('DATE')
        fixture(self.path,facts=[['2026-01-02','-','AIGC动态创意素材集合',None,1]])
        self.assertEqual('-2',self.convert()['days'][0]['facts'][0]['material_id'])
        fixture(self.path,facts=[['2026-01-02','-','其他',None,1]])
        self.reject('ID_TEXT')
        fixture(self.path,facts=[['2026-01-02','-2','其他',None,1]])
        self.reject('ID_TEXT')

    def test_template_version(self):
        fixture(self.path,overrides={'xl/worksheets/sheet1.xml': f'<worksheet xmlns="{c.NS[1:-1]}"/>'})
        self.reject('TEMPLATE_VERSION')

    def test_generated_template_structure(self):
        path = c.ROOT/'backend/src/main/resources/templates/manual-history.xlsx'
        if not path.exists():
            self.skipTest('最终模板尚未生成')
        with self.assertRaises(c.ConversionError) as error:
            c.convert_workbook(path,project_ref='synthetic',account_id='123',first_mcp_date='2026-01-10')
        self.assertEqual('NO_DAYS',error.exception.diagnostic['code'])

    def test_utf16_and_unsupported_encoding_rejected(self):
        fixture(self.path, additions={'xl/bad.xml':'<?xml version="1.0" encoding="UTF-16"?><!DOCTYPE x><x/>'.encode('utf-16')})
        self.reject('XML_ENCODING')
        fixture(self.path, additions={'xl/bad.xml':'<?xml version="1.0" encoding="ISO-8859-1"?><x/>'})
        self.reject('XML_ENCODING')

    def test_numeric_token_length(self):
        fixture(self.path,facts=[['2026-01-02','1','',None,Decimal('0.'+'1'*4097)]])
        self.reject('TEXT_LIMIT')

    def test_long_cell(self):
        fixture(self.path,facts=[['2026-01-02','1','x'*4097,None,1]])
        self.reject('TEXT_LIMIT')

    def test_worksheet_hyperlinks_are_ignored_without_changing_values(self):
        fixture(self.path)
        expected = self.convert()['days']
        rel = '<Relationships><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" TargetMode="External" Target="https://example.invalid/private"/></Relationships>'
        fixture(self.path, additions={'xl/worksheets/_rels/sheet2.xml.rels':rel})
        result = self.convert()
        self.assertEqual(expected, result['days'])
        self.assertNotIn('example.invalid', c.canonical_json(result))

    def test_hyperlink_exception_does_not_allow_other_external_relationships(self):
        prefix = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships/'
        for path, kind in [('xl/worksheets/_rels/sheet2.xml.rels','externalLink'), ('xl/worksheets/_rels/sheet2.xml.rels','image'), ('xl/_rels/workbook.xml.rels','hyperlink')]:
            rel = f'<Relationships><Relationship Type="{prefix}{kind}" TargetMode="External" Target="https://example.invalid"/></Relationships>'
            fixture(self.path, additions={path:rel})
            self.reject('EXTERNAL_LINK')

    def test_zip_paths_macro_external_and_entity(self):
        for addition,code in [({'../bad.xml':'<x/>'},'ZIP_PATH'),({'xl/vbaProject.bin':'bad'},'ACTIVE_CONTENT'),
                              ({'xl/test.rels':'<Relationships><Relationship TargetMode="External" Target="https://example.invalid"/></Relationships>'},'EXTERNAL_LINK'),
                              ({'xl/bad.xml':'<!DOCTYPE x><x/>'},'XML_ENTITY')]:
            fixture(self.path,additions=addition)
            self.reject(code)

    def test_limits(self):
        fixture(self.path)
        for key,code in [('maxZipEntries','ZIP_ENTRY_LIMIT'),('maxExpandedBytes','ZIP_SIZE_LIMIT'),('maxFileBytes','FILE_LIMIT'),('maxFactRows','ROW_LIMIT'),('maxDays','ROW_LIMIT')]:
            with patch.dict(c.CONTRACT['limits'],{key:0}):
                self.reject(code)

    def test_missing_required_and_bad_number(self):
        fixture(self.path,facts=[['2026-01-02','1']])
        self.reject('REQUIRED')
        fixture(self.path,facts=[['2026-01-02','1','',None,'12.2']])
        self.reject('NUMBER')

    def test_project_account_bound_not_filename(self):
        fixture(self.path)
        result = self.convert(project_ref='second',account_id='987654321')
        self.assertEqual('second',result['projectRef'])
        self.assertEqual('987654321',result['accountId'])

    def test_output_exclusive(self):
        fixture(self.path)
        result = self.convert()
        dest = self.root/'output'
        c.write_preview(result,dest)
        before = (dest/'preview.json').read_bytes()
        with self.assertRaises(FileExistsError):
            c.write_preview(result,dest)
        self.assertEqual(before,(dest/'preview.json').read_bytes())

    def test_cli_io_error_is_sanitized(self):
        output = io.StringIO()
        with patch('sys.argv',['converter',str(self.root/'missing-private-name.xlsx'),'--project-ref','synthetic','--account-id','123','--first-mcp-date','2026-01-10']), redirect_stdout(output):
            self.assertEqual(2,c.main())
        payload = output.getvalue()
        self.assertEqual('FILE_IO',json.loads(payload)['diagnostic']['code'])
        self.assertNotIn('missing-private-name',payload)


if __name__ == '__main__':
    unittest.main()
