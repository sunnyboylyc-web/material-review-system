"""Offline portability checks; never start a client or connect to a database."""
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch
import prepare_project_day as prepare


class PortableClientTest(unittest.TestCase):
    def test_cloud_schema_is_explicit_and_read_only(self):
        with patch.object(prepare, 'mysql_runtime', return_value=(Path('mysql'), Path('private.ini'))), \
             patch.object(prepare.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, b'facts')) as run:
            prepare.fingerprint(Path.cwd(), '12345', '2026-10-15', schema='SCSJ_LOCAL_CLOUD')
            query = run.call_args.args[0][-1]
            self.assertIn('FROM SCSJ_LOCAL_CLOUD.SCSJ_MATERIAL_DAY_FACT', query)
            self.assertNotIn('SCSJ_LOCAL_FACTS.', query)
            self.assertTrue(query.startswith('SET SESSION TRANSACTION READ ONLY;'))
            self.assertTrue(query.endswith('ROLLBACK;'))

    def test_unknown_or_injected_schema_never_launches_client(self):
        with patch.object(prepare.subprocess, 'run') as run:
            for schema in ('mysql', 'SCSJ_LOCAL_CLOUD; DROP DATABASE mysql', '', None):
                with self.assertRaises(Exception):
                    prepare.fingerprint(Path.cwd(), '12345', '2026-10-15', schema=schema)
            run.assert_not_called()

    def test_read_only_command_uses_explicit_paths(self):
        root = Path(__file__).resolve().parents[2]
        client = root / 'synthetic/mysql'
        defaults = root / '.local/synthetic/client.ini'
        with patch.object(prepare, 'mysql_runtime', return_value=(client, defaults)) as runtime, \
             patch.object(prepare.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, b'facts')) as run:
            self.assertEqual(prepare.fingerprint(root, '12345', '2026-10-13', 'mysql', str(defaults)),
                             prepare.collect.sha(b'facts'))
            runtime.assert_called_once_with(root, 'mysql', str(defaults))
            command = run.call_args.args[0]
            self.assertEqual(command[0], str(client))
            self.assertEqual(command[1], '--defaults-extra-file=' + str(defaults))
            self.assertIn('SET SESSION TRANSACTION READ ONLY;', command[-1])
            self.assertTrue(command[-1].endswith('ROLLBACK;'))
            self.assertEqual(run.call_args.kwargs['cwd'], root)

    def test_unpaired_settings_rejected(self):
        with self.assertRaises(Exception):
            prepare.mysql_runtime(Path.cwd(), 'mysql', None)

    def test_missing_client_rejected(self):
        with patch.object(prepare.shutil, 'which', return_value=None):
            with self.assertRaises(Exception):
                prepare.mysql_runtime(Path.cwd(), 'missing-mysql', '.local/client.ini')

    def test_failed_client_does_not_return_success_digest(self):
        with patch.object(prepare, 'mysql_runtime', return_value=(Path('mysql'), Path('client.ini'))), \
             patch.object(prepare.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, b'partial')):
            with self.assertRaises(Exception):
                prepare.fingerprint(Path.cwd(), '12345', '2026-10-13')

    def test_invalid_identity_never_launches_client(self):
        with patch.object(prepare.subprocess, 'run') as run:
            with self.assertRaises(Exception):
                prepare.fingerprint(Path.cwd(), "123'", '2026-10-13')
            run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
