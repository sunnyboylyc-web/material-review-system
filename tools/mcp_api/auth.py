"""Static Bearer authentication at the ASGI boundary; no token value in RPC/config output."""
import hmac
import os
from pathlib import Path


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
        allowed=False
        if len(values)==1:
            parts=values[0].split(b' ')
            allowed=len(parts)==2 and parts[0].lower()==b'bearer' and hmac.compare_digest(parts[1],self.expected)
        if not allowed:
            body=b'{"error":"MCP_BEARER_TOKEN_REQUIRED_OR_INVALID"}'
            await send({'type':'http.response.start','status':401,'headers':[(b'content-type',b'application/json'),(b'cache-control',b'no-store'),(b'www-authenticate',b'Bearer realm="material-review-mcp"')]})
            await send({'type':'http.response.body','body':body});return
        await self.app(scope,receive,send)
