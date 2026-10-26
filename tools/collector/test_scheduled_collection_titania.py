"""Reuse scheduler safety tests against the Titania candidate, plus target slot checks."""
import sys,unittest,datetime as dt
from pathlib import Path
sys.path.insert(0,str(Path(__file__).parent))
import scheduled_collection_titania as candidate
import importlib.util
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('_titania_regression',Path(__file__).with_name('test_scheduled_collection.py'))
existing=importlib.util.module_from_spec(spec)
with patch.dict(sys.modules,{'scheduled_collection':candidate}):spec.loader.exec_module(existing)
omit={'test_slot_is_shanghai_yesterday_no_catchup','test_oneoff_slot_retries_failed_but_skips_verified','test_1420_skips_every_verified_project_without_collection','test_1420_has_new_identity_and_deduplicates_until_142459','test_backup_failure_no_import_other_project_attempted','test_database_check_scoped_exact_account_day_three_tables','test_persistent_backup_once_per_day_and_changed_dump_rejected','test_credential_init_failure_replaces_previous_success_without_secret'}
class TargetTests(unittest.TestCase):
 def test_target_slots_and_no_old_oneoffs(self):
  for hour,minute in [(8,0),(8,30),(9,0),(9,30),(10,0),(10,30),(11,0),(11,30),(12,0)]:
   day,point=candidate.slot(dt.datetime(2026,10,22,hour,minute,tzinfo=dt.timezone(dt.timedelta(hours=8))))
   self.assertEqual(day,'2026-10-21')
  for hour,minute in [(8,59),(11,29),(11,35),(14,20)]:
   with self.assertRaises(candidate.Rejected):candidate.slot(dt.datetime(2026,10,22,hour,minute,tzinfo=dt.timezone(dt.timedelta(hours=8))))
 def test_parameterized_database_probe_uses_only_owned_tables(self):
  from unittest.mock import patch,MagicMock
  import types,tempfile
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp);creds=root/'.local/d218-runtime/db-credentials.json';creds.parent.mkdir(parents=True)
   import json
   creds.write_text(json.dumps({'username':'scsj_titania','password':'synthetic-only','port':3306}))
   ops=object.__new__(candidate.LinuxOps);ops.root=root
   cursor=MagicMock();cursor.fetchone.side_effect=[(1,),(0,0)];cursor.fetchall.return_value=[(5,0,"CURRENT","123","2026-10-21")]
   db=MagicMock();db.__enter__.return_value=db;db.cursor.return_value.__enter__.return_value=cursor
   connect=MagicMock(return_value=db)
   with patch.dict(sys.modules,{'pymysql':types.SimpleNamespace(connect=connect)}):
    self.assertTrue(ops.exists({'advertiserId':'123'},'2026-10-21'))
   query,values=cursor.execute.call_args_list[0].args
   self.assertEqual(values,('123','2026-10-21')*3)
   self.assertNotIn('123',query);self.assertEqual(query.count('account_id=%s'),3)
   for table in candidate.TABLES:self.assertIn('`'+table+'`',query)
   self.assertEqual(connect.call_args.kwargs['database'],'material_dashboard')
 def test_owned_schema_and_tables(self):
  self.assertEqual(candidate.SCHEMA,'material_dashboard');self.assertEqual(len(candidate.TABLES),3)
  self.assertTrue(all(t.startswith('scsj_') and t==t.lower() for t in candidate.TABLES))
def suite():
 out=unittest.TestSuite()
 for cls in vars(existing).values():
  if isinstance(cls,type) and issubclass(cls,unittest.TestCase):
   for name in unittest.defaultTestLoader.getTestCaseNames(cls):
    if name not in omit:out.addTest(cls(name))
 out.addTests(unittest.defaultTestLoader.loadTestsFromTestCase(TargetTests));return out
if __name__=='__main__':sys.exit(not unittest.TextTestRunner(verbosity=1).run(suite()).wasSuccessful())
