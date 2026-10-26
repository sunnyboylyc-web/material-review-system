import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ConfigurationPanel from '../components/ConfigurationPanel.vue'
import { TestConfigurationAdapter, TEST_PROJECT } from './configurationFixture'
import { SATELLITES, satellitePreset } from '../visual/satellites'

describe('business capacity independent of visual presets', () => {
  it('allows project 17 through 32 and blocks 33, retaining deleted slots', async () => {
    const adapter = new TestConfigurationAdapter()
    adapter.snapshot.projects = Array.from({ length: 16 }, (_, i) => ({ ...TEST_PROJECT,
      id: `synthetic-${i}`, advertiserId: `999999${i}`, deleted: i === 0, enabled: i !== 0 }))
    const page = mount(ConfigurationPanel, { props: { adapter } }); await flushPromises()
    const add = page.get('.configuration-add')
    for (let count = 16; count < 32; count++) {
      expect(add.attributes('disabled')).toBeUndefined()
      await add.trigger('click')
    }
    expect(page.findAll('[aria-label^="采集项目 "][aria-label$=" 名称"]')).toHaveLength(32)
    expect(add.attributes('disabled')).toBeDefined()
    await add.trigger('click')
    expect(page.findAll('[aria-label^="采集项目 "][aria-label$=" 名称"]')).toHaveLength(32)
    expect(page.text()).toContain('最多 32 个项目')
    expect(page.text()).toContain('删除不会腾出新增名额')
    expect(adapter.apply).not.toHaveBeenCalled()
    expect(SATELLITES).toHaveLength(16)
    expect(satellitePreset(16)).toBe(satellitePreset(0))
    expect(satellitePreset(31)).toBe(satellitePreset(15))
    page.unmount()
  })
})
