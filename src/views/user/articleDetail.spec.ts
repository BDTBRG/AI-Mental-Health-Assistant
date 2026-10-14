import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ArticleDetail from './articleDetail.vue'
import { getKnowledgeArticleDetail } from '@/api/user'

vi.mock('@/api/user', () => ({
	getKnowledgeArticleDetail: vi.fn(),
}))

describe('articleDetail', () => {
	beforeEach(() => {
		vi.mocked(getKnowledgeArticleDetail).mockResolvedValue({
			content:
				'<p style="color:#ff0000;font-size:16px;background-image:url(javascript:alert(3))">可读内容</p>' +
				'<img src="x" onerror="alert(1)"><a href="javascript:alert(2)">恶意链接</a>',
		} as never)
	})

	it('renders article HTML without active elements or unsafe links', async () => {
		const wrapper = mount(ArticleDetail, {
			props: { id: '1' },
			global: {
				stubs: ['el-image', 'el-icon', 'el-tag', 'List', 'Platform'],
			},
		})
		await flushPromises()

		const content = wrapper.get('.content-wrapper')
		expect(content.find('img').exists()).toBe(false)
		expect(content.find('a').attributes('href')).toBeUndefined()
		expect(content.find('p').attributes('style')).toContain('color')
		expect(content.find('p').attributes('style')).not.toContain('background-image')
		expect(content.text()).toContain('可读内容')
	})
})
