<script setup lang="ts">
import { computed } from 'vue'
import type { NumericResult } from '../domain/dashboard'
import { formatMetric, type NumberFormat } from '../domain/formatting'
const props = defineProps<{ result?: NumericResult; format?: NumberFormat; hidePartial?: boolean }>()
const display = computed(() => formatMetric(props.result, props.format))
// Split the already formatted text only; keep precision, signs and units unchanged.
const parts = computed(() => display.value.match(/^(-?[\d,]+\.)(\d{2})(%?)$/))
</script>

<template>
  <span :class="{ 'metric-unknown': result?.status === 'UNKNOWN_INPUT', 'metric-undefined': result?.status === 'ZERO_DENOMINATOR' }" :aria-label="result?.partial ? `${display}，部分数据缺失，按已有匹配输入计算` : undefined">
    <template v-if="parts">{{ parts[1] }}<span class="metric-fraction">{{ parts[2] }}</span>{{ parts[3] }}</template><template v-else>{{ display }}</template>
  </span>
</template>

<style scoped>
.metric-fraction { font-size: 0.75em; vertical-align: baseline; }
</style>
