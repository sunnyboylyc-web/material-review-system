import { createApp } from 'vue'
import App from '../App.vue'
import { httpConfigurationAdapter } from '../adapters/httpConfigurationAdapter'
import type { ConfigurationAdapter } from '../domain/configuration'
import '../styles.css'
import './preview.css'

const rejectWrite = async (): Promise<never> => { throw new Error('视觉预览为只读；请返回正式页面维护配置。') }
const configurationAdapter: ConfigurationAdapter = {
  evidenceKind: 'CURRENT_FACTS',
  async read() { return { ...await httpConfigurationAdapter.read(), writeEnabled: false } },
  resolveAnchor: rejectWrite,
  preflight: rejectWrite,
  apply: rejectWrite,
}
createApp(App, { configurationAdapter, historyReadOnly: true }).mount('#app')
