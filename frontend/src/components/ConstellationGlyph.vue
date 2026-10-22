<script setup lang="ts">
import { computed } from 'vue'
const props = withDefaults(defineProps<{ kind?: number }>(), { kind: 0 })
const shapes = [
  [[8,58],[32,45],[54,52],[76,30],[101,39],[126,18],[145,31],[133,55],[107,65],[83,82],[54,72]],
  [[15,77],[28,45],[53,53],[78,37],[105,44],[126,30],[146,46],[133,61],[112,61]],
  [[19,22],[37,39],[33,62],[60,75],[83,56],[100,29],[125,18],[144,34],[129,61]],
  [[29,82],[34,49],[56,29],[84,48],[110,31],[139,49],[111,68],[83,48],[74,80]],
  [[16,66],[40,41],[68,51],[89,25],[115,42],[143,34],[130,65],[104,80],[83,64]],
]
const dots = computed(() => shapes[props.kind % 5])
</script>
<template><svg class="constellation-glyph" viewBox="0 0 164 100" aria-hidden="true"><polyline :points="dots.map(p => p.join(',')).join(' ')" fill="none" stroke="currentColor" stroke-width=".8" opacity=".6" /><path :d="`M${dots[1].join(' ')} L${dots[6].join(' ')}`" fill="none" stroke="currentColor" stroke-width=".5" opacity=".24" /><g v-for="(point,i) in dots" :key="i"><circle :cx="point[0]" :cy="point[1]" :r="i % 3 === 0 ? 5 : 3" fill="currentColor" opacity=".11"/><circle :cx="point[0]" :cy="point[1]" :r="i % 3 === 0 ? 1.8 : 1.15" fill="#fff2d4" /><path v-if="i % 3 === 0" :d="`M${point[0]-5} ${point[1]}h10M${point[0]} ${point[1]-5}v10`" stroke="currentColor" stroke-width=".5" opacity=".7" /></g></svg></template>
