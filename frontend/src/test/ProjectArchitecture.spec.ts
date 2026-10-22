import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import App from '../App.vue'
import WorkspaceHome from '../components/WorkspaceHome.vue'
import ConfigurationPanel from '../components/ConfigurationPanel.vue'
import CollectedExportPanel from '../components/CollectedExportPanel.vue'
import { SyntheticMockAdapter } from '../adapters/syntheticMockAdapter'
import { TestConfigurationAdapter, TEST_PROJECT } from './configurationFixture'

const projects = [{id:'a',name:'吹风机',enabled:true},{id:'b',name:'示例项目',enabled:true}]
describe('project satellite architecture', () => {
  it('renders one satellite per project and enters the selected project without querying', async () => {
    const adapter=new SyntheticMockAdapter(projects), top=vi.spyOn(adapter,'fetchTop'), review=vi.spyOn(adapter,'fetchReview')
    const page=mount(App,{props:{adapter}});await flushPromises()
    expect(page.findAll('.project-satellite')).toHaveLength(2)
    expect(page.find('.cosmic-command').exists()).toBe(false)
    expect(page.find('.cosmic-legend').exists()).toBe(false)
    await page.get('[aria-label="示例项目：每日TOP素材"]').trigger('click');await flushPromises()
    expect(page.get('.workspace-header h2').text()).toBe('示例项目')
    expect(page.get('.daily-panel').isVisible()).toBe(true)
    expect(page.get('.review-panel').isVisible()).toBe(false)
    expect(top).not.toHaveBeenCalled();expect(review).not.toHaveBeenCalled();page.unmount()
  })
  it('opens each project period shortcut directly and retains that project state without calculation', async () => {
    const adapter=new SyntheticMockAdapter(projects), top=vi.spyOn(adapter,'fetchTop'), review=vi.spyOn(adapter,'fetchReview')
    const page=mount(App,{props:{adapter},attachTo:document.body});await flushPromises()
    expect(page.get('[data-project-id="a"] .satellite-label').text()).toBe('吹风机每日TOP素材周期数据')
    expect(page.find('.satellite-meta').exists()).toBe(false)
    await page.get('[aria-label="示例项目：周期数据"]').trigger('click');await flushPromises()
    expect(page.get('.workspace-header h2').text()).toBe('示例项目')
    expect(page.get('.review-panel').isVisible()).toBe(true)
    expect(page.get('.daily-panel').isVisible()).toBe(false)
    await page.findAll('.global-nav button')[0].trigger('click');await flushPromises()
    await page.get('[aria-label="吹风机：每日TOP素材"]').trigger('click');await flushPromises()
    expect(page.get('.workspace-header h2').text()).toBe('吹风机')
    expect(page.get('.daily-panel').isVisible()).toBe(true)
    expect(top).not.toHaveBeenCalled();expect(review).not.toHaveBeenCalled();page.unmount()
  })
  it('adds matching satellites with deterministic distinct models up to sixteen, without empty placeholders', async () => {
    const page=mount(WorkspaceHome,{props:{projects,selectedProjectId:'a',projectName:'吹风机',canOperate:true,top:null,review:null},global:{stubs:{StarScene:true}}})
    expect(page.findAll('[data-satellite-number]').map(node=>node.attributes('data-satellite-number'))).toEqual(['1','2'])
    await page.setProps({projects:Array.from({length:16},(_,i)=>({id:`p${i}`,name:`项目${i}`,enabled:true}))})
    expect(page.findAll('.project-satellite')).toHaveLength(16)
    expect(new Set(page.findAll('.project-satellite [data-satellite-model]').map(node=>node.attributes('data-satellite-model'))).size).toBe(16)
    await page.setProps({projects:[]});expect(page.findAll('.project-satellite')).toHaveLength(0)
    expect(page.get('.satellite-empty').text()).toContain('等待第一个项目');page.unmount()
  })
  it('disables further project creation at the confirmed thirty-two-project limit and guards the add action', async () => {
    const adapter=new TestConfigurationAdapter()
    adapter.snapshot.projects=Array.from({length:32},(_,i)=>({...TEST_PROJECT,id:`p${i}`,name:`项目${i}`,advertiserId:`999999999999999901${String(i).padStart(2,'0')}`,douyinIds:[`999999999999999902${String(i).padStart(2,'0')}`]}))
    const panel=mount(ConfigurationPanel,{props:{adapter}});await flushPromises()
    expect(panel.get('.configuration-add').attributes('disabled')).toBeDefined()
    await panel.get('.configuration-add').trigger('click')
    expect(panel.findAll('[data-config-row]')).toHaveLength(32)
    expect(panel.text()).toContain('最多 32 个项目');panel.unmount()
  })
  it('opens exports from daily materials, closes them without querying, and has no duplicate business navigation', async () => {
    const adapter=new SyntheticMockAdapter(projects), top=vi.spyOn(adapter,'fetchTop'), review=vi.spyOn(adapter,'fetchReview')
    const page=mount(App,{props:{adapter}});await flushPromises()
    await page.findAll('.global-nav button')[1].trigger('click')
    expect(page.find('.dashboard-tabs').exists()).toBe(false)
    expect(page.find('.constellation-band').exists()).toBe(false)
    expect(page.findAll('.operations-nav button').some(button=>button.text().includes('数据下载'))).toBe(false)
    await page.get('.download-data-button').trigger('click');await flushPromises()
    expect(page.findComponent(CollectedExportPanel).props('active')).toBe(true)
    await page.get('[aria-label="关闭下载数据面板"]').trigger('click')
    expect(page.findComponent(CollectedExportPanel).props('active')).toBe(false)
    expect(top).not.toHaveBeenCalled();expect(review).not.toHaveBeenCalled();page.unmount()
  })
})
