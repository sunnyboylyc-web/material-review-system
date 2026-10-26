import { computed, ref, type Ref } from 'vue'
import { emptyFilters, filterOptions, filtersActive, matchingMaterialIds, type ColumnCondition, type FilterColumn, type FilterRecord, type MaterialFilters } from '../domain/materialFilters'

export function useMaterialDetailFilters<T>(selected: Ref<string | null>, source: Ref<T[]>, project: (row: T) => FilterRecord, columns: FilterColumn[]) {
  const saved=ref<Record<string,MaterialFilters>>({})
  const filters=computed({get:()=>saved.value[selected.value ?? ''] ?? emptyFilters(),set:(value: MaterialFilters)=>{if(selected.value)saved.value[selected.value]=value}})
  const records=computed(()=>source.value.map(project))
  const ids=computed(()=>matchingMaterialIds(records.value,filters.value,columns))
  const matched=computed(()=>source.value.filter(row=>ids.value.has(project(row).materialId)))
  const active=computed(()=>filtersActive(filters.value))
  function setColumn(key:string,value:ColumnCondition|undefined){const next={...filters.value.columns};if(value)next[key]=value;else delete next[key];filters.value={...filters.value,columns:next}}
  return {filters,matched,active,setColumn,options:(column:FilterColumn)=>filterOptions(records.value,column),reset:()=>{saved.value={}}}
}
