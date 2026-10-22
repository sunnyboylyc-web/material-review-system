import { createApp } from 'vue'
import App from './App.vue'
import './styles.css'
import './dashboard-theme.css'
import { initializeSession } from './domain/session'

initializeSession().then(session => createApp(App, { session }).mount('#app')).catch(error => {
  const root = document.getElementById('app')
  if (root) root.textContent = error instanceof Error ? error.message : '页面暂时无法打开，请刷新重试。'
})
