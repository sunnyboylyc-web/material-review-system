import type { CreationDate, FirstSpend, NumericResult } from './dashboard'

export type NumberFormat = 'decimal' | 'integer' | 'percent'

export function formatMetric(result: NumericResult | undefined, format: NumberFormat = 'decimal'): string {
  if (!result) return '—（未查询）'
  if (result.status === 'UNKNOWN_INPUT') return '—（数据未知）'
  if (result.status === 'ZERO_DENOMINATOR') return '—（分母为 0）'
  if (result.value === null || !Number.isFinite(result.value)) return '—（结果无效）'
  const value = format === 'percent' ? result.value * 100 : result.value
  const decimals = format === 'integer' ? 0 : 2
  return `${value.toLocaleString('zh-CN', { minimumFractionDigits: decimals, maximumFractionDigits: decimals })}${format === 'percent' ? '%' : ''}`
}

export function formatFirstSpend(value: FirstSpend): string {
  return value.status === 'VERIFIED' && value.date ? value.date : '待核（首次消耗证据不足）'
}

export function formatCreationDate(value: CreationDate): string {
  if (value.status === 'NOT_APPLICABLE') return '不适用'
  if (value.status === 'VERIFIED' && value.date) return value.date
  return value.reason === 'CONFLICTING_CREATION_DATE' ? '待核（创建日期冲突）' : value.reason === 'CREATION_AFTER_OBSERVATION' ? '待核（创建日期晚于事实日）' : '待核（创建日期缺失）'
}

/** Display-only legacy AIGC exception; the wire ID and ranking keys remain unchanged. */
export function formatMaterialId(materialId: string, materialName: string | null): string {
  return materialId === '-2'
    && (materialName === 'AIGC动态创意素材集合' || materialName === 'AIGC动态创意视频素材集合')
    ? '-' : materialId
}

/** Presentation only: never use this label as a lookup key or persisted ID. */
export function formatMaterialLabel(materialId: string, materialName: string | null): string {
  return `素材ID ${formatMaterialId(materialId, materialName).replace(/\s+/g, '')}`
}
