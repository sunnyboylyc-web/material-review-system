<script setup lang="ts">
withDefaults(defineProps<{ name: string; size?: number }>(), { size: 18 })
const paths: Record<string, string> = {
  book: 'M12 5C9 3 5 3 2 4v16c3-1 7-1 10 1 3-2 7-2 10-1V4c-3-1-7-1-10 1Zm0 0v16',
  star: 'm12 2 2.4 7.6L22 12l-7.6 2.4L12 22l-2.4-7.6L2 12l7.6-2.4Z',
  grid: 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z',
  chart: 'M4 3v17h17M8 14l4-5 4 3 5-8',
  compare: 'M4 7h15m-4-4 4 4-4 4M20 17H5m4-4-4 4 4 4',
  settings: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8M12 2v3m0 14v3M2 12h3m14 0h3M5 5l2 2m10 10 2 2M5 19l2-2M17 7l2-2',
  folder: 'M3 6h6l2 3h10v11H3ZM3 6V4h6l2 2h9v3',
  pulse: 'M2 12h5l3-7 4 14 3-7h5',
  arrow: 'M4 12h16m-6-6 6 6-6 6',
  down: 'm6 9 6 6 6-6',
  close: 'm5 5 14 14M5 19 19 5',
  search: 'M10.5 3a7.5 7.5 0 1 0 0 15 7.5 7.5 0 0 0 0-15M16 16l5 5',
  refresh: 'M20 8a8 8 0 0 0-14-3L3 8m0-5v5h5M4 16a8 8 0 0 0 14 3l3-3m0 5v-5h-5',
  user: 'M12 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8M4 21v-3a8 8 0 0 1 16 0v3',
  download: 'M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5',
  table: 'M3 4h18v16H3ZM3 9h18M8 4v16M3 14h18',
  moon: 'M20 15A9 9 0 0 1 9 3 9 9 0 1 0 20 15Z',
  shield: 'm12 2 9 4v6c0 5-9 10-9 10S3 17 3 12V6ZM8 12l3 3 5-6',
}
</script>
<template><svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name] ?? paths.star" /></svg></template>
