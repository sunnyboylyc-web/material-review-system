"""URL-key and legacy Bearer authentication; credentials never reach MCP handlers."""
import hmac
import os
from pathlib import Path
from urllib.parse import parse_qsl, urlencode
import re


def load_token(path):
    p=Path(path)
    if p.is_symlink() or not p.is_file():raise ValueError('AUTH_TOKEN_FILE_INVALID')
    if os.name!='nt' and p.stat().st_mode&0o007:raise ValueError('AUTH_TOKEN_FILE_WORLD_ACCESSIBLE')
    raw=p.read_text(encoding='ascii').strip()
    if not 32<=len(raw)<=512 or any(c.isspace() for c in raw):raise ValueError('AUTH_TOKEN_FORMAT_INVALID')
    return raw


class BearerGate:
    def __init__(self,app,token):self.app=app;self.expected=token.encode('ascii')

    async def __call__(self,scope,receive,send):
        if scope['type']!='http':return await self.app(scope,receive,send)
        values=[v for k,v in scope.get('headers',[]) if k.lower()==b'authorization']
        query=scope.get('query_string',b'')
        pairs=[];valid_query=True
        try:
            if len(query)>8192 or re.search(rb'%(?![0-9A-Fa-f]{2})',query):raise ValueError()
            pairs=parse_qsl(query.decode('ascii'),keep_blank_values=True,encoding='ascii',errors='strict',max_num_fields=32)
        except (ValueError,UnicodeError):valid_query=False
        keys=[v.encode('ascii') for k,v in pairs if k=='API_KEY']
        # Strip both credential channels BEFORE passing scope to SDK/error handlers.
        clean=dict(scope)
        clean['query_string']=urlencode([(k,v) for k,v in pairs if k!='API_KEY']).encode('ascii')
        clean['headers']=[(k,v) for k,v in scope.get('headers',[]) if k.lower()!=b'authorization']
        header_ok=False
        if len(values)==1:
            parts=values[0].split(b' ')
            header_ok=len(parts)==2 and parts[0].lower()==b'bearer' and hmac.compare_digest(parts[1],self.expected)
        query_ok=len(keys)==1 and hmac.compare_digest(keys[0],self.expected)
        # Reject duplicates and mixed conflicting credentials rather than silently choosing one.
        allowed=valid_query and bool(values or keys) and (not values or header_ok) and (not keys or query_ok)
        if not allowed:
            body=b'{"error":"MCP_BEARER_TOKEN_REQUIRED_OR_INVALID"}'
            await send({'type':'http.response.start','status':401,'headers':[(b'content-type',b'application/json'),(b'cache-control',b'no-store'),(b'www-authenticate',b'Bearer realm="material-review-mcp"')]})
            await send({'type':'http.response.body','body':body});return
        async def safe_send(event):
            if event['type']=='http.response.start':
                event=dict(event)
                event['headers']=[(k,v) for k,v in event.get('headers',[]) if k.lower() not in (b'cache-control',b'referrer-policy')]+[(b'cache-control',b'no-store'),(b'referrer-policy',b'no-referrer')]
            await send(event)
        await self.app(clean,receive,safe_send)
