"""Synthetic SQL only, under qa/runs; never opens deployed data or credentials."""
import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import weekly_database_backup as backup


BASE = Path(__file__).resolve().parents[2]
TEST_ROOT = BASE / 'qa/runs/d187-worker-synthetic'
SQL = b'-- synthetic fixture; no real data\n-- Dump completed on 2026-10-18 23:00:00\n'


class WorkerTests(unittest.TestCase):
    def setUp(self):
        TEST_ROOT.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=TEST_ROOT)
        self.root = Path(self.temp.name).absolute()
        for relative in ('.local', '.local/collection-scheduler', backup.LEGACY_ROOT, backup.STORE):
            (self.root / relative).mkdir(mode=0o700)
        (self.root / '.local/collection-scheduler/runner.lock').touch(mode=0o600)
        self.legacy_files = []
        for index, day in enumerate(backup.LEGACY_DATES):
            directory = self.root / backup.LEGACY_ROOT / day
            directory.mkdir(mode=0o700)
            filename = f'{index:032x}.sql'
            content = SQL + b'-- old fixture ' + str(index).encode()
            path = directory / filename
            path.write_bytes(content)
            checksum = hashlib.sha256(content).hexdigest()
            marker = {'file': filename, 'bytes': len(content), 'sha256': checksum}
            (directory / 'complete.json').write_text(json.dumps(marker), encoding='utf-8')
            self.legacy_files.append(path)
        self.legacy = backup.capture_legacy(self.root)
        backup.atomic_json(self.root, self.root / backup.STORE / 'legacy.json', self.legacy)

    def tearDown(self):
        self.temp.cleanup()

    @property
    def base(self):
        return self.root / backup.STORE

    def run_ok(self, data=SQL):
        return backup.run_backup(self.root, dump=lambda stream: stream.write(data), reserve_bytes=0)

    def status(self):
        return backup.read_json(self.root, self.base / 'last-attempt.json')

    def assert_rejected(self, code, callback):
        with self.assertRaisesRegex(backup.Rejected, '^' + code + '$'):
            callback()

    def test_first_publish_only_then_removes_five_exact_legacy_pairs(self):
        original = backup.cleanup_retired
        observed = []
        def cleanup(root, transaction, current):
            observed.append(backup.read_current(root))
            self.assertTrue(all(path.exists() for path in self.legacy_files))
            return original(root, transaction, current)
        with patch.object(backup, 'cleanup_retired', side_effect=cleanup):
            current = self.run_ok()
        self.assertEqual(observed, [current])
        self.assertFalse(any(path.exists() for path in self.legacy_files))
        self.assertEqual(list((self.root / backup.LEGACY_ROOT).iterdir()), [])
        self.assertEqual(backup.read_current(self.root), current)
        self.assertEqual(current['validation'], 'DUMP_EXIT_AND_HASH_READBACK')
        self.assertIs(current['restoreTested'], False)
        self.assertTrue(current['createdAt'].endswith('Z'))
        self.assertEqual(self.status()['status'], 'SUCCESS')
        self.assertIsNone(self.status()['errorCode'])
        self.assertFalse((self.base / 'transaction.json').exists())

    def test_second_publish_retains_only_latest_and_no_business_or_state_changes(self):
        state = self.root / '.local/collection-scheduler/status.json'
        state.write_bytes(b'{"testOnly":"unchanged"}')
        first = self.run_ok()
        old = backup.generation_directory(self.root, first)
        second = self.run_ok(SQL.replace(b'fixture', b'another fixture'))
        self.assertNotEqual(first['generation'], second['generation'])
        self.assertFalse(old.exists())
        self.assertEqual([p.name for p in (self.base / 'generations').iterdir()], [second['generation']])
        self.assertEqual(state.read_bytes(), b'{"testOnly":"unchanged"}')
        self.assertTrue((self.root / '.local/collection-scheduler/runner.lock').exists())

    def test_dump_failure_keeps_legacy_and_cleanly_allows_later_retry(self):
        def failed(stream):
            stream.write(b'partial')
            raise backup.Rejected('DUMP_FAILED')
        self.assert_rejected('DUMP_FAILED', lambda: backup.run_backup(self.root, failed, 0))
        self.assertTrue(all(path.exists() for path in self.legacy_files))
        self.assertFalse((self.base / 'current.json').exists())
        self.assertEqual(list((self.base / 'generations').iterdir()), [])
        self.assertFalse((self.base / 'transaction.json').exists())
        self.assertEqual(self.status()['errorCode'], 'DUMP_FAILED')
        self.run_ok()

    def test_failed_new_dump_never_removes_old_current(self):
        previous = self.run_ok()
        def failed(stream):
            stream.write(b'partial')
            raise RuntimeError('private user/path/password must not reach status')
        self.assert_rejected('IO_FAILED', lambda: backup.run_backup(self.root, failed, 0))
        self.assertEqual(backup.read_current(self.root), previous)
        self.assertNotIn('private', (self.base / 'last-attempt.json').read_text())
        self.assertEqual(len(list((self.base / 'generations').iterdir())), 1)

    def test_empty_and_missing_footer_dump_rejected(self):
        for data in (b'', b'not complete', SQL + b'ERROR unexpected tail'):
            with self.subTest(data=data):
                self.assert_rejected('DUMP_INCOMPLETE', lambda: self.run_ok(data))
                self.assertTrue(all(path.exists() for path in self.legacy_files))
                self.assertFalse((self.base / 'current.json').exists())

    def test_footer_must_be_a_complete_final_line(self):
        for data in (b'fake -- Dump completed on 2026-10-18 23:00:00\n',
                     b'-- Dump completed on not-a-date\n'):
            self.assert_rejected('DUMP_INCOMPLETE', lambda: self.run_ok(data))

    def test_disk_reserve_before_dump_does_not_delete_anything(self):
        calls = []
        with patch.object(backup.shutil, 'disk_usage', return_value=type('Space', (), {'free': 1})()):
            self.assert_rejected('DISK_RESERVE_REQUIRED', lambda: backup.run_backup(
                self.root, lambda stream: calls.append(True), 0))
        self.assertEqual(calls, [])
        self.assertTrue(all(path.exists() for path in self.legacy_files))

    def test_disk_reserve_after_dump_keeps_old_and_removes_only_new_attempt(self):
        first = self.run_ok()
        spaces = [type('Space', (), {'free': 10**12})(), type('Space', (), {'free': 0})()]
        with patch.object(backup.shutil, 'disk_usage', side_effect=spaces):
            self.assert_rejected('DISK_RESERVE_REQUIRED', self.run_ok)
        self.assertEqual(backup.read_current(self.root), first)

    def test_publish_failure_keeps_current_and_retry_is_safe(self):
        first = self.run_ok()
        original = backup.atomic_json
        def fail(root, path, value):
            if path.name == 'current.json':
                raise OSError('simulated replace failure')
            return original(root, path, value)
        with patch.object(backup, 'atomic_json', side_effect=fail):
            self.assert_rejected('IO_FAILED', self.run_ok)
        self.assertEqual(backup.read_current(self.root), first)
        self.assertFalse((self.base / 'transaction.json').exists())
        self.run_ok()

    def test_error_after_pointer_replace_does_not_remove_published_backup(self):
        first = self.run_ok()
        original = backup.atomic_json
        def fail(root, path, value):
            original(root, path, value)
            if path.name == 'current.json':
                raise OSError('simulated directory fsync failure after replace')
        with patch.object(backup, 'atomic_json', side_effect=fail):
            self.assert_rejected('IO_FAILED', self.run_ok)
        published = backup.read_current(self.root)
        self.assertNotEqual(published, first)
        self.assertTrue(backup.generation_directory(self.root, first).exists())
        self.assertTrue((self.base / 'transaction.json').exists())
        calls = []
        def dump(stream):
            calls.append(True)
            stream.write(SQL)
        recovered = backup.run_backup(self.root, dump, 0)
        self.assertNotEqual(recovered['generation'], published['generation'])
        self.assertEqual(calls, [True])
        self.assertFalse(backup.generation_directory(self.root, first).exists())

    def test_changed_legacy_preserved_with_new_current_and_pending_cleanup(self):
        self.legacy_files[0].write_bytes(b'changed, do not delete')
        self.assert_rejected('CLEANUP_CHANGED', self.run_ok)
        self.assertEqual(self.legacy_files[0].read_bytes(), b'changed, do not delete')
        self.assertTrue(all(path.exists() for path in self.legacy_files))
        self.assertIsNotNone(backup.read_current(self.root))
        self.assertTrue((self.base / 'transaction.json').exists())

    def test_new_legacy_directory_preserved_without_cleaning_original_five(self):
        extra = self.root / backup.LEGACY_ROOT / '2026-10-18'
        extra.mkdir()
        self.assert_rejected('CLEANUP_CHANGED', self.run_ok)
        self.assertTrue(extra.exists())
        self.assertTrue(all(path.exists() for path in self.legacy_files))

    def test_crash_after_publication_resumes_cleanup_then_creates_this_weeks_dump(self):
        with patch.object(backup, 'cleanup_retired', side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt):
                self.run_ok()
        published = backup.read_current(self.root)
        self.assertEqual(self.status()['status'], 'RUNNING')
        self.assertTrue(all(path.exists() for path in self.legacy_files))
        calls = []
        def dump(stream):
            calls.append(True)
            stream.write(SQL)
        resumed = backup.run_backup(self.root, dump, 0)
        self.assertNotEqual(resumed['generation'], published['generation'])
        self.assertEqual(calls, [True])
        self.assertFalse(any(path.exists() for path in self.legacy_files))

    def test_crash_during_cleanup_is_idempotent(self):
        original = backup._remove_file
        interrupted = []
        def crash(root, path):
            original(root, path)
            if path.suffix == '.sql' and not interrupted:
                interrupted.append(True)
                raise KeyboardInterrupt
        with patch.object(backup, '_remove_file', side_effect=crash):
            with self.assertRaises(KeyboardInterrupt):
                self.run_ok()
        self.assertTrue(any(path.exists() for path in self.legacy_files))
        published = backup.read_current(self.root)
        current = self.run_ok()
        self.assertNotEqual(current['generation'], published['generation'])
        self.assertFalse(any(path.exists() for path in self.legacy_files))

    def test_crash_during_dump_blocks_growth_and_preserves_current(self):
        first = self.run_ok()
        def crash(stream):
            stream.write(b'partial fixture')
            raise KeyboardInterrupt
        with self.assertRaises(KeyboardInterrupt):
            backup.run_backup(self.root, crash, 0)
        self.assertEqual(backup.read_current(self.root), first)
        count = len(list((self.base / 'generations').iterdir()))
        for _ in range(2):
            self.assert_rejected('INCOMPLETE_ATTEMPT_REQUIRES_REVIEW', self.run_ok)
        self.assertEqual(len(list((self.base / 'generations').iterdir())), count)

    def test_changed_current_file_blocks_backup_and_no_cleanup(self):
        first = self.run_ok()
        path = backup.generation_directory(self.root, first) / 'business.sql'
        path.write_bytes(b'damaged current')
        self.assert_rejected('BACKUP_CHANGED', self.run_ok)
        self.assertEqual(path.read_bytes(), b'damaged current')

    def test_unmanaged_storage_and_generation_are_not_deleted(self):
        self.run_ok()
        unknown = self.base / 'generations' / 'other-important-data'
        unknown.mkdir()
        (unknown / 'keep.txt').write_bytes(b'keep')
        self.assert_rejected('UNKNOWN_ARTIFACTS', self.run_ok)
        self.assertEqual((unknown / 'keep.txt').read_bytes(), b'keep')

    def test_unmanaged_atomic_temporary_requires_review_not_blind_prune(self):
        unknown = self.base / '.write-unknown.tmp'
        unknown.write_bytes(b'keep')
        self.assert_rejected('UNKNOWN_ARTIFACTS', self.run_ok)
        self.assertTrue(unknown.exists())

    def test_manifest_path_traversal_rejected(self):
        first = self.run_ok()
        for malicious in ('../other', '../../..', '/opt', 'a' * 31, 'A' * 32):
            current = dict(first, generation=malicious)
            backup.atomic_json(self.root, self.base / 'current.json', current)
            self.assert_rejected('INVALID_MANIFEST', self.run_ok)
        backup.atomic_json(self.root, self.base / 'current.json', first)

    def test_bool_size_and_extra_manifest_fields_rejected(self):
        first = self.run_ok()
        for changed in (dict(first, bytes=True), dict(first, unexpected='value'), dict(first, restoreTested=True)):
            backup.atomic_json(self.root, self.base / 'current.json', changed)
            self.assert_rejected('INVALID_MANIFEST', self.run_ok)
        backup.atomic_json(self.root, self.base / 'current.json', first)

    def test_symlink_legacy_file_is_rejected_without_following(self):
        path = self.legacy_files[0]
        target = self.root / 'synthetic-target.sql'
        target.write_bytes(path.read_bytes())
        path.unlink()
        try:
            path.symlink_to(target)
        except OSError:
            self.skipTest('Host does not permit unprivileged symbolic links')
        self.assert_rejected('UNSAFE_PATH', lambda: backup.capture_legacy(self.root))
        self.assertTrue(target.exists())

    def test_hardlinked_legacy_file_is_rejected(self):
        target = self.root / 'synthetic-hardlink.sql'
        try:
            os.link(self.legacy_files[0], target)
        except OSError:
            self.skipTest('Host filesystem does not support hard links')
        self.assert_rejected('UNSAFE_PATH', lambda: backup.capture_legacy(self.root))

    def test_exact_five_legacy_only_and_hash_check(self):
        self.legacy_files[1].write_bytes(b'changed')
        self.assert_rejected('BACKUP_CHANGED', lambda: backup.capture_legacy(self.root))

    def test_capture_does_not_accept_unknown_or_missing_date(self):
        directory = self.root / backup.LEGACY_ROOT / '2026-10-99'
        directory.mkdir()
        self.assert_rejected('LEGACY_LAYOUT_CHANGED', lambda: backup.capture_legacy(self.root))

    def test_capture_does_not_accept_extra_file_in_date(self):
        (self.legacy_files[0].parent / 'other.sql').write_bytes(SQL)
        self.assert_rejected('LEGACY_LAYOUT_CHANGED', lambda: backup.capture_legacy(self.root))

    def test_legacy_manifest_traversal_cannot_delete_outside(self):
        bad = copy.deepcopy(self.legacy)
        bad['entries'][0]['file'] = '../../../../keep.sql'
        backup.atomic_json(self.root, self.base / 'legacy.json', bad)
        self.assert_rejected('INVALID_LEGACY_MANIFEST', self.run_ok)
        self.assertTrue(all(path.exists() for path in self.legacy_files))

    def test_existing_collection_lock_is_required(self):
        path = self.root / '.local/collection-scheduler/runner.lock'
        path.unlink()
        self.assert_rejected('UNSAFE_PATH', self.run_ok)
        self.assertFalse(path.exists())

    def test_collector_overlap_fails_without_dump(self):
        path = self.root / '.local/collection-scheduler/runner.lock'
        with backup.exclusive_lock(self.root, path, 'COLLECTION_BUSY'):
            self.assert_rejected('COLLECTION_BUSY', self.run_ok)
        self.assertEqual(self.status()['errorCode'], 'COLLECTION_BUSY')
        self.assertTrue(all(path.exists() for path in self.legacy_files))

    def test_worker_overlap_keeps_existing_status(self):
        backup.atomic_json(self.root, self.base / 'last-attempt.json', {'status': 'synthetic sentinel'})
        with backup.exclusive_lock(self.root, self.base / 'worker.lock', 'BACKUP_LOCKED'):
            self.assert_rejected('BACKUP_LOCKED', self.run_ok)
        self.assertEqual(self.status(), {'status': 'synthetic sentinel'})

    def test_generation_file_hardlink_rejects_before_download_replacement(self):
        first = self.run_ok()
        original = backup.generation_directory(self.root, first) / 'business.sql'
        try:
            os.link(original, self.root / 'second-link.sql')
        except OSError:
            self.skipTest('Hard links unavailable')
        self.assert_rejected('UNSAFE_PATH', self.run_ok)

    def test_cleanup_keeps_new_current_even_if_old_generation_changes(self):
        first = self.run_ok()
        old_path = backup.generation_directory(self.root, first) / 'business.sql'
        original = backup.cleanup_retired
        def changed(root, transaction, current):
            old_path.write_bytes(b'changed old SQL')
            return original(root, transaction, current)
        with patch.object(backup, 'cleanup_retired', side_effect=changed):
            self.assert_rejected('CLEANUP_CHANGED', self.run_ok)
        newest = backup.read_current(self.root)
        self.assertNotEqual(newest['generation'], first['generation'])
        self.assertTrue(old_path.exists())

    def test_pointer_changed_after_publish_blocks_cleanup_of_old_generation(self):
        first = self.run_ok()
        original = backup.cleanup_retired
        def changed(root, transaction, current):
            backup.atomic_json(root, self.base / 'current.json', first)
            return original(root, transaction, current)
        with patch.object(backup, 'cleanup_retired', side_effect=changed):
            self.assert_rejected('TRANSACTION_CHANGED', self.run_ok)
        self.assertEqual(backup.read_current(self.root), first)
        self.assertEqual(len(list((self.base / 'generations').iterdir())), 2)

    def test_readback_difference_prevents_publication(self):
        original = backup.digest
        count = []
        def wrong(root, path):
            result = original(root, path)
            if path.name == 'business.sql':
                count.append(True)
                if len(count) == 2:
                    return result[0], 'f' * 64
            return result
        with patch.object(backup, 'digest', side_effect=wrong):
            self.assert_rejected('DUMP_READBACK_FAILED', self.run_ok)
        self.assertFalse((self.base / 'current.json').exists())
        self.assertTrue(all(path.exists() for path in self.legacy_files))

    def test_cleanup_journal_cannot_target_current_as_previous(self):
        first = self.run_ok()
        bad = dict(schemaVersion=1, generation=first['generation'], previous=first, legacy=self.legacy)
        backup.atomic_json(self.root, self.base / 'transaction.json', bad)
        self.assert_rejected('TRANSACTION_CHANGED', self.run_ok)
        self.assertEqual(backup.read_current(self.root), first)

    def test_json_read_is_bounded(self):
        (self.base / 'legacy.json').write_bytes(b' ' * 65537)
        self.assert_rejected('INVALID_JSON', self.run_ok)

    def test_status_and_schema_have_no_private_paths_or_payload(self):
        current = self.run_ok()
        self.assertEqual(set(current), backup.MANIFEST_KEYS)
        self.assertEqual(set(self.status()), {'schemaVersion', 'status', 'startedAt', 'completedAt', 'errorCode'})
        self.assertNotIn(str(self.root), json.dumps(self.status()))
        self.assertTrue(self.status()['startedAt'].endswith('Z'))
        self.assertTrue(self.status()['completedAt'].endswith('Z'))

    def test_interrupted_normal_dump_keeps_previous_and_records_fixed_failure(self):
        first = self.run_ok()
        def interrupted(stream):
            stream.write(b'partial')
            raise backup.Rejected('INTERRUPTED')
        self.assert_rejected('INTERRUPTED', lambda: backup.run_backup(self.root, interrupted, 0))
        self.assertEqual(self.status()['errorCode'], 'INTERRUPTED')
        self.assertEqual(backup.read_current(self.root), first)
        self.assertFalse((self.base / 'transaction.json').exists())


class DumpProcessTests(unittest.TestCase):
    """Exercise process contracts with fake credentials/processes, never MySQL."""
    def setUp(self):
        TEST_ROOT.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=TEST_ROOT)
        self.root = Path(self.temp.name).absolute()
        credentials = self.root / '.local/d145-runtime'
        credentials.mkdir(parents=True)
        (credentials / 'db-credentials.json').write_text(json.dumps({
            'username': 'scsj_d145', 'port': 3306, 'password': 'a' * 48}), encoding='utf-8')
        self.memfd_path = self.root / 'synthetic-anonymous-memory-file'
        self.stream = (self.root / 'synthetic-output.sql').open('wb')
        self.patches = [
            patch.object(backup.sys, 'platform', 'linux'),
            patch.object(backup.os, 'memfd_create', side_effect=lambda *_: os.open(
                self.memfd_path, os.O_RDWR | os.O_CREAT | os.O_EXCL, 0o600), create=True),
            patch.object(backup.os, 'MFD_CLOEXEC', 1, create=True),
            patch.object(backup.os, 'fchmod', create=True),
        ]
        for item in self.patches:
            item.start()

    def tearDown(self):
        for item in reversed(self.patches):
            item.stop()
        self.stream.close()
        self.temp.cleanup()

    def test_innodb_preflight_and_dump_use_private_fd_no_password_args(self):
        process = SimpleNamespace(returncode=0, poll=lambda: 0)
        with patch.object(backup.subprocess, 'run', return_value=SimpleNamespace(
                returncode=0, stdout=b'4\t0\n')) as query, patch.object(
                backup.subprocess, 'Popen', return_value=process) as start:
            backup.system_dump(self.root, self.stream, 0)
        query_args, dump_args = query.call_args.args[0], start.call_args.args[0]
        self.assertEqual(query_args[0], '/usr/bin/mysql')
        self.assertEqual(dump_args[0], '/usr/bin/mysqldump')
        self.assertIn('ENGINE', query_args[-1])
        self.assertNotIn('a' * 48, repr(query_args) + repr(dump_args))
        self.assertTrue(query_args[1].startswith('--defaults-extra-file=/proc/self/fd/'))
        self.assertIn('--single-transaction', dump_args)
        self.assertIn('--quick', dump_args)
        self.assertEqual(dump_args[-1], backup.DATABASE)
        self.assertEqual(start.call_args.kwargs['stderr'], backup.subprocess.DEVNULL)
        self.assertEqual(query.call_args.kwargs['timeout'], 15)
        backup.os.fchmod.assert_called_once_with(start.call_args.kwargs['pass_fds'][0], 0o600)

    def test_nontransactional_engine_rejects_before_dump(self):
        with patch.object(backup.subprocess, 'run', return_value=SimpleNamespace(
                returncode=0, stdout=b'5\t1\n')), patch.object(backup.subprocess, 'Popen') as start:
            with self.assertRaisesRegex(backup.Rejected, '^NONTRANSACTIONAL_SCHEMA$'):
                backup.system_dump(self.root, self.stream, 0)
            start.assert_not_called()

    def test_metadata_failure_does_not_output_raw_database_error(self):
        with patch.object(backup.subprocess, 'run', return_value=SimpleNamespace(
                returncode=1, stdout=b'private sql')), patch.object(backup.subprocess, 'Popen') as start:
            with self.assertRaisesRegex(backup.Rejected, '^DATABASE_PREFLIGHT_FAILED$'):
                backup.system_dump(self.root, self.stream, 0)
            start.assert_not_called()

    def test_metadata_timeout_is_bounded_and_dump_not_started(self):
        with patch.object(backup.subprocess, 'run', side_effect=backup.subprocess.TimeoutExpired('mysql', 15)), \
                patch.object(backup.subprocess, 'Popen') as start:
            with self.assertRaisesRegex(backup.Rejected, '^DATABASE_PREFLIGHT_FAILED$'):
                backup.system_dump(self.root, self.stream, 0)
            start.assert_not_called()

    def test_nonzero_dump_exit_does_not_become_success(self):
        process = SimpleNamespace(returncode=2, poll=lambda: 2)
        with patch.object(backup.subprocess, 'run', return_value=SimpleNamespace(returncode=0, stdout=b'4\t0\n')), \
                patch.object(backup.subprocess, 'Popen', return_value=process):
            with self.assertRaisesRegex(backup.Rejected, '^DUMP_FAILED$'):
                backup.system_dump(self.root, self.stream, 0)

    def test_dump_timeout_terminates_child_before_return(self):
        state = {'stopped': False, 'waited': False}
        process = SimpleNamespace(returncode=None, poll=lambda: 0 if state['stopped'] else None,
                                  terminate=lambda: state.update(stopped=True),
                                  wait=lambda **_: state.update(waited=True))
        with patch.object(backup.subprocess, 'run', return_value=SimpleNamespace(returncode=0, stdout=b'4\t0\n')), \
                patch.object(backup.subprocess, 'Popen', return_value=process), \
                patch.object(backup.time, 'monotonic', side_effect=[0, 1501]):
            with self.assertRaisesRegex(backup.Rejected, '^DUMP_TIMEOUT$'):
                backup.system_dump(self.root, self.stream, 0)
        self.assertTrue(state['stopped'])
        self.assertTrue(state['waited'])

    def test_disk_pressure_terminates_child_before_return(self):
        state = {'stopped': False}
        process = SimpleNamespace(returncode=None, poll=lambda: 0 if state['stopped'] else None,
                                  terminate=lambda: state.update(stopped=True), wait=lambda **_: None)
        with patch.object(backup.subprocess, 'run', return_value=SimpleNamespace(returncode=0, stdout=b'4\t0\n')), \
                patch.object(backup.subprocess, 'Popen', return_value=process), \
                patch.object(backup.shutil, 'disk_usage', return_value=SimpleNamespace(free=0)):
            with self.assertRaisesRegex(backup.Rejected, '^DISK_RESERVE_REQUIRED$'):
                backup.system_dump(self.root, self.stream, 1)
        self.assertTrue(state['stopped'])


if __name__ == '__main__':
    unittest.main()
