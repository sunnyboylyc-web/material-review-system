/** Only dashboard query endpoints are permitted through the local preview. */
export function permitsPreviewQuery(method: string, rawUrl: string): boolean {
  if (method !== 'GET') return false
  const path=rawUrl.split('?')[0]
  return path==='/api/projects'||/^\/api\/projects\/[A-Za-z0-9_-]+\/(freshness|review|top|material-types|review-materials)$/.test(path)
}

export function permitsPreviewSource(raw: string, allowLoopback = false): boolean {
  try {
    const u=new URL(raw)
    return !u.username&&!u.password&&!u.search&&!u.hash&&
      (u.protocol==='https:'||(allowLoopback&&u.protocol==='http:'&&u.hostname==='127.0.0.1'))
  } catch { return false }
}
