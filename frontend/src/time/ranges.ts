import type { DateRange } from '../domain/dashboard'
import { shanghaiIsoDate, shiftIsoDate } from './shanghai'
export const RANGE_PRESETS = ['今天','昨天','最近3天','最近7天','最近15天','最近30天','上周','本周','本月','上月'] as const
export type RangePreset = typeof RANGE_PRESETS[number]
export function presetRange(key: RangePreset, now = new Date()): DateRange {
  const today = shanghaiIsoDate(now), yesterday = shiftIsoDate(today,-1)
  if(key==='今天')return {start:today,end:today}
  if(key==='昨天')return {start:yesterday,end:yesterday}
  if(key.startsWith('最近'))return {start:shiftIsoDate(yesterday,1-Number(key.replace(/\D/g,''))),end:yesterday}
  const date=new Date(`${today}T00:00:00Z`)
  if(key==='本周'||key==='上周'){
    const monday=shiftIsoDate(today,-((date.getUTCDay()+6)%7)+(key==='上周'?-7:0))
    return {start:monday,end:shiftIsoDate(monday,6)}
  }
  const offset=key==='上月'?-1:0
  return {start:new Date(Date.UTC(date.getUTCFullYear(),date.getUTCMonth()+offset,1)).toISOString().slice(0,10),end:new Date(Date.UTC(date.getUTCFullYear(),date.getUTCMonth()+offset+1,0)).toISOString().slice(0,10)}
}
export function validRange(range: DateRange): boolean {
  const valid=(value:string)=>value>='1900-01-01'&&value<='2200-12-31'&&/^\d{4}-\d{2}-\d{2}$/.test(value)&&Number.isFinite(Date.parse(value))&&new Date(`${value}T00:00:00Z`).toISOString().slice(0,10)===value
  return valid(range.start)&&valid(range.end)&&range.start<=range.end&&(Date.parse(range.end)-Date.parse(range.start))/86400000<3660
}
