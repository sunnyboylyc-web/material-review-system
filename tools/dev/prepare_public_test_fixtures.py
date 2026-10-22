"""Generate a synthetic workbook locally; no production data or database access."""
from pathlib import Path
import sys
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/manual_history'))
from test_convert_upload_template import fixture
target = ROOT / 'qa/runs/d085-template/synthetic-filled.xlsx'
target.parent.mkdir(parents=True, exist_ok=True)
if not target.exists():
    fixture(target, facts=[
        ['2026-01-01', '999999999999990001', '合成素材一', '', 10],
        ['2026-01-03', '999999999999990002', '合成素材二', '', 20],
    ], days=[['2026-01-01', '整日完整'], ['2026-01-03', '整日完整']])
print('SYNTHETIC_FIXTURE_READY')
