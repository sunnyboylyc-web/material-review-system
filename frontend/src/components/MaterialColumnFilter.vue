<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { conditionActive, conditionError, emptyCondition, type ColumnCondition, type FilterColumn, type FilterOption } from '../domain/materialFilters'
const props=defineProps<{ column: FilterColumn; modelValue?: ColumnCondition; options: FilterOption[]; disabled?: boolean }>()
const emit=defineEmits<{ 'update:modelValue': [value: ColumnCondition | undefined] }>()
const root=ref<HTMLDetailsElement>(), draft=ref<ColumnCondition>(emptyCondition()), search=ref(''), shown=ref(200), error=ref(''), menuStyle=ref<Record<string,string>>({})
const found=computed(()=>props.options.filter(option=>option.label.toLocaleLowerCase().includes(search.value.trim().toLocaleLowerCase())))
const visible=computed(()=>found.value.slice(0,shown.value))
const selected=(key:string)=>draft.value.selected===undefined || draft.value.selected.includes(key)
function begin(){const rect=root.value?.getBoundingClientRect();menuStyle.value={left:Math.max(12,Math.min((rect?.right ?? 300)-280,window.innerWidth-304))+'px',top:Math.max(12,Math.min((rect?.bottom ?? 12)+8,window.innerHeight-490))+'px'};draft.value=JSON.parse(JSON.stringify(props.modelValue ?? emptyCondition()));search.value='';shown.value=200;error.value=''}
function toggle(key:string){const values=new Set(draft.value.selected ?? props.options.map(option=>option.key));if(values.has(key))values.delete(key);else values.add(key);draft.value.selected=[...values]}
function apply(){error.value=conditionError(draft.value,props.column);if(error.value)return;emit('update:modelValue',conditionActive(draft.value)?JSON.parse(JSON.stringify(draft.value)):undefined);if(root.value)root.value.open=false}
function clear(){emit('update:modelValue',undefined);if(root.value)root.value.open=false}
function outside(event:PointerEvent){if(root.value?.open&&!root.value.contains(event.target as Node))root.value.open=false}
onMounted(()=>document.addEventListener('pointerdown',outside));onBeforeUnmount(()=>document.removeEventListener('pointerdown',outside))
</script>
<template>
 <details ref="root" class="column-filter" :class="{'is-filtered':conditionActive(modelValue),'is-disabled':disabled}" @keydown.esc.stop.prevent="root && (root.open=false)">
  <summary :aria-label="`筛选${column.label}`" :aria-disabled="disabled" @click="event=>{if(disabled){event.preventDefault();return}if(!root?.open)begin()}">{{ column.label }}<span aria-hidden="true">⏷</span></summary>
  <div class="column-filter-menu" :style="menuStyle" role="group" :aria-label="`${column.label}筛选条件`">
   <strong>{{ column.label }}筛选</strong>
   <label>条件<select v-model="draft.operator" :aria-label="`${column.label}筛选方式`"><option value="all">不限</option><option value="eq">等于</option><option value="ne">不等于</option><option value="gt">大于</option><option value="gte">大于或等于</option><option value="lt">小于</option><option value="lte">小于或等于</option><option value="between">介于（含首尾）</option></select></label>
   <div v-if="draft.operator!=='all'" class="column-filter-inputs"><input v-model="draft.from" :type="column.kind==='date'?'date':'number'" step="any" :aria-label="`${column.label}筛选值`" :placeholder="column.kind==='percent'?'例如2表示2%':'输入数值'"/><span v-if="column.kind==='percent'">%</span><template v-if="draft.operator==='between'"><span>至</span><input v-model="draft.to" :type="column.kind==='date'?'date':'number'" step="any" :aria-label="`${column.label}筛选结束值`"/><span v-if="column.kind==='percent'">%</span></template></div>
   <label>具体值<input v-model="search" type="search" :aria-label="`搜索${column.label}筛选值`" placeholder="搜索可选值" @input="shown=200"/></label>
   <div class="column-filter-choice-actions"><button type="button" @click="draft.selected=undefined">全选</button><button type="button" @click="draft.selected=[]">全不选</button><small>共{{ found.length }}项</small></div>
   <div class="column-filter-values"><label v-for="option in visible" :key="option.key" :title="option.label"><input type="checkbox" :checked="selected(option.key)" @change="toggle(option.key)"/><span>{{ option.label }}</span></label><p v-if="!found.length">没有匹配的选项</p><button v-if="visible.length<found.length" type="button" @click="shown+=200">显示更多可选值</button></div>
   <p v-if="error" class="form-error" role="alert">{{ error }}</p>
   <footer><button type="button" @click="clear">清除此列</button><button type="button" class="apply-filter" @click="apply">应用</button></footer>
  </div>
 </details>
</template>
<style scoped>
.column-filter{position:relative;display:inline-block;text-align:left;font-weight:400}.column-filter>summary{display:flex;align-items:center;gap:9px;list-style:none;cursor:pointer;font-weight:600;white-space:nowrap}.column-filter>summary::-webkit-details-marker{display:none}.column-filter>summary span{color:#a6a9bb;font-size:12px}.column-filter.is-filtered>summary{color:#f1d58b}.column-filter.is-disabled>summary{opacity:.5;cursor:wait}.column-filter-menu{position:fixed;max-height:80vh;overflow:auto;z-index:80;width:280px;padding:16px;border:1px solid #53566c;border-radius:10px;background:#181c2d;color:#e7e6e0;box-shadow:0 16px 50px #0009;font-size:13px;white-space:normal}.column-filter-menu>label{display:grid;gap:7px;margin-top:12px}.column-filter-menu :is(input:not([type=checkbox]),select){min-width:0;width:100%;border:1px solid #464b61;background:#101421;color:#e9e8df;border-radius:6px;padding:8px;font:inherit;box-sizing:border-box}.column-filter-inputs{display:flex;gap:6px;align-items:center;margin-top:8px}.column-filter-choice-actions{display:flex;gap:12px;align-items:center;margin:12px 0}.column-filter-choice-actions small{margin-left:auto;color:#a3acba}.column-filter-menu button{border:1px solid #4b5066;background:#252a40;color:#dbdce6;border-radius:5px;padding:6px 9px;cursor:pointer;font:inherit}.column-filter-choice-actions button{border:0;padding:0;background:none;color:#d5c58f}.column-filter-values{max-height:190px;overflow:auto;border-block:1px solid #363c50;padding:6px 0}.column-filter-values label{display:flex;align-items:center;gap:9px;padding:7px 2px;word-break:break-all}.column-filter-values input{accent-color:#d4bc7c;flex-shrink:0}.column-filter-menu footer{display:flex;justify-content:space-between;gap:10px;margin-top:14px}.column-filter-menu .apply-filter{background:#d2bb7d;color:#191b25}.column-filter-menu :focus-visible{outline:2px solid #e0c881;outline-offset:2px}
</style>
