"""Reuse frozen backup transaction failure tests against target adaptation."""
import importlib.util,sys,unittest
from pathlib import Path
import weekly_database_backup_titania as target
p=Path(__file__).with_name('test_weekly_database_backup.py')
original=sys.modules.get('weekly_database_backup');sys.modules['weekly_database_backup']=target
try:
 spec=importlib.util.spec_from_file_location('d221_original_tests',p);tests=importlib.util.module_from_spec(spec)
 source=p.read_text(encoding='utf8').replace('business.sql', 'business.sql.gz').replace('set(current), backup.MANIFEST_KEYS', "set(current), backup.MANIFEST_KEYS | {'compression','sqlBytes','sqlSha256'}")
 exec(compile(source,str(p),'exec'),tests.__dict__)
finally:
 if original is None:sys.modules.pop('weekly_database_backup',None)
 else:sys.modules['weekly_database_backup']=original
WorkerTests=tests.WorkerTests
class CompressionTests(unittest.TestCase):
 setUp=tests.WorkerTests.setUp
 tearDown=tests.WorkerTests.tearDown
 run_ok=tests.WorkerTests.run_ok
 base=tests.WorkerTests.base
 def test_gzip_roundtrip_and_both_hashes(self):
  import gzip,hashlib
  current=self.run_ok();path=target.generation_directory(self.root,current)/target.artifact_name(current)
  self.assertEqual(gzip.decompress(path.read_bytes()),tests.SQL)
  self.assertEqual(current['sqlSha256'],hashlib.sha256(tests.SQL).hexdigest())
  self.assertEqual(current['sqlBytes'],len(tests.SQL))
  self.assertEqual(current['sha256'],hashlib.sha256(path.read_bytes()).hexdigest())
  self.assertEqual(current['schemaVersion'],2)
 def test_legacy_sql_is_retired_only_after_gzip_verified(self):
  import hashlib
  current=self.run_ok();directory=target.generation_directory(self.root,current)
  (directory/target.artifact_name(current)).unlink()
  legacy={k:v for k,v in current.items() if k in target.MANIFEST_KEYS}
  legacy.update(schemaVersion=1,bytes=len(tests.SQL),sha256=hashlib.sha256(tests.SQL).hexdigest())
  (directory/'business.sql').write_bytes(tests.SQL)
  target.atomic_json(self.root,directory/'manifest.json',legacy)
  target.atomic_json(self.root,self.base/'current.json',legacy)
  self.assertEqual(target.read_current(self.root),legacy)
  from unittest.mock import patch
  with patch.object(target,'verify_compressed_sql',side_effect=target.Rejected('DUMP_READBACK_FAILED')):
   with self.assertRaises(target.Rejected):self.run_ok()
  self.assertEqual(target.read_current(self.root),legacy)
  self.assertTrue((directory/'business.sql').exists())
  self.run_ok();self.assertFalse(directory.exists())
 def test_truncated_compressed_stream_rejected(self):
  current=self.run_ok();path=target.generation_directory(self.root,current)/target.artifact_name(current)
  path.write_bytes(path.read_bytes()[:-5])
  with self.assertRaises(target.Rejected):target.verify_compressed_sql(self.root,path,current['sqlBytes'],current['sqlSha256'])
class TargetFooterTests(unittest.TestCase):
    def test_mysql84_space_padded_morning_hour(self):
        self.assertIsNotNone(target.FOOTER.search(b'-- Dump completed on 2026-10-22  9:56:46\n'))
        self.assertIsNotNone(target.FOOTER.search(b'-- Dump completed on 2026-10-25 23:00:01\n'))
        self.assertIsNone(target.FOOTER.search(b'-- Dump completed on 2026-10-22  9:56:46\ntruncated'))
if __name__=='__main__':unittest.main()
