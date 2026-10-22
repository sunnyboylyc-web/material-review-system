import { describe, expect, it } from 'vitest'
import { formatMaterialLabel } from '../domain/formatting'

describe('material ID display label', () => {
  it('labels exact long string IDs without numeric conversion', () => {
    expect(formatMaterialLabel('9999999999999900082', null)).toBe('素材ID 9999999999999900082')
  })
  it('removes display whitespace without mutating the identity', () => {
    const identity = ' 9999 9999\t99999900082 '
    expect(formatMaterialLabel(identity, null)).toBe('素材ID 9999999999999900082')
    expect(identity).toBe(' 9999 9999\t99999900082 ')
  })
  it('retains the existing AIGC display exception and scientific text', () => {
    expect(formatMaterialLabel('-2', 'AIGC动态创意素材集合')).toBe('素材ID -')
    expect(formatMaterialLabel('7.67044E+18', null)).toBe('素材ID 7.67044E+18')
  })
})
