import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import MarkdownRenderer from './MarkdownRenderer.vue'

describe('MarkdownRenderer', () => {
	it('removes executable link protocols while preserving formatted text', () => {
		const wrapper = mount(MarkdownRenderer, {
			props: {
				content:
					'**安全说明** [安全链接](https://example.org/guide) [打开](javascript:alert(1))',
			},
		})

		expect(wrapper.find('strong').text()).toBe('安全说明')
		const links = wrapper.findAll('a')
		expect(links[0].attributes('href')).toBe('https://example.org/guide')
		expect(links[1].attributes('href')).toBeUndefined()
	})
})
