const SHANGHAI_OFFSET_MS = 8 * 60 * 60 * 1000

function formatIsoDateFromUtc(date: Date): string {
  return [
    date.getUTCFullYear(),
    String(date.getUTCMonth() + 1).padStart(2, '0'),
    String(date.getUTCDate()).padStart(2, '0'),
  ].join('-')
}

export function shiftIsoDate(date: string, offsetDays: number): string {
  const shifted = new Date(`${date}T00:00:00.000Z`)
  shifted.setUTCDate(shifted.getUTCDate() + offsetDays)
  return formatIsoDateFromUtc(shifted)
}

export function shanghaiIsoDate(now = new Date(), offsetDays = 0): string {
  const shanghaiClock = new Date(now.getTime() + SHANGHAI_OFFSET_MS)
  shanghaiClock.setUTCDate(shanghaiClock.getUTCDate() + offsetDays)
  return formatIsoDateFromUtc(shanghaiClock)
}

export function enumerateIsoDates(start: string, end: string): string[] {
  const dates: string[] = []
  let cursor = start
  while (cursor <= end) {
    dates.push(cursor)
    cursor = shiftIsoDate(cursor, 1)
  }
  return dates
}

export function formatDateLabel(date: string): string {
  const parsed = new Date(`${date}T00:00:00.000Z`)
  const weekday = ['日', '一', '二', '三', '四', '五', '六'][parsed.getUTCDay()]
  return `${date.slice(5).replace('-', '/')} 周${weekday}`
}

export function defaultDateSelection(now = new Date()) {
  const yesterday = shanghaiIsoDate(now, -1)
  return {
    review: {
      current: { start: shiftIsoDate(yesterday, -6), end: yesterday },
      previous: { start: shiftIsoDate(yesterday, -13), end: shiftIsoDate(yesterday, -7) },
    },
    topDate: yesterday,
  }
}
