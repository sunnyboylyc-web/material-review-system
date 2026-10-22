<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { ProjectModule, TopResult, ReviewResult } from '../domain/dashboard'
import StarScene from './StarScene.vue'
import UiIcon from './UiIcon.vue'
import SatelliteGlyph from './SatelliteGlyph.vue'
import HomeProjectOverview from './HomeProjectOverview.vue'
import type { DashboardDataAdapter } from '../adapters/dashboardAdapter'
import { satellitePreset } from '../visual/satellites'
const props = defineProps<{ projects: ProjectModule[]; selectedProjectId: string; projectName: string; canOperate: boolean; active?: boolean; overviewRevision?: number; overviewAdapter?: Pick<DashboardDataAdapter, 'fetchReview'>; top: TopResult | null; review: ReviewResult | null }>()
const emit = defineEmits<{ navigate: [page: string]; selectProject: [id: string, page: 'top' | 'review']; selectOverview: [id: string]; projects: [] }>()
const paused = ref(false)
const visible = ref(true), pageVisible = ref(!document.hidden), reducedMotion = ref(false)
const motionPaused = computed(() => paused.value || props.active === false || !visible.value || !pageVisible.value || reducedMotion.value)
let visibilityObserver: IntersectionObserver | undefined, motionPreference: MediaQueryList | undefined
function syncVisibility() { pageVisible.value = !document.hidden }
function syncMotionPreference() { reducedMotion.value = motionPreference?.matches === true }
const universe = ref<HTMLElement>()
const core = ref<HTMLElement>()
const orbits = ref<HTMLElement>()
const connections = ref<{ id: string; path: string; x: number; y: number }[]>([])
const connectionViewBox = ref('0 0 1600 900')
let observer: ResizeObserver | undefined, layoutFrame = 0, disposed = false
// Pure layout measurement: these lines are navigation decoration, never business relationships.
function measureConnections() {
  if (!universe.value || !core.value || !orbits.value || disposed) return
  const area = universe.value.getBoundingClientRect(), center = core.value.getBoundingClientRect()
  if (!area.width || !area.height) return
  const cx = center.left + center.width / 2 - area.left, cy = center.top + center.height / 2 - area.top
  connectionViewBox.value = `0 0 ${area.width} ${area.height}`
  connections.value = Array.from(orbits.value.querySelectorAll<HTMLElement>('.project-satellite')).map(node => {
    const moon = node.querySelector('.natural-moon')!.getBoundingClientRect()
    const mx = moon.left + moon.width / 2 - area.left, my = moon.top + moon.height / 2 - area.top
    const dx = cx - mx, dy = cy - my, distance = Math.max(1, Math.hypot(dx, dy))
    const x = mx + dx / distance * (moon.width / 2 + 6), y = my + dy / distance * (moon.height / 2 + 6)
    const endX = cx - dx / distance * 25, endY = cy - dy / distance * 25
    const bend = mx > cx ? -85 : -18
    return { id: node.dataset.projectId!, x, y, path: `M ${x} ${y} C ${x + dx * .14} ${y + bend}, ${endX - dx * .3} ${endY + bend}, ${endX} ${endY}` }
  })
}
function scheduleConnections() {
  if (disposed) return
  cancelAnimationFrame(layoutFrame)
  layoutFrame = requestAnimationFrame(measureConnections)
}
watch(() => props.projects.map(project => project.id).join('|'), () => { void nextTick(scheduleConnections) }, { flush: 'post' })
onMounted(() => {
  motionPreference = window.matchMedia?.('(prefers-reduced-motion: reduce)')
  syncMotionPreference(); motionPreference?.addEventListener('change', syncMotionPreference)
  document.addEventListener('visibilitychange', syncVisibility)
  if (typeof IntersectionObserver !== 'undefined' && universe.value) {
    visibilityObserver = new IntersectionObserver(entries => { visible.value = entries.some(entry => entry.isIntersecting) })
    visibilityObserver.observe(universe.value)
  }
  if (typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(scheduleConnections)
    for (const element of [universe.value, core.value, orbits.value]) if (element) observer.observe(element)
  }
  window.addEventListener('resize', scheduleConnections)
  void nextTick(scheduleConnections)
})
onBeforeUnmount(() => { disposed = true; observer?.disconnect(); visibilityObserver?.disconnect(); motionPreference?.removeEventListener('change', syncMotionPreference); document.removeEventListener('visibilitychange', syncVisibility); cancelAnimationFrame(layoutFrame); window.removeEventListener('resize', scheduleConnections) })
function position(index: number, count: number) {
  const points = count === 1 ? [[18,52]] : count === 2 ? [[18,52],[82,52]]
    : count === 3 ? [[16,58],[84,58],[53,12]]
    : count <= 4 ? [[17,28],[83,28],[21,83],[79,83]]
    : count <= 6 ? [[13,34],[87,34],[37,4],[63,4],[24,85],[76,85]]
    : [[10,18],[90,18],[10,52],[90,52],[10,86],[90,86],[37,5],[63,5],[37,95],[63,95]]
  const [x,y] = points[index % points.length]
  return {'--satellite-x':`${x}%`,'--satellite-y':`${y}%`,'--satellite-accent':satellitePreset(index).color}
}
</script>
<template>
  <section ref="universe" class="cosmic-workspace project-universe" :class="{'has-many-projects':projects.length>4,'has-full-orbit':projects.length>10,'motion-paused':motionPaused}" aria-label="项目卫星工作台">
    <div class="cosmic-stars" aria-hidden="true"><i v-for="n in 92" :key="n" :style="{left: `${(n * 37.71) % 100}%`, top: `${(n * 19.37) % 100}%`, opacity: .15 + (n % 5) * .1, width: `${n % 7 === 0 ? 2 : 1}px`}" /></div>
    <header class="cosmic-heading"><p class="overline">素材视界 <span>·</span> PROJECT UNIVERSE</p><h1>让每一份流量，<br>都有迹可循<span>。</span></h1></header>
    <div class="cosmic-controls"><button @click="paused = !paused" :aria-pressed="paused || reducedMotion" :disabled="reducedMotion">{{ reducedMotion ? '已按系统设置减少动效' : paused ? '▷ 播放动效' : 'Ⅱ 暂停动效' }}</button></div>
    <div ref="core" class="cosmic-center"><StarScene :paused="motionPaused"/><div class="core-caption"><span>PROJECT ORBITS</span><b>项目轨道 · 素材视界</b></div></div>
    <svg class="project-connections" :viewBox="connectionViewBox" preserveAspectRatio="none" aria-hidden="true">
      <g v-for="connection in connections" :key="connection.id" :data-connection-project="connection.id">
        <path class="project-connection-glow" :d="connection.path"/>
        <path class="project-connection-line" :d="connection.path"/>
        <circle :cx="connection.x" :cy="connection.y" r="2"/>
      </g>
    </svg>
    <div ref="orbits" class="project-orbits" :data-project-count="projects.length">
      <svg class="project-orbit-lines" viewBox="0 0 1000 600" preserveAspectRatio="none" aria-hidden="true"><ellipse cx="500" cy="320" rx="370" ry="140" transform="rotate(-10 500 320)"/><ellipse cx="500" cy="320" rx="410" ry="190" transform="rotate(8 500 320)" stroke-dasharray="2 13"/></svg>
      <article v-for="(project,index) in projects" :key="project.id" class="project-satellite" :class="{'is-selected':project.id===selectedProjectId}" :style="position(index,projects.length)" :data-project-id="project.id" :data-satellite-number="index+1" :aria-label="project.name">
        <div class="satellite-label" @click="emit('selectOverview',project.id)"><button class="satellite-select" :aria-label="`选择项目：${project.name}，查看近7天数据`" :aria-pressed="project.id===selectedProjectId" @click.stop="emit('selectOverview',project.id)">{{ project.name }}</button><nav class="satellite-destinations" :aria-label="`${project.name}快捷入口`"><button :aria-label="`${project.name}：每日TOP素材`" @click.stop="emit('selectProject',project.id,'top')">每日TOP素材<UiIcon name="arrow" :size="12"/></button><span aria-hidden="true"/><button :aria-label="`${project.name}：周期数据`" @click.stop="emit('selectProject',project.id,'review')">周期数据<UiIcon name="arrow" :size="12"/></button></nav></div>
        <button class="satellite-open" :aria-label="`进入项目：${project.name}，每日TOP素材`" @click="emit('selectProject',project.id,'top')"><SatelliteGlyph :index="index"/></button>
      </article>
      <div v-if="!projects.length" class="satellite-empty"><UiIcon name="folder" :size="28"/><h2>等待第一个项目入轨</h2><p>{{ canOperate ? '在项目配置中添加并启用项目，卫星会自动出现。' : '当前没有可用项目，请联系运维配置。' }}</p><button v-if="canOperate" class="gold-button" @click="emit('navigate','configuration')">配置项目 <UiIcon name="arrow"/></button></div>
    </div>
  </section>
  <HomeProjectOverview :project-id="selectedProjectId" :project-name="projectName" :active="active" :adapter="overviewAdapter" :revision="overviewRevision"/>
</template>
<style scoped>
.satellite-select { display:block; width:100%; min-height:44px; padding:0; border:0; background:none; text-align:left; color:inherit; font:600 18px/1.5 Georgia,"SimSun",serif; cursor:pointer; }
.satellite-select:focus-visible, .satellite-destinations button:focus-visible { outline:2px solid var(--sc-gold,#e8c884); outline-offset:4px; border-radius:4px; }
.satellite-label { cursor:pointer; min-width:258px; }
.satellite-destinations { gap:10px; }
.satellite-destinations button { min-height:36px; white-space:nowrap; }
.cosmic-center :deep(.star-scene) { animation: workspace-core-breathe 9s ease-in-out infinite; }
.project-satellite :deep(.moon-disc) { animation: workspace-moon-drift 14s ease-in-out infinite; }
.motion-paused .cosmic-center :deep(.star-scene), .motion-paused .project-satellite :deep(.moon-disc) { animation-play-state: paused; }
@keyframes workspace-core-breathe { 0%,100% { opacity:.88; } 50% { opacity:1; } }
@keyframes workspace-moon-drift { 0%,100% { transform:rotate(-2deg); } 50% { transform:rotate(2deg); } }
@media(prefers-reduced-motion:reduce) { .cosmic-center :deep(.star-scene), .project-satellite :deep(.moon-disc) { animation:none; } }
@media(max-width:600px) { .satellite-label { min-width:0; } .satellite-select { font-size:16px; } .satellite-destinations { gap:6px; } }
</style>
