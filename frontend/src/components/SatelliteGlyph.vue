<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import { satellitePreset, SATELLITES } from '../visual/satellites'
import { renderMoonSurface } from '../visual/moon-surface.js'
const props = defineProps<{ index: number }>()
const preset = computed(() => satellitePreset(props.index))
const canvas = ref<HTMLCanvasElement>()
const ready = ref(false)
const photoFilter = `moon-background-${useId()}`
function render(event: Event) {
  if (!preset.value.globe || !canvas.value) return
  try {
    const surface = renderMoonSurface(event.target as HTMLImageElement, preset.value.globe)
    canvas.value.width = surface.width; canvas.value.height = surface.height
    const context = canvas.value.getContext('2d')
    if (!context) return
    context.drawImage(surface, 0, 0); ready.value = true
  } catch { ready.value = false }
}
</script>
<template>
  <span class="satellite-glyph natural-moon" :data-satellite-model="((index % SATELLITES.length) + SATELLITES.length) % SATELLITES.length + 1" :data-moon="preset.model" aria-hidden="true">
    <svg class="moon-filter-defs" width="0" height="0"><defs><filter :id="photoFilter" color-interpolation-filters="sRGB"><feColorMatrix type="matrix" values="1 0 0 0 0 0 1 0 0 0 0 0 1 0 0 .3333 .3333 .3333 0 0"/><feComponentTransfer result="background-mask"><feFuncA type="linear" slope="100"/></feComponentTransfer><feComposite in="SourceGraphic" in2="background-mask" operator="in"/></filter></defs></svg>
    <span class="moon-disc" :class="{'moon-disc--mapped':preset.globe, 'moon-disc--ready':ready, 'moon-disc--crop':preset.cropLeft || preset.circularCrop}">
      <img :src="preset.image" alt="" decoding="async" draggable="false" :style="[preset.cropLeft ? {width:'200%',height:'100%',maxWidth:'none',objectFit:'fill'} : {transform:`scale(${preset.scale})`}, {filter:preset.globe ? undefined : `url(#${photoFilter})`}]" @load="render">
      <canvas v-if="preset.globe" ref="canvas" class="moon-surface" :data-render-ready="ready"/>
      <span v-if="preset.globe && !ready" class="moon-surface-pending"/>
    </span>
  </span>
</template>
