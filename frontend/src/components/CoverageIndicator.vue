<script setup lang="ts">
import type { Coverage } from '../domain/dashboard'
import CoverageStatus from './CoverageStatus.vue'
defineProps<{ coverage: Coverage; label: string }>()
</script>

<template>
  <details class="coverage-indicator" :class="coverage.state === 'COMPLETE' ? 'is-complete' : 'is-partial'">
    <summary :aria-label="`${label}：${coverage.state === 'COMPLETE' ? '完整' : '缺数'}，${coverage.coveredDays}/${coverage.expectedDays}天，查看说明`">
      <span class="signal" aria-hidden="true"></span>
      <strong>{{ label }}</strong>
      <span>{{ coverage.state === 'COMPLETE' ? '完整' : '缺数' }} {{ coverage.coveredDays }}/{{ coverage.expectedDays }} 天</span>
      <span class="coverage-indicator__hint">详情 ▾</span>
    </summary>
    <CoverageStatus :coverage="coverage" />
  </details>
</template>
