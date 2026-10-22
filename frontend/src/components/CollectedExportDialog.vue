<script setup lang="ts">
import { nextTick, ref } from 'vue'
import CollectedExportPanel from './CollectedExportPanel.vue'
import type { CollectedExportAdapter } from '../domain/collectedExport'

defineProps<{ projectId: string; projects: { id: string; name: string }[]; readOnly?: boolean; adapter?: CollectedExportAdapter }>()
const emit = defineEmits<{ selectProject: [projectId: string] }>()
const opened = ref(false)
const busy = ref(false)
const dialog = ref<HTMLDialogElement | null>(null)
const trigger = ref<HTMLButtonElement | null>(null)
async function open(): Promise<void> {
  opened.value = true
  await nextTick()
  dialog.value?.showModal()
}
function close(): void { if (!busy.value) dialog.value?.close() }
function cancel(event: Event): void { if (busy.value) event.preventDefault() }
function closed(): void { opened.value = false; busy.value = false; trigger.value?.focus() }
</script>

<template>
  <button ref="trigger" type="button" class="export-trigger" aria-haspopup="dialog" @click="open">下载数据</button>
  <Teleport to="body">
    <dialog ref="dialog" class="export-dialog" aria-labelledby="export-dialog-title" @close="closed" @cancel="cancel">
      <header><h2 id="export-dialog-title">下载数据</h2><button type="button" autofocus :disabled="busy" @click="close">关闭下载面板</button></header>
      <p class="export-attention">提示：关闭面板不会更改已入库数据；正在生成下载时请等待完成，避免重复请求。</p>
      <CollectedExportPanel v-if="opened" :project-id="projectId" :projects="projects" :read-only="readOnly" :adapter="adapter" @busy="busy = $event" @select-project="emit('selectProject', $event)" />
    </dialog>
  </Teleport>
</template>

<style scoped>
.export-trigger, header button { min-height:44px; padding:8px 14px; border:1px solid var(--line); border-radius:8px; background:var(--surface); color:var(--ink); cursor:pointer; }
.export-dialog { width:min(1000px,calc(100% - 32px)); max-height:calc(100dvh - 40px); box-sizing:border-box; overflow:auto; border:1px solid var(--line); border-radius:16px; padding:20px; background:var(--surface); color:var(--ink); }
.export-dialog::backdrop { background:rgb(15 30 40 / 60%); }
header { display:flex; align-items:center; justify-content:space-between; gap:16px; flex-wrap:wrap; }
header h2 { margin:0; }
.export-attention { color:var(--danger, #b42318); line-height:1.6; }
button:focus-visible { outline:2px solid var(--accent); outline-offset:3px; }
</style>
