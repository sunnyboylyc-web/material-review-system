import { flushPromises, type VueWrapper } from '@vue/test-utils'

/** Presentation tests explicitly request results; opening the application no longer does so. */
export async function refreshDashboard(wrapper: VueWrapper) {
  if (wrapper.find('.date-form--review').exists()) await wrapper.get('.date-form--review').trigger('submit')
  if (wrapper.find('.date-form--top').exists()) await wrapper.get('.date-form--top').trigger('submit')
  await flushPromises()
}
