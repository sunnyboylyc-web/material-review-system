import datetime as dt
import gzip
import io
import unittest
from unittest.mock import patch
import period_database_backup_titania as p


class PeriodTests(unittest.TestCase):
    def test_dynamic_seven_days_across_month_and_year(self):
        self.assertEqual(p.dates(today=dt.date(2027,1,3)), ('2026-12-27','2027-01-02'))
        self.assertEqual(p.dates(today=dt.date(2026,10,25)), ('2026-10-18','2026-10-24'))

    def test_invalid_and_future_dates_rejected(self):
        for a,b in [('2026-02-30','2026-03-01'),('2026-10-22','2026-10-22'),('2026-10-21','2026-10-20'),('2026-1-01','2026-02-01'),('2000-01-01',None),("2026-01-01';DROP",'2026-02-01')]:
            with self.subTest(a=a), self.assertRaises(p.worker.Rejected): p.dates(a,b,today=dt.date(2026,10,22))
        self.assertEqual(p.dates('2024-03-31','2026-10-21',today=dt.date(2026,10,22)),('2024-03-31','2026-10-21'))

    def test_lossless_sql_literals(self):
        value='中文\\\n\x00\x1a\"\'😀'
        self.assertEqual(bytes.fromhex(p.literal(value)[2:-1]).decode(), value)
        self.assertEqual(p.literal(None),'NULL')
        self.assertEqual(p.literal(dt.datetime(2026,10,22,12,1,2,123456)),"'2026-10-22 12:01:02.123456'")
        with self.assertRaises(p.worker.Rejected): p.literal(float('nan'))

    def test_snapshot_streamed_twice_and_date_filter_not_applied_to_schema_history(self):
        db = FakeDB(); output=io.BytesIO()
        result=p.export_snapshot(db,output,'2026-10-01','2026-10-07')
        text=output.getvalue().decode()
        self.assertEqual(len(result),4); self.assertTrue(db.rolled_back)
        self.assertIn('WITH CONSISTENT SNAPSHOT, READ ONLY', '\n'.join(q for q,a in db.queries))
        selects=[(q,a) for q,a in db.queries if q.startswith('SELECT *')]
        self.assertEqual(len(selects),8)
        self.assertEqual(sum('BETWEEN %s AND %s' in q for q,a in selects),6)
        self.assertTrue(all(a==('2026-10-01','2026-10-07') for q,a in selects if 'BETWEEN' in q))
        self.assertNotIn('DROP TABLE',text); self.assertNotIn('DELETE FROM',text)
        self.assertEqual(text.count('INSERT INTO'),4)
        self.assertRegex(text,r'-- Dump completed on')

    def test_dangling_reference_refuses_dump(self):
        db=FakeDB(broken=True); output=io.BytesIO()
        with self.assertRaises(p.worker.Rejected): p.export_snapshot(db,output,'2026-10-01','2026-10-07')
        self.assertEqual(output.getvalue(), b'')

    def test_second_pass_mismatch_refuses_success_footer(self):
        db=FakeDB(changing=True); output=io.BytesIO()
        with self.assertRaises(p.worker.Rejected): p.export_snapshot(db,output,'2026-10-01','2026-10-07')
        self.assertNotIn(b'Dump completed',output.getvalue())


class FakeDB:
    def __init__(self,broken=False,changing=False): self.queries=[]; self.rolled_back=False; self.broken=broken; self.changing=changing; self.passes=0
    def cursor(self,*args): return FakeCursor(self)
    def rollback(self): self.rolled_back=True
class FakeCursor:
    description=[('id',),('raw_fact_json',)]
    def __init__(self,db): self.db=db; self.query=''
    def __enter__(self): return self
    def __exit__(self,*args): pass
    def execute(self,q,args=()):
        self.query=q; self.db.queries.append((q,args))
        if q.startswith('SELECT *'): self.db.passes+=1
    def fetchall(self): return [(t,'InnoDB') for t in p.worker.TABLES]
    def fetchone(self):
        if self.query.startswith('SHOW'): return ('table','CREATE TABLE '+self.query[18:]+' (`id` int, `raw_fact_json` text)')
        return (1 if self.db.broken else 0,)
    def __iter__(self): return iter([(2 if self.db.changing and self.db.passes==2 else 1,'{"value":null,"中文":0}')])

if __name__=='__main__': unittest.main()
