import type { CreationDate, NumericResult } from './dashboard'

export type FilterValue = NumericResult | CreationDate
export interface FilterColumn { key: string; label: string; kind: 'number' | 'percent' | 'date' }
export interface ColumnCondition {
  operator: 'all' | 'eq' | 'ne' | 'gt' | 'gte' | 'lt' | 'lte' | 'between'
  from: string
  to: string
  selected?: string[]
}
export interface MaterialFilters { search: string; columns: Record<string, ColumnCondition> }
export interface FilterRecord { materialId: string; materialName: string | null; values: Record<string, FilterValue> }
export interface FilterOption { key: string; label: string }
export const emptyFilters = (): MaterialFilters => ({ search: '', columns: {} })
export const emptyCondition = (): ColumnCondition => ({ operator: 'all', from: '', to: '' })
export function filterToken(value: FilterValue): string {
  return 'value' in value ? `${value.status}:${value.value ?? ''}` : `${value.status}:${value.date ?? ''}`
}
export function conditionActive(value?: ColumnCondition): boolean { return !!value && (value.operator !== 'all' || value.selected !== undefined) }
export function filtersActive(filters: MaterialFilters): boolean { return !!filters.search.trim() || Object.values(filters.columns).some(conditionActive) }
function operand(value: FilterValue): number | string | null {
  if ('value' in value) return value.status === 'VALID' ? value.value : null
  return value.status === 'VERIFIED' ? value.date : null
}
export function conditionError(condition: ColumnCondition, column: FilterColumn): string {
  if (condition.operator === 'all') return ''
  const valid = (s: string) => column.kind === 'date' ? /^\d{4}-\d{2}-\d{2}$/.test(s) && Number.isFinite(Date.parse(s)) && new Date(s+'T00:00:00Z').toISOString().slice(0,10) === s : !!String(s).trim() && Number.isFinite(Number(s))
  if (!valid(condition.from) || condition.operator === 'between' && !valid(condition.to)) return '请输入有效的筛选值。'
  const a = column.kind === 'date' ? condition.from : Number(condition.from), b = column.kind === 'date' ? condition.to : Number(condition.to)
  return condition.operator === 'between' && a > b ? '区间开始值不能大于结束值。' : ''
}
export function matchesCondition(value: FilterValue, condition: ColumnCondition | undefined, column: FilterColumn): boolean {
  if (!condition) return true
  if (condition.selected !== undefined && !condition.selected.includes(filterToken(value))) return false
  if (condition.operator === 'all') return true
  if (conditionError(condition,column)) return false
  const actual = operand(value)
  // Unknown, zero denominator and inapplicable dates are never numeric zero.
  if (actual === null) return false
  const scale = column.kind === 'percent' ? 100 : 1
  const from = column.kind === 'date' ? condition.from : Number(condition.from)/scale
  const to = column.kind === 'date' ? condition.to : Number(condition.to)/scale
  switch(condition.operator) {
    case 'eq': return actual === from
    case 'ne': return actual !== from
    case 'gt': return actual > from
    case 'gte': return actual >= from
    case 'lt': return actual < from
    case 'lte': return actual <= from
    case 'between': return actual >= from && actual <= to
  }
}
export function matchingMaterialIds(rows: FilterRecord[], filters: MaterialFilters, columns: FilterColumn[]): Set<string> {
  const search=filters.search.trim().toLocaleLowerCase()
  return new Set(rows.filter(row => (!search || row.materialId.toLocaleLowerCase().includes(search) || row.materialName?.toLocaleLowerCase().includes(search))
    && columns.every(column => matchesCondition(row.values[column.key],filters.columns[column.key],column))).map(row=>row.materialId))
}
export function filterOptions(rows: FilterRecord[], column: FilterColumn): FilterOption[] {
  const values=new Map<string,FilterOption>()
  for(const row of rows) {
    const value=row.values[column.key], key=filterToken(value)
    if(values.has(key)) continue
    const raw=operand(value)
    const label=raw===null ? value.status==='ZERO_DENOMINATOR' ? '分母为0' : value.status==='NOT_APPLICABLE' ? '不适用' : '数据未知'
      : column.kind==='percent' ? `${Number(raw)*100}%` : String(raw)
    values.set(key,{key,label})
  }
  return [...values.values()]
}
