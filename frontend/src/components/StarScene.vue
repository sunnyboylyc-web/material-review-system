<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
const props = defineProps<{ paused: boolean }>()
const canvas = ref<HTMLCanvasElement>()
const ready = ref(false)
let disposed = false, frame = 0, clock = 0, last = 0, draw: ((time: number) => void) | undefined
let media: MediaQueryList | undefined
function animate(now: number) {
  if (disposed || props.paused || media?.matches || document.hidden) { frame = 0; last = 0; return }
  frame = requestAnimationFrame(animate)
  if (now - last < 40) return
  clock += last ? Math.min((now - last) / 1000, .1) : 0
  last = now
  draw?.(clock)
}
function resume() { if (!frame && draw && !props.paused && !media?.matches && !document.hidden) frame = requestAnimationFrame(animate) }
watch(() => props.paused, resume)
onMounted(async () => {
  if (typeof Path2D === 'undefined') return
  media = window.matchMedia('(prefers-reduced-motion: reduce)')
  media.addEventListener('change', resume)
  document.addEventListener('visibilitychange', resume)
  const { createCore } = await import('../visual/vendor/star-core.js')
  if (disposed || !canvas.value) return
  const ctx = canvas.value.getContext('2d')
  if (!ctx) return
  const dpr = Math.min(window.devicePixelRatio || 1, 1.3)
  canvas.value.width = 920 * dpr; canvas.value.height = 700 * dpr
  const scene = createCore({ w: 920, h: 700, dpr, params: { grains: 8500, scale: .88 }, outerN: 8 })
  draw = time => scene.render(ctx, time)
  draw(0); ready.value = true; resume()
})
onBeforeUnmount(() => { disposed = true; cancelAnimationFrame(frame); media?.removeEventListener('change', resume); document.removeEventListener('visibilitychange', resume) })
</script>
<template><div class="star-scene" aria-hidden="true"><div v-if="!ready" class="star-fallback"><i /><b /></div><canvas ref="canvas" :class="{ ready }" /></div></template>
