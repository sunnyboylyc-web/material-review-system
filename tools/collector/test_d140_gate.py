"""Local synthetic permission-sequence tests; no network, credentials, or real sleeps."""
import json
from pathlib import Path
import tempfile
import unittest

import collect


class GateSequenceTest(unittest.TestCase):
    def setUp(self):
        runs = Path(__file__).resolve().parents[2] / 'qa/runs/collector-unit'
        runs.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=runs)
        self.root = Path(self.temp.name).resolve()
        self.path = self.root / '.local/collection-scheduler/attempts' / ('a' * 32) / 'retry-permit.json'
        self.path.parent.mkdir(parents=True)

    def tearDown(self):
        self.temp.cleanup()

    def permit(self, sequence):
        self.path.write_text(json.dumps({'resume': True, 'retryCount': sequence}))

    def test_stale_first_permit_cannot_release_second_or_third_wait(self):
        calls = []
        def sleep(seconds):
            calls.append(seconds)
            self.permit(len(calls))
        gate = collect.scheduler_gate(self.root, str(self.path), sleep)
        for sequence in range(1, 13):
            gate(sequence)
            self.assertEqual(sequence, len(calls))
        with self.assertRaises(collect.Rejected):
            gate(13)

    def test_future_permit_rejected(self):
        gate = collect.scheduler_gate(self.root, str(self.path))
        self.permit(2)
        with self.assertRaises(collect.Rejected):
            gate(1)

    def test_missing_sequence_and_boolean_sequence_rejected(self):
        for permit in ({'resume': True}, {'resume': True, 'retryCount': True}):
            with self.subTest(permit=permit):
                if self.path.exists():
                    self.path.unlink()
                gate = collect.scheduler_gate(self.root, str(self.path))
                self.path.write_text(json.dumps(permit))
                with self.assertRaises(collect.Rejected):
                    gate(1)

    def test_repeated_or_skipped_wait_sequence_rejected(self):
        gate = collect.scheduler_gate(self.root, str(self.path))
        self.permit(1)
        gate(1)
        for sequence in (1, 3, True):
            with self.assertRaises(collect.Rejected):
                gate(sequence)


if __name__ == '__main__':
    unittest.main()
