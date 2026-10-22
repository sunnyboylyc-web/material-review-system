/** Keep assets, API calls and authentication inside this application's mount path. */
export function applicationPath(path: string, base = import.meta.env.BASE_URL): string {
  if (!path.startsWith('/') || path.startsWith('//')) throw new Error('应用路径无效')
  if (!/^\/(?:[A-Za-z0-9_-]+\/)*$/.test(base)) throw new Error('应用部署路径无效')
  if (base === '/' || path === base.slice(0, -1) || path.startsWith(base)) return path
  return base.slice(0, -1) + path
}
