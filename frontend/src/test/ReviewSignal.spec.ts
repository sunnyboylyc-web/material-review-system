import { describe, expect, it } from 'vitest'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
import { reviewSignal } from '../domain/reviewSignal'
const zero = { status: 'ZERO_DENOMINATOR' as const, value: null, partial: false, partialReasons: [] }
async function complete() {
  const result = await new SyntheticMockAdapter().fetchReview('sample-project', {current:{start:'2026-10-01',end:'2026-10-01'},previous:{start:'2026-09-01',end:'2026-09-01'}},20)
  result.metricsState='READY';result.topMaterials=[]
  for(const period of result.periods){period.pendingMaterialCount=0;period.coverage={state:'COMPLETE',expectedDays:1,coveredDays:1,missingDates:[]};for(const group of period.groups)for(const key of Object.keys(group.metrics) as (keyof typeof group.metrics)[])group.metrics[key]=zero}
  return result
}
describe('period status signal',()=>{
  it('keeps no-result and loading neutral, and complete zero denominators green despite incomparable comparisons',async()=>{
    const result=await complete();result.comparisons[0].metrics.roi={status:'UNKNOWN_INPUT',value:null}
    expect(reviewSignal(null)).toBe('loading');expect(reviewSignal(result,true)).toBe('loading');expect(reviewSignal(result)).toBe('green')
  })
  it('marks missing days, pending classification, partial values and missing fields red',async()=>{
    for(const mutate of [
      (r:Awaited<ReturnType<typeof complete>>)=>{r.periods[0].coverage.state='PARTIAL'},
      (r:Awaited<ReturnType<typeof complete>>)=>{r.periods[0].pendingMaterialCount=1},
      (r:Awaited<ReturnType<typeof complete>>)=>{r.periods[0].groups[0].metrics.cpc={status:'VALID',value:2,partial:true}},
      (r:Awaited<ReturnType<typeof complete>>)=>{r.periods[0].groups[0].metrics.ctr={status:'UNKNOWN_INPUT',value:null}},
    ]){const result=await complete();mutate(result);expect(reviewSignal(result)).toBe('red')}
  })
})
