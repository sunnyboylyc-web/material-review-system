import { vi } from 'vitest'
import { REQUIRED_METRICS } from '../domain/configuration'
import type { ConfigurationAdapter, ConfigurationCandidate, ConfigurationProject, ConfigurationSnapshot } from '../domain/configuration'

export const TEST_PROJECT: ConfigurationProject = {
  category: 'OTHER',
  id: 'sampleproject', name: '合成示例项目', advertiserId: '99999999999999990001', douyinIds: ['99999999999999990002'], enabled: true,
}

export function metricConfigurationFixture(): ConfigurationSnapshot {
  const metricCatalog = [...REQUIRED_METRICS, ...Array.from({ length: 23 }, (_, index) => `synthetic_metric_${index}`)]
    .map(id => ({ id, label: `合成指标 ${id}`, required: REQUIRED_METRICS.includes(id) }))
  return { ...configurationFixture(), metricChangesEnabled: true, metricCatalog, enabledMetrics: metricCatalog.map(metric => metric.id) }
}
export function configurationFixture(projects: ConfigurationProject[] = [TEST_PROJECT]): ConfigurationSnapshot {
  return { schemaVersion: 'project-configuration-v1', revision: 'a'.repeat(64), writeEnabled: true,
    collectorExecution: 'DISABLED', metricProfile: 'material-33-v1', metricChangesEnabled: false,
    projects: structuredClone(projects) }
}

/** In-memory fabricated configuration; never stores identities in browser persistent storage. */
export class TestConfigurationAdapter implements ConfigurationAdapter {
  readonly evidenceKind = 'SYNTHETIC' as const
  snapshot = configurationFixture()
  private sequence = 1
  read = vi.fn(async () => structuredClone(this.snapshot))
  resolveAnchor = vi.fn(async (input: { advertiserId: string; publicDouyinId: string }) => ({ ...input, awemeId: '99999999999999990004', awemeName: '合成抖音账号' }))
  preflight = vi.fn(async (candidate: ConfigurationCandidate) => ({
    status: 'READY' as const, expectedRevision: candidate.expectedRevision, candidateHash: 'b'.repeat(64),
  }))
  apply = vi.fn(async (candidate: ConfigurationCandidate & { candidateHash: string }) => {
    const previousRevision = this.snapshot.revision
    this.snapshot = { ...this.snapshot, revision: (++this.sequence).toString(16).padStart(2, '0').repeat(32), projects: structuredClone(candidate.projects),
      ...(candidate.enabledMetrics ? { enabledMetrics: [...candidate.enabledMetrics] } : {}) }
    return { status: 'CURRENT_VERIFIED' as const, revision: this.snapshot.revision, previousRevision, collectorExecution: 'DISABLED' as const }
  })
}
