"""Synthetic runtime encoder regressions; no business data or database access."""
from copy import deepcopy
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import tempfile
import subprocess
import sys
import unittest
import xml.etree.ElementTree as ET
import zipfile
import export_saved_data as export
import convert_upload_template as upload


def payload():
    row = {c['field']: '4.31' if c['kind'] == 'percent' else '12.34'
           for c in export.CONTRACT['columns'] if c['kind'] in ('number', 'percent')}
    row.update(stat_time_day='2026-10-12', material_id='9999999999999900081',
               roi2_material_video_name='=literal-title', material_create_time_v2='2026-03-01 12:30:00',
               stat_cost_for_roi2='0', video_like_count_for_roi2='UNKNOWN')
    return dict(projectName='合成项目', accountId='1000000000000001', startDate='2026-10-11',
                endDate='2026-10-13', rows=[row],
                days=[dict(statDate='2026-10-12', rowCount=1, sourceType='MANUAL_HISTORY'),
                      dict(statDate='2026-10-13', rowCount=0, sourceType='MCP_AUTO')],
                excludedDates=[dict(statDate='2026-10-11', reason='MISSING')])


class ExportTest(unittest.TestCase):
    def setUp(self):
        runs = export.ROOT / 'qa/runs/d118-tests'
        runs.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=runs)
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name) / 'synthetic.xlsx'

    def test_saved_package_and_upload_roundtrip_all_columns(self):
        template = export.ROOT / 'backend/src/main/resources/templates/manual-history.xlsx'
        original = hashlib.sha256(template.read_bytes()).hexdigest()
        data = payload()
        export.build(data, self.output)
        parsed = upload.convert_workbook(self.output, project_ref='synthetic', account_id=data['accountId'], first_mcp_date='2026-10-14')
        facts = parsed['days'][0]['facts'][0]
        for column in export.CONTRACT['columns']:
            name, kind = column['field'], column['kind']
            expected = data['rows'][0][name]
            if kind == 'date':
                expected = expected[:10]
            elif kind in ('number', 'percent') and expected != 'UNKNOWN':
                expected = Decimal(expected)
            self.assertEqual(facts[name], expected, name)
        self.assertEqual(parsed['days'][1]['rowCount'], 0)
        self.assertEqual(hashlib.sha256(template.read_bytes()).hexdigest(), original)
        with zipfile.ZipFile(self.output) as result, zipfile.ZipFile(template) as source:
            self.assertEqual(set(result.namelist()), set(source.namelist()))
            for name in result.namelist():
                if name not in ('xl/styles.xml', 'xl/worksheets/sheet1.xml', 'xl/worksheets/sheet2.xml', 'xl/worksheets/sheet3.xml'):
                    self.assertEqual(result.read(name), source.read(name))
            for name in export.sheet_paths(result).values():
                self.assertEqual(len(ET.fromstring(result.read(name)).findall('.//' + export.tag('f'))), 0)
            guide = upload.read_sheets(upload.read_package(self.output)[0])['填表说明']
            self.assertEqual(guide['B24'][0], '人工历史数据')
            self.assertEqual(guide['B25'][0], '机器采集数据')

    def test_dates_and_precision(self):
        self.assertEqual(export.excel_day('2026-10-11T20:00:00Z'), export.excel_day('2026-10-12'))
        self.assertEqual(export.excel_day('1900-03-01'), 61)
        self.assertEqual(export.excel_day('1900-02-28'), 59)
        data = payload()
        data['rows'][0]['stat_cost_for_roi2'] = '123456789.123456789'
        percent = next(c['field'] for c in export.CONTRACT['columns'] if c['kind'] == 'percent')
        data['rows'][0][percent] = '0.000123456789123456789'
        projected = next(export.project_rows(data))
        self.assertEqual(projected[4], Decimal('123456789.123456789'))
        self.assertEqual(projected[6], Decimal('0.00000123456789123456789'))
        self.assertIsNone(projected[14])

    def test_invalid_rows_fail_without_file(self):
        for field, value in [('material_id', 123), ('stat_cost_for_roi2', 'UNKNOWN'),
                             ('stat_cost_for_roi2', 'NaN'), ('stat_cost_for_roi2', True),
                             ('material_create_time_v2', '2026-02-30')]:
            with self.subTest(field=field, value=value):
                data = payload()
                data['rows'][0][field] = value
                with self.assertRaises(ValueError):
                    export.build(data, self.output)
                self.assertFalse(self.output.exists())

    def test_duplicate_and_counts(self):
        for mode in ('duplicate', 'count', 'unknown-day', 'duplicate-day'):
            data = payload()
            if mode == 'duplicate':
                data['rows'].append(deepcopy(data['rows'][0]))
            elif mode == 'count':
                data['days'][0]['rowCount'] = 2
            elif mode == 'unknown-day':
                data['rows'][0]['stat_time_day'] = '2026-10-11'
            else:
                data['days'].append(data['days'][0])
            with self.assertRaises(ValueError):
                list(export.project_rows(data))

    def test_empty_success_day_and_no_overwrite(self):
        data = payload()
        data['rows'] = []
        data['days'] = data['days'][1:]
        export.build(data, self.output)
        with self.assertRaises(FileExistsError):
            export.build(data, self.output)
        parsed = upload.convert_workbook(self.output, project_ref='synthetic', account_id=data['accountId'], first_mcp_date='2026-10-14')
        self.assertEqual(parsed['rowCount'], 0)

    def test_independent_excel_reader_and_styles(self):
        # Optional independent reader is for QA only, never a runtime dependency.
        import openpyxl
        export.build(payload(), self.output)
        book = openpyxl.load_workbook(self.output)
        self.addCleanup(book.close)
        sheet = book['素材数据']
        self.assertEqual([c.value for c in sheet[1]], [c['header'] for c in export.CONTRACT['columns']])
        self.assertEqual(sheet['B2'].value, '9999999999999900081')
        self.assertEqual(sheet['C2'].value, '=literal-title')
        self.assertEqual(sheet['C2'].data_type, 's')
        self.assertEqual(sheet['A2'].value.date().isoformat(), '2026-10-12')
        self.assertEqual(sheet['G2'].number_format, '0.00%')
        self.assertEqual(sheet['E2'].number_format, '0.00########')
        self.assertEqual(sheet['G2'].value, 0.0431)
        self.assertEqual(sheet['E2'].value, 0)
        self.assertIsNone(sheet['O2'].value)
        self.assertEqual(sheet.freeze_panes, 'C2')

    def test_no_site_packages_required(self):
        source = Path(self.temp.name) / 'synthetic.json'
        source.write_text(json.dumps(payload()), encoding='utf-8')
        code = ('import json,sys; from pathlib import Path; '
                'sys.path.insert(0,sys.argv[1]); from export_saved_data import build; '
                'build(json.loads(Path(sys.argv[2]).read_text()),Path(sys.argv[3]))')
        result = subprocess.run([sys.executable, '-S', '-B', '-c', code,
                                 str(Path(export.__file__).parent), str(source), str(self.output)],
                                capture_output=True, timeout=30)
        self.assertEqual(result.returncode, 0)
        self.assertTrue(zipfile.is_zipfile(self.output))


if __name__ == '__main__':
    unittest.main()
