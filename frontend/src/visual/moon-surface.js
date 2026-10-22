// Orthographic globe display from unchanged NASA equirectangular map assets.
// This is a static UI render, not a replacement scientific photograph.
// Front lighting keeps the complete visible hemisphere readable; no invented terrain.
const cache = new Map()
export function renderMoonSurface(image, options, size = 320) {
  const key = `${image.currentSrc || image.src}|${options.centerU}|${options.latitude}|${size}`
  if (cache.has(key)) return cache.get(key)
  const texture = document.createElement('canvas')
  texture.width = image.naturalWidth; texture.height = image.naturalHeight
  const source = texture.getContext('2d', { willReadFrequently: true })
  if (!source) throw new Error('Canvas unavailable')
  source.drawImage(image, 0, 0)
  const pixels = source.getImageData(0, 0, texture.width, texture.height).data
  const canvas = document.createElement('canvas'); canvas.width = canvas.height = size
  const target = canvas.getContext('2d')
  if (!target) throw new Error('Canvas unavailable')
  const frame = target.createImageData(size, size), radius = size * .47
  const tilt = options.latitude * Math.PI / 180, cos = Math.cos(tilt), sin = Math.sin(tilt)
  for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
    const nx = (x + .5 - size / 2) / radius, ny = (size / 2 - y - .5) / radius
    const squared = nx * nx + ny * ny
    if (squared >= 1) continue
    const z = Math.sqrt(1 - squared), worldY = ny * cos + z * sin, worldZ = z * cos - ny * sin
    const u = ((options.centerU + Math.atan2(nx, worldZ) / (2 * Math.PI)) % 1 + 1) % 1
    const v = .5 - Math.asin(Math.max(-1, Math.min(1, worldY))) / Math.PI
    const tx = Math.min(texture.width - 1, Math.floor(u * texture.width))
    const ty = Math.min(texture.height - 1, Math.max(0, Math.floor(v * texture.height)))
    const input = (ty * texture.width + tx) * 4, output = (y * size + x) * 4
    const light = .82 + .18 * z
    for (let c = 0; c < 3; c++) frame.data[output + c] = pixels[input + c] * light
    frame.data[output + 3] = Math.min(1, radius * (1 - Math.sqrt(squared))) * 255
  }
  target.putImageData(frame, 0, 0)
  cache.set(key, canvas)
  return canvas
}
