import type { NumericResult } from './dashboard'
export function comparisonClass(result?:NumericResult):string {
  if(result?.status!=='VALID'||result.value===null||!Number.isFinite(result.value))return ''
  return result.value>1?'comparison-up':result.value<1?'comparison-down':'comparison-neutral'
}
