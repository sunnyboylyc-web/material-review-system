<script setup lang="ts">
import { ref } from 'vue'
import { SATELLITES } from '../visual/satellites'
import SatelliteGlyph from './SatelliteGlyph.vue'
import UiIcon from './UiIcon.vue'
const dialog = ref<HTMLDialogElement | null>(null)
function open() { dialog.value?.showModal() }
function close() { dialog.value?.close() }
defineExpose({ open })
</script>
<template>
  <dialog ref="dialog" class="moon-atlas" aria-labelledby="moon-atlas-title" aria-describedby="moon-atlas-description" @click="($event.target === dialog) && close()">
    <header class="moon-atlas-heading"><div><p class="overline">NATURAL SATELLITES <span>·</span> 01—16</p><h2 id="moon-atlas-title">十六颗卫星，十六种风景。</h2><p id="moon-atlas-description">从月海、冰纹到火山与峡谷，为每个项目选择一份来自真实宇宙的辨识度。</p></div><button class="icon-button" aria-label="关闭自然卫星图鉴" autofocus @click="close"><UiIcon name="close"/></button></header>
    <div class="moon-atlas-note"><span>01—08 榜单入选优先 · 木卫四与月球靠后分配</span><span>NASA 满盘影像 / 地图球体 · 编号为界面顺序</span></div>
    <div class="moon-atlas-grid">
      <article v-for="(moon,index) in SATELLITES" :key="moon.model" :style="{'--satellite-accent':moon.color}">
        <div class="moon-atlas-number"><span>{{ String(index+1).padStart(2,'0') }}</span><small>{{ moon.parent }}</small></div>
        <SatelliteGlyph :index="index"/>
        <p class="moon-atlas-english">{{ moon.model }}<span>{{ moon.priority }}</span></p><h3>{{ moon.name }}<span>{{ moon.theme }}</span></h3><p class="moon-atlas-description">{{ moon.description }}</p>
        <details><summary>{{ moon.imageType }} <span>影像来源 ↗</span></summary><p>{{ moon.credit }}</p><p v-if="moon.globe">依据公开表面地图绘制的正面受光球体，并非此视角的原始照片。{{ moon.model === 'TRITON' ? '海卫一采用南极朝前的已覆盖区域。' : moon.model === 'CHARON' ? '冥卫一采用北极朝前的已覆盖区域。' : '' }}</p><a :href="moon.source" target="_blank" rel="noopener noreferrer">查看 NASA 原始说明 ↗</a></details>
      </article>
    </div>
    <footer>完整轮廓优先，地图球体采用正面受光。真实地表的深浅纹理与不规则外形予以保留；增强色、照片与地图渲染分别标注。</footer>
  </dialog>
</template>
