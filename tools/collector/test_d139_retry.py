"""Synthetic only: no platform/database calls and no real sleeps."""
import datetime as dt
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import collect
from scheduled_collection import Runner, LinuxOps, Rejected, save
from test_scheduled_collection import FakeOps


class RetryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.now = dt.datetime(2026, 10, 16, 3, 0, tzinfo=dt.timezone.utc)

    def tearDown(self):
        self.temp.cleanup()

    def test_nested_waits_preserve_serial_order_and_failure_isolation(self):
        ops = FakeOps()
        ops.projects.append({'id': 'three', 'advertiserId': '103'})
        events = []
        def collection(state, progress):
            key = state['projectId']
            events.append('request-' + key)
            if key != 'three':
                progress('RETRY_WAIT', '2026-10-16T03:05:00+00:00')
                ops.yield_project()
                events.append('retry-' + key)
                if key == 'two':
                    raise Rejected('PROTOCOL_REJECTED')
            return '/synthetic/' + key
        ops.collect = collection
        runner = Runner(self.root, ops)
        result = runner.run(self.now)
        self.assertEqual(events, ['request-one', 'request-two', 'request-three', 'retry-two', 'retry-one'])
        self.assertEqual(result['status'], 'PARTIAL')
        failure = next(p for p in result['projects'] if p['projectId'] == 'two')
        self.assertNotIn('retryNotBefore', failure)
        self.assertEqual(failure['status'], 'FAILED')
        self.assertEqual([x for x in ops.calls if x[0] == 'import'], [('import', 'three'), ('import', 'one')])

    def test_noon_retries_failed_only_and_success_not_recollected(self):
        ops = FakeOps()
        ops.fail[('collect', 'one')] = Rejected('PROCESS_FAILED')
        runner = Runner(self.root, ops)
        runner.run(self.now)
        ops.fail.clear()
        self.assertEqual(runner.run(self.now.replace(hour=4))['status'], 'COMPLETE')
        self.assertEqual(ops.calls.count(('collect', 'one')), 2)
        self.assertEqual(ops.calls.count(('collect', 'two')), 1)

    def test_expired_yield_budget_does_not_start_another_project(self):
        ops = FakeOps()
        events = []
        def collection(state, progress):
            events.append(state['projectId'])
            if state['projectId'] == 'one':
                with patch('scheduled_collection.time.monotonic', side_effect=[0, 301]):
                    ops.yield_project()
                self.assertEqual(events, ['one'])
            return '/synthetic/' + state['projectId']
        ops.collect = collection
        Runner(self.root, ops).run(self.now)
        self.assertEqual(events, ['one', 'two'])

    def test_oversized_project_list_rejected_before_any_collection(self):
        ops = FakeOps()
        ops.projects *= 17
        with self.assertRaisesRegex(Rejected, 'PROJECT_COUNT_REJECTED'):
            Runner(self.root, ops).run(self.now)
        self.assertEqual(ops.calls, [])

    def gate_path(self):
        path = self.root / '.local/collection-scheduler/attempts' / ('a' * 32) / 'retry-permit.json'
        path.parent.mkdir(parents=True)
        return path

    def test_gate_waits_until_parent_permission(self):
        path = self.gate_path()
        polls = []
        def sleep(seconds):
            polls.append(seconds)
            self.assertFalse(path.exists())
            save(path, {'resume': True, 'retryCount': 1})
        gate = collect.scheduler_gate(self.root, str(path), sleep)
        gate(1)
        self.assertEqual(polls, [1])

    def test_gate_rejects_preexisting_and_out_of_scope_path(self):
        path = self.gate_path()
        save(path, {'resume': True})
        for value in (str(path), str(self.root / 'retry-permit.json')):
            with self.assertRaises(collect.Rejected):
                collect.scheduler_gate(self.root, value)

    def test_gate_rejects_orphan_and_timeout(self):
        path = self.gate_path()
        with patch('collect.os.getppid', side_effect=[100, 101]):
            gate = collect.scheduler_gate(self.root, str(path))
            with self.assertRaises(collect.Rejected):
                gate(1)
        with patch('collect.time.monotonic', side_effect=[0, 10801]):
            gate = collect.scheduler_gate(self.root, str(path))
            with self.assertRaises(collect.Rejected):
                gate(1)

    def test_adapter_releases_gate_only_after_other_project_completed(self):
        ops = LinuxOps.__new__(LinuxOps)
        ops.root = self.root
        events = []
        state = {'attemptDir': str(self.root / '.local/attempt'), 'plan': '/synthetic', 'projectId': 'one'}
        permit = Path(state['attemptDir']) / 'retry-permit.json'
        def yielded():
            self.assertFalse(permit.exists())
            events.append('other-finished')
        ops.yield_project = yielded
        def command(args, timeout, progress, log):
            self.assertIn('--retry-gate', args)
            self.assertFalse(permit.exists())
            progress('RETRY_WAIT', '2026-10-16T03:05:00+00:00', 1)
            self.assertEqual(events, ['RETRY_WAIT', 'other-finished'])
            self.assertEqual(json.loads(permit.read_bytes()), {'resume': True, 'retryCount': 1})
        with patch.object(ops, 'command', side_effect=command), patch.object(ops, 'recover_collection', return_value='/synthetic-receipt'):
            self.assertEqual(ops.collect(state, lambda status, retry: events.append(status)), '/synthetic-receipt')

    def test_adapter_yield_exception_never_permits_parked_request(self):
        ops = LinuxOps.__new__(LinuxOps)
        ops.root = self.root
        state = {'attemptDir': str(self.root / '.local/attempt'), 'plan': '/synthetic', 'projectId': 'one'}
        permit = Path(state['attemptDir']) / 'retry-permit.json'
        def yielded():
            raise Rejected('SYNTHETIC_INTERRUPTION')
        ops.yield_project = yielded
        def command(args, timeout, progress, log):
            progress('RETRY_WAIT', '2026-10-16T03:05:00+00:00', 1)
        with patch.object(ops, 'command', side_effect=command):
            with self.assertRaisesRegex(Rejected, 'SYNTHETIC_INTERRUPTION'):
                ops.collect(state, lambda *args: None)
        self.assertFalse(permit.exists())


if __name__ == '__main__':
    unittest.main()
