<script setup lang="ts">
import type { Coverage } from '../domain/dashboard'

defineProps<{
  coverage: Coverage
  summaryOnly?: boolean
}>()
</script>

<template>
  <div
    class="coverage"
    :class="coverage.state === 'COMPLETE' ? 'coverage--complete' : 'coverage--partial'"
    :aria-label="coverage.state === 'COMPLETE' ? '数据完整' : '数据有缺失'"
  >
    <div class="coverage__headline">
      <span class="signal" aria-hidden="true"></span>
      <strong>{{ coverage.state === 'COMPLETE' ? '完整' : '缺数' }}</strong>
      <span>{{ coverage.coveredDays }} / {{ coverage.expectedDays }} 天</span>
    </div>
    <template v-if="summaryOnly && coverage.missingDates.length || coverage.missingDates.length > 10">
      <p class="coverage__missing">缺失 {{ coverage.missingDates.length }} 天 · 首日 {{ coverage.missingDates[0] }} · 末日 {{ coverage.missingDates.at(-1) }}</p>
      <details v-if="!summaryOnly" class="coverage__missing-details"><summary>展开全部缺失日期（{{ coverage.missingDates.length }} 天）</summary><p class="coverage__missing">{{ coverage.missingDates.join('、') }}</p></details>
    </template>
    <p v-else-if="coverage.missingDates.length" class="coverage__missing">
      缺失日期：{{ coverage.missingDates.join('、') }}
    </p>
    <p v-else class="coverage__complete-copy">所需账户日均有完整证据；不代表所有历史指标字段均可用。</p>
  </div>
</template>

<style scoped>
.coverage__missing-details{font-size:12px;margin-top:8px;max-width:100%;overflow-wrap:anywhere}
.coverage__missing-details summary{cursor:pointer}
.coverage__missing-details>p{max-height:220px;overflow-y:auto;padding-right:6px}
</style>
