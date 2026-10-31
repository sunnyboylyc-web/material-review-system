import json
from pathlib import Path
import os
import tempfile
import unittest
from tools.mcp_api.auth import BearerGate,load_token


class BearerTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.calls=0;self.token='synthetic-test-token-'+'x'*40
        async def app(scope,receive,send):
            self.calls+=1
            await send({'type':'http.response.start','status':200,'headers':[]})
            await send({'type':'http.response.body','body':b'{"ok":true}'})
        self.gate=BearerGate(app,self.token)
    async def request(self,headers=(),method='POST'):
        events=[]
        async def send(e):events.append(e)
        async def receive():return {'type':'http.request','body':b''}
        await self.gate({'type':'http','method':method,'path':'/mcp','headers':list(headers)},receive,send)
        return events
    async def test_missing_or_wrong_key_blocks_before_tools_or_resources(self):
        for headers in [[],[(b'authorization',b'Bearer incorrect')],[(b'authorization',self.token.encode())]]:
            for method in ('GET','POST','DELETE'):
                result=await self.request(headers,method);self.assertEqual(401,result[0]['status']);self.assertNotIn(self.token.encode(),result[1]['body'])
        self.assertEqual(0,self.calls)
    async def test_valid_key_passes_and_duplicate_header_is_rejected(self):
        h=(b'authorization',b'Bearer '+self.token.encode())
        self.assertEqual(200,(await self.request([h]))[0]['status']);self.assertEqual(1,self.calls)
        self.assertEqual(401,(await self.request([h,h]))[0]['status']);self.assertEqual(1,self.calls)
    async def test_unauthorized_has_bearer_challenge_and_no_store(self):
        result=await self.request();headers=dict(result[0]['headers'])
        self.assertIn(b'Bearer',headers[b'www-authenticate']);self.assertEqual(b'no-store',headers[b'cache-control'])


class TokenFiles(unittest.TestCase):
    def test_short_and_missing_key_are_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'key';p.write_text('short');p.chmod(0o600)
            with self.assertRaisesRegex(ValueError,'FORMAT'):load_token(p)
            with self.assertRaisesRegex(ValueError,'FILE_INVALID'):load_token(p.with_name('missing'))
    def test_key_file_is_read_but_not_exposed(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'key';token='test-only-'+'z'*40;p.write_text(token+'\n');p.chmod(0o600)
            self.assertEqual(token,load_token(p))


if __name__=='__main__':unittest.main()
