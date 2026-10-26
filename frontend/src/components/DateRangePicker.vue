<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import type { DateRange } from '../domain/dashboard'
import { RANGE_PRESETS, presetRange, validRange, type RangePreset } from '../time/ranges'
import { shanghaiIsoDate, shiftIsoDate } from '../time/shanghai'
const props=defineProps<{modelValue:DateRange;label:string;disabled?:boolean}>()
const emit=defineEmits<{ 'update:modelValue':[value:DateRange]; confirm:[value:DateRange] }>()
const root=ref<HTMLElement>(),opened=ref(false),active=ref<'start'|'end'>('start'),draft=ref<DateRange>({...props.modelValue}),month=ref(props.modelValue.start.slice(0,7)),error=ref('')
let trigger:HTMLElement|null=null
const monthStart=(offset:number)=>{const [year,m]=month.value.split('-').map(Number);return new Date(Date.UTC(year,m-1+offset,1)).toISOString().slice(0,10)}
const calendars=computed(()=>[0,1].map(offset=>{const first=monthStart(offset), weekday=(new Date(first+'T00:00:00Z').getUTCDay()+6)%7;return {month:first.slice(0,7),days:Array.from({length:42},(_,i)=>shiftIsoDate(first,i-weekday))}}))
async function open(part:'start'|'end',event:Event){trigger=event.currentTarget as HTMLElement;if(!opened.value)draft.value={...props.modelValue};active.value=part;const candidate=draft.value[part];month.value=(validRange({start:candidate,end:candidate})?candidate:props.modelValue[part]).slice(0,7);error.value='';opened.value=true;await nextTick();root.value?.querySelector<HTMLInputElement>(`input[data-part="${part}"]`)?.focus()}
function cancel(){opened.value=false;error.value='';trigger?.focus()}
function commit(){if(!validRange(draft.value)){error.value='结束日期不能早于开始日期，且范围不得超过3660天。';return}const value={...draft.value};emit('update:modelValue',value);emit('confirm',value);cancel()}
function select(date:string){draft.value[active.value]=date;error.value='';if(active.value==='start'){active.value='end'}else commit()}
function preset(key:RangePreset){draft.value=presetRange(key);commit()}
function canNavigate(offset:number){const target=monthStart(offset).slice(0,7);return target>='1900-01'&&target<='2200-11'}
function navigate(offset:number){if(canNavigate(offset))month.value=monthStart(offset).slice(0,7)}
function outside(event:PointerEvent){if(opened.value&&!root.value?.contains(event.target as Node))cancel()}
function key(event:KeyboardEvent){if(event.key==='Escape'){event.preventDefault();cancel()}}
onMounted(()=>document.addEventListener('pointerdown',outside));onBeforeUnmount(()=>document.removeEventListener('pointerdown',outside))
</script>
<template>
 <div ref="root" class="range-picker" @keydown="key">
  <span class="range-picker-label">{{ label }}</span><div class="range-picker-fields"><button type="button" :disabled="disabled" :aria-label="`${label}开始日期`" :aria-expanded="opened" @click="open('start',$event)">{{ modelValue.start }}</button><span>—</span><button type="button" :disabled="disabled" :aria-label="`${label}结束日期`" :aria-expanded="opened" @click="open('end',$event)">{{ modelValue.end }} <span aria-hidden="true">▾</span></button></div>
  <div v-if="opened" class="range-popover" role="dialog" :aria-label="`${label}日期范围`">
   <aside><button v-for="presetKey in RANGE_PRESETS" :key="presetKey" type="button" @click="preset(presetKey)">{{ presetKey }}</button></aside>
   <section><div class="range-draft"><label :class="{active:active==='start'}">开始日期<input data-part="start" v-model="draft.start" type="date" min="1900-01-01" max="2200-12-31" :aria-label="`${label}草稿开始日期`" @focus="active='start'" @change="active='end'" /></label><span>—</span><label :class="{active:active==='end'}">结束日期<input data-part="end" v-model="draft.end" type="date" min="1900-01-01" max="2200-12-31" :aria-label="`${label}草稿结束日期`" @focus="active='end'" @change="commit" /></label><button type="button" aria-label="关闭日期选择" @click="cancel">×</button></div>
    <div class="range-month-nav"><div class="range-nav-buttons"><button type="button" aria-label="上一年" title="上一年" :disabled="!canNavigate(-12)" @click="navigate(-12)">«</button><button type="button" aria-label="前一个月" title="前一个月" :disabled="!canNavigate(-1)" @click="navigate(-1)">‹</button></div><span>{{ active==='start'?'请选择开始日期':'请选择结束日期' }}</span><div class="range-nav-buttons"><button type="button" aria-label="后一个月" title="后一个月" :disabled="!canNavigate(1)" @click="navigate(1)">›</button><button type="button" aria-label="下一年" title="下一年" :disabled="!canNavigate(12)" @click="navigate(12)">»</button></div></div>
    <div class="range-calendars"><div v-for="calendar in calendars" :key="calendar.month" class="range-calendar"><strong>{{ calendar.month.replace('-','年') }}月</strong><div class="range-week"><span v-for="day in ['一','二','三','四','五','六','日']" :key="day">{{ day }}</span></div><div class="range-days"><button v-for="day in calendar.days" :key="day" type="button" :disabled="day < '1900-01-01' || day > '2200-12-31'" :aria-label="`${day}${day.slice(0,7)!==calendar.month?' 相邻月':''}`" :class="{outside:day.slice(0,7)!==calendar.month,selected:day===draft.start||day===draft.end,between:day>draft.start&&day<draft.end,today:day===shanghaiIsoDate()}" @click="select(day)">{{ Number(day.slice(8)) }}</button></div></div></div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
   </section>
  </div>
 </div>
</template>
<style scoped>
.range-nav-buttons{display:flex;gap:6px}.range-nav-buttons button{min-width:28px}.range-nav-buttons button:disabled{opacity:.3;cursor:not-allowed}
</style>
