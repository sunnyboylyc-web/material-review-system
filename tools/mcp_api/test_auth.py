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
    async def request(self,headers=(),method='POST',query=b''):
        events=[]
        async def send(e):events.append(e)
        async def receive():return {'type':'http.request','body':b''}
        await self.gate({'type':'http','method':method,'path':'/mcp','headers':list(headers),'query_string':query},receive,send)
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

    async def test_url_key_all_methods_and_no_cache(self):
        for method in ('GET','POST','DELETE'):
            response=await self.request(method=method,query=b'API_KEY='+self.token.encode())
            self.assertEqual(200,response[0]['status'])
            self.assertEqual(b'no-store',dict(response[0]['headers'])[b'cache-control'])

    async def test_bad_duplicate_conflicting_and_malformed_credentials_rejected(self):
        good=b'API_KEY='+self.token.encode()
        header=(b'authorization',b'Bearer '+self.token.encode())
        invalid=[
            (b'API_KEY=',[]),
            (b'API_KEY=wrong',[]),(good+b'&'+good,[]),(b'API_KEY=wrong',[header]),
            (good,[(b'authorization',b'Bearer wrong')]),(good,[header,header]),
            (good+b'&x=%ZZ',[]),(b'API_KEY=%FF',[]),(b'x='+b'a'*8193,[])]
        for q,h in invalid:
            self.assertEqual(401,(await self.request(h,query=q))[0]['status'])
        self.assertEqual(200,(await self.request([header],query=good))[0]['status'])

    async def test_encoded_key_and_credential_removal_before_sdk(self):
        captured=[]
        self.token += '+/?=&%'
        async def downstream(scope,receive,send):
            captured.append(scope)
            await send({'type':'http.response.start','status':200,'headers':[]})
        self.gate=BearerGate(downstream,self.token)
        from urllib.parse import quote
        response=await self.request([(b'authorization',b'Bearer '+self.token.encode())],query=('API%5FKEY='+quote(self.token,safe='')+'&keep=yes').encode())
        self.assertEqual(200,response[0]['status'])
        self.assertEqual(b'keep=yes',captured[0]['query_string'])
        self.assertFalse(captured[0]['headers'])
        self.assertNotIn(self.token,str(captured[0]))


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
