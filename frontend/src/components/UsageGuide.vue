<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import UiIcon from './UiIcon.vue'
import { accountsGuide, businessGuide, operationsGuide } from '../domain/usageGuide'

const props = defineProps<{ operations: boolean; administrator?: boolean; recoveryReadOnly?: boolean }>()
const sections = computed(() => props.operations
  ? [...operationsGuide, ...(props.administrator ? [accountsGuide] : [])] : businessGuide)
const selected = ref('start')
const current = computed(() => sections.value.find(section => section.id === selected.value) ?? sections.value[0])
const dialog = ref<HTMLDialogElement>()
const trigger = ref<HTMLButtonElement>()
const closeButton = ref<HTMLButtonElement>()
const article = ref<HTMLElement>()
const opened = ref(false)
watch(() => props.operations, () => { selected.value = sections.value[0].id })

async function open() {
  selected.value = sections.value[0].id
  dialog.value?.showModal()
  opened.value = true
  await nextTick()
  closeButton.value?.focus()
}
function closed() { opened.value = false; trigger.value?.focus() }
async function choose(id: string) {
  selected.value = id
  await nextTick()
  if (article.value) { article.value.scrollTop = 0; article.value.focus() }
}
function trapFocus(event: KeyboardEvent) {
  if (event.key !== 'Tab' || !dialog.value) return
  const items = [...dialog.value.querySelectorAll<HTMLElement>('button, [tabindex="0"]')]
  const first = items[0], last = items.at(-1)
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
</script>

<template>
  <div class="usage-guide">
    <button ref="trigger" type="button" class="usage-guide-trigger" aria-haspopup="dialog" :aria-expanded="opened" @click="open"><UiIcon name="book" :size="16"/>使用说明</button>
    <dialog ref="dialog" class="usage-guide-dialog" aria-labelledby="usage-guide-title" @close="closed" @keydown="trapFocus" @click="event => { if (event.target === dialog) dialog?.close() }">
      <header class="usage-guide-header">
        <div><p>{{ operations ? '运维指南' : '使用者指南' }}</p><h2 id="usage-guide-title">使用说明</h2></div>
        <button ref="closeButton" type="button" class="icon-button" aria-label="关闭使用说明" @click="dialog?.close()"><UiIcon name="close"/></button>
      </header>
      <p v-if="recoveryReadOnly" class="guide-recovery-note">当前为恢复验证只读模式，本说明中的写入操作暂不可用。</p>
      <div class="usage-guide-body">
        <nav aria-label="使用说明目录">
          <button v-for="(section, index) in sections" :key="section.id" type="button" :aria-current="current.id === section.id ? 'page' : undefined" @click="choose(section.id)"><span>{{ String(index + 1).padStart(2, '0') }}</span>{{ section.title }}</button>
        </nav>
        <article ref="article" tabindex="0" aria-live="polite" :aria-labelledby="`guide-${current.id}`">
          <h3 :id="`guide-${current.id}`">{{ current.title }}</h3>
          <p class="guide-intro">{{ current.intro }}</p>
          <ol><li v-for="step in current.steps" :key="step">{{ step }}</li></ol>
          <p v-if="current.note" class="guide-note">{{ current.note }}</p>
        </article>
      </div>
    </dialog>
  </div>
</template>

<style scoped>
.usage-guide-trigger { display:flex; align-items:center; gap:7px; min-height:36px; padding:7px 10px; border:1px solid #45445a; border-radius:7px; color:#d5c294; background:#151725; font-size:12px; white-space:nowrap; cursor:pointer; }
.usage-guide-trigger:hover { background:#242638; border-color:#8e7d55; }
.usage-guide-dialog { width:min(960px,94vw); max-width:94vw; max-height:88dvh; padding:0; border:1px solid #4c4c63; border-radius:16px; background:#101422; color:#dce1ef; box-shadow:0 30px 100px #0009; }
.usage-guide-dialog::backdrop { background:#03050abd; backdrop-filter:blur(4px); }
.usage-guide-header { display:flex; align-items:center; justify-content:space-between; gap:20px; padding:24px 28px; border-bottom:1px solid #30364b; }
.usage-guide-header p { margin:0 0 6px; color:#b7a67e; font-size:12px; }
.usage-guide-header h2 { margin:0; font-size:24px; color:#ece6d8; }
.usage-guide-header .icon-button { color:#d6dcec; background:#202639; border:1px solid #414960; border-radius:8px; width:36px; height:36px; }
.usage-guide-body { display:grid; grid-template-columns:235px minmax(0,1fr); min-height:390px; }
.usage-guide-body nav { padding:20px 14px; background:#0c101c; border-right:1px solid #30364b; }
.usage-guide-body nav button { display:flex; align-items:baseline; gap:10px; width:100%; text-align:left; border:1px solid transparent; border-radius:8px; background:none; color:#a8b3ca; padding:13px 10px; line-height:1.5; font-size:13px; cursor:pointer; }
.usage-guide-body nav button span { color:#817968; font:11px Consolas,monospace; }
.usage-guide-body nav button[aria-current] { color:#e3cd98; background:#292636; border-color:#5a503c; }
.usage-guide-body article { padding:28px 32px; max-height:calc(88dvh - 106px); overflow:auto; line-height:1.9; font-size:14px; }
.usage-guide-body h3 { color:#eee9df; margin:0 0 12px; font-size:21px; }
.guide-intro { color:#aab6cd; margin:0 0 24px; }
.usage-guide-body ol { margin:0; padding-left:22px; }
.usage-guide-body li { padding-left:5px; margin:0 0 17px; }
.usage-guide-body li::marker { color:#c7b483; }
.guide-note, .guide-recovery-note { padding:13px 16px; background:#2a2530; border-left:3px solid #9d8452; color:#d4c39e; font-size:13px; }
.guide-recovery-note { margin:0; }
button:focus-visible, article:focus-visible { outline:2px solid #dcc18a; outline-offset:-2px; }
@media(max-width:650px) {
  .usage-guide-body { grid-template-columns:1fr; }
  .usage-guide-body nav { display:flex; gap:6px; padding:10px; overflow:auto; border-right:0; border-bottom:1px solid #30364b; }
  .usage-guide-body nav button { width:auto; flex:none; padding:9px; font-size:12px; }
  .usage-guide-body article { max-height:calc(88dvh - 170px); padding:22px; }
  .usage-guide-header { padding:18px 22px; }
  .usage-guide-trigger { padding:6px 8px; font-size:11px; gap:4px; }
}
</style>
