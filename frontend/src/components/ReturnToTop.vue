<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
defineProps<{ blocked?: boolean }>()
const shown = ref(false)
let frame = 0
function measure() { shown.value = (window.scrollY || document.documentElement.scrollTop) > 360; frame = 0 }
function onScroll() { if (!frame) frame = requestAnimationFrame(measure) }
function returnToTop() {
  const target = document.querySelector<HTMLElement>('.global-header')
  target?.focus({ preventScroll: true })
  window.scrollTo({ top: 0, behavior: window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' })
}
onMounted(() => { measure(); window.addEventListener('scroll', onScroll, { passive: true }) })
onBeforeUnmount(() => { window.removeEventListener('scroll', onScroll); cancelAnimationFrame(frame) })
</script>
<template><button v-if="shown && !blocked" class="return-to-top" type="button" aria-label="返回顶部" title="返回顶部" @click="returnToTop"><svg viewBox="0 0 24 24" width="23" height="23" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M12 20V4m-6 6 6-6 6 6"/></svg></button></template>
<style scoped>
.return-to-top { position: fixed; right: max(24px, env(safe-area-inset-right)); bottom: max(24px, env(safe-area-inset-bottom)); z-index: 30; width: 48px; height: 48px; display: grid; place-items: center; border: 1px solid #8b7958; border-radius: 50%; background: #191a25; color: #ecd39c; box-shadow: 0 4px 18px #0004; cursor: pointer; }
.return-to-top:hover { background: #2a2730; }
.return-to-top:focus-visible { outline: 2px solid #ecd39c; outline-offset: 4px; }
@media(max-width:540px) { .return-to-top { right: max(16px, env(safe-area-inset-right)); bottom: max(16px, env(safe-area-inset-bottom)); } }
</style>
