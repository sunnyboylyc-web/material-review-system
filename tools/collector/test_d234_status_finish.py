import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import scheduled_collection_titania as s

class StatusFinishTests(unittest.TestCase):
    def test_terminal_and_interrupted_states_do_not_invent_success(self):
        cases=[([],False,'FAILED'),(['VERIFIED'],False,'COMPLETE'),
               (['ALREADY_PRESENT'],False,'COMPLETE'),(['VERIFIED','FAILED'],False,'PARTIAL'),
               (['COLLECTING'],True,'FAILED'),(['VERIFIED'],True,'PARTIAL'),
               (['IMPORTING'],True,'REVIEW_REQUIRED'),(['VERIFYING'],True,'REVIEW_REQUIRED'),
               (['REVIEW_REQUIRED'],False,'REVIEW_REQUIRED')]
        for states,interrupted,expected in cases:
            with self.subTest(states=states,interrupted=interrupted), tempfile.TemporaryDirectory() as tmp:
                runner=s.Runner(Path(tmp),None)
                runner.public={'status':'RUNNING','projects':[{'status':x} for x in states]}
                runner.finish(interrupted)
                result=json.loads((runner.base/'status.json').read_text())
                self.assertEqual(expected,result['status'])
                self.assertEqual(states,[p['status'] for p in result['projects']])
                self.assertFalse((runner.base/'days').exists())

    def test_unexpected_runner_failure_closes_observation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);p=root/'.local/d218-runtime/db-credentials.json';p.parent.mkdir(parents=True)
            p.write_text(json.dumps({'username':'scsj_titania','port':3306,'password':'a'*48}))
            (root/'.local/collection-scheduler').mkdir()
            def fail(runner,now):
                runner.public={'status':'RUNNING','projects':[{'status':'IMPORTING'}]}
                runner.publish()
                raise RuntimeError('synthetic')
            with patch.object(s,'LinuxOps'),patch.object(s.Runner,'run',fail):
                with self.assertRaises(RuntimeError):s.locked_run(root,'2026-10-21')
            self.assertEqual('REVIEW_REQUIRED',json.loads((root/'.local/collection-scheduler/status.json').read_text())['status'])

if __name__=='__main__':unittest.main()
