import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { permitsPreviewQuery, permitsPreviewSource } from './preview-policy'

export default defineConfig(() => {
  const raw=process.env.SCSJ_PREVIEW_SOURCE_URL??''
  if(!permitsPreviewSource(raw,process.env.SCSJ_PREVIEW_LOOPBACK_SOURCE==='1'))
    throw new Error('Set the authorized HTTPS source or explicitly enabled loopback query bridge')
  const target=new URL(raw)
  const base=target.pathname.replace(/\/$/,'')
  return {
    plugins:[vue(),{name:'readonly-dashboard-preview',configureServer(server){
      server.middlewares.use((req,res,next)=>{
        if(!req.url?.startsWith('/api'))return next()
        if(!permitsPreviewQuery(req.method??'',req.url)){
          res.statusCode=405;res.setHeader('Content-Type','application/json');res.end(JSON.stringify({error:'READ_ONLY_PREVIEW_QUERY_NOT_ALLOWED'}));return
        }
        next()
      })
    }}],
    server:{host:'127.0.0.1',port:5189,strictPort:true,proxy:{'/api':{
      target:target.origin,changeOrigin:true,secure:true,rewrite:path=>base+path,
      configure(proxy){
        proxy.on('proxyReq',request=>{request.removeHeader('cookie');request.removeHeader('authorization')})
        proxy.on('proxyRes',response=>{delete response.headers['set-cookie'];response.headers['cache-control']='no-store'})
      },
    }}},
  }
})
