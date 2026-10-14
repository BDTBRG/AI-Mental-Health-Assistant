import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElMessageBox } from 'element-plus'
import { deleteEmotionLog, getEmotionlogPage } from '@/api/admin'
import EmotionalPage from './emotional.vue'

vi.mock('@/api/admin', () => ({
	getEmotionlogPage: vi.fn(),
	deleteEmotionLog: vi.fn(),
}))

const TableStub = defineComponent({
	name: 'ElTable',
	props: { data: { type: Array, default: () => [] } },
	setup(props, { slots }) {
		return () => {
			const columns = (slots.default?.() ?? []).filter(
				column => column.props?.label != null,
			)
			return h('table', [
				h(
					'thead',
					[
						h(
							'tr',
							columns.map(column =>
								h('th', String(column.props?.label)),
							),
						),
					],
				),
				h(
					'tbody',
					(props.data as Record<string, unknown>[]).map((row, index) =>
						h(
							'tr',
							columns.map(column => {
								const renderCell = (
									column.children as
										| { default?: (scope: { row: Record<string, unknown>; $index: number }) => unknown }
										| null
										| undefined
								)?.default
								const cell = renderCell
									? renderCell({ row, $index: index })
									: row[String(column.props?.prop)]
								return h(
									'td',
									{ 'data-column': String(column.props?.label) },
									cell == null ? '' : (cell as string | number),
								)
							}),
						),
					),
				),
			])
		}
	},
})

const RateStub = defineComponent({
	name: 'ElRate',
	props: { modelValue: { type: Number, default: undefined } },
	setup(props) {
		return () => h('span', { class: 'score-cell' }, String(props.modelValue ?? ''))
	},
})

const ButtonStub = defineComponent({
	name: 'ElButton',
	props: {
		type: { type: String, default: '' },
		loading: { type: Boolean, default: false },
		disabled: { type: Boolean, default: false },
	},
	emits: ['click'],
	setup(props, { emit, slots }) {
		return () =>
			h(
				'button',
				{
					class:
						props.type === 'danger'
							? 'delete-button'
							: props.type === 'primary'
								? 'detail-button'
								: '',
					disabled: props.disabled,
					'aria-busy': props.loading,
					onClick: (event: MouseEvent) => emit('click', event),
				},
				slots.default?.(),
			)
	},
})

const SearchStub = defineComponent({
	name: 'TableSearch',
	props: { formItem: { type: Array, default: () => [] } },
	emits: ['search'],
	setup(_, { emit }) {
		return () =>
			h('div', [
				h(
					'button',
					{
						'data-testid': 'search-first',
						onClick: () =>
							emit('search', {
								userId: '41',
								modelScoreRange: '4-6',
							}),
					},
					'筛选一',
				),
				h(
					'button',
					{
						'data-testid': 'search-second',
						onClick: () =>
							emit('search', {
								userId: '73',
								modelScoreRange: '7-10',
							}),
					},
					'筛选二',
				),
					h(
					'button',
					{
						'data-testid': 'search-reset',
						onClick: () => emit('search', {}),
					},
					'重置',
				),
				h(
					'button',
					{
						'data-testid': 'search-emotion',
						onClick: () => emit('search', { dominantEmotion: '疲惫' }),
					},
					'筛选疲惫',
				),
			])
	},
})

const PaginationStub = defineComponent({
	name: 'ElPagination',
	props: { currentPage: { default: '1' } },
	emits: ['current-change', 'update:currentPage'],
	setup(props, { emit }) {
		return () =>
			h('div', {
				'data-testid': 'pagination',
				'data-current-page': String(props.currentPage),
				'data-current-page-type': typeof props.currentPage,
			}, [
				h(
					'button',
					{
						'data-testid': 'page-two',
						onClick: () => {
							emit('update:currentPage', 2)
							emit('current-change', 2)
						},
					},
					'第 2 页',
				),
					h(
						'button',
						{
							'data-testid': 'page-three',
						onClick: () => {
							emit('update:currentPage', 3)
							emit('current-change', 3)
						},
						},
						'第 3 页',
					),
					h(
						'button',
						{
							'data-testid': 'page-five',
							onClick: () => {
								emit('update:currentPage', 5)
								emit('current-change', 5)
							},
						},
						'第 5 页',
					),
				])
	},
})

const DialogStub = defineComponent({
	name: 'ElDialog',
	props: { modelValue: { type: Boolean, default: false } },
	setup(props, { slots }) {
		return () =>
			props.modelValue
				? h('div', { 'data-testid': 'detail-dialog' }, [
						slots.default?.(),
						slots.footer?.(),
					])
				: null
	},
})

const SlotStub = defineComponent({
	setup(_, { slots }) {
		return () => h('div', slots.default?.())
	},
})

const diaryRows = [
	{
		id: 9101,
		userId: 41,
		username: 'alice',
		nickname: '小艾',
		diaryDate: '2026-10-12',
		moodScore: 8,
		dominantEmotion: '开心',
		emotionTriggers: '项目完成',
		diaryContent: '今天心情不错',
		diaryContentPreview: '今天心情不错',
		sleepQuality: 4,
		stressLevel: 2,
		aiEmotionAnalysis: '',
		aiAnalysisUpdatedAt: '',
		hasAiEmotionAnalysis: false,
		aiAnalysisStatus: 'PENDING',
		contentLength: 7,
		createdAt: '2026-10-12T09:00:00',
		updatedAt: '2026-10-12T09:00:00',
	},
	{
		id: 9202,
		userId: 73,
		username: 'bob',
		nickname: '小博',
		diaryDate: '2026-10-11',
		moodScore: 3,
		dominantEmotion: '焦虑',
		emotionTriggers: '工作安排',
		diaryContent: '担心明天的汇报',
		diaryContentPreview: '担心明天的汇报',
		sleepQuality: 3,
		stressLevel: 4,
		aiEmotionAnalysis: '',
		aiAnalysisUpdatedAt: '',
		hasAiEmotionAnalysis: false,
		aiAnalysisStatus: 'PENDING',
		contentLength: 8,
		createdAt: '2026-10-11T09:00:00',
		updatedAt: '2026-10-11T09:00:00',
	},
]

const deferred = <T,>() => {
	let resolve!: (value: T) => void
	let reject!: (reason?: unknown) => void
	const promise = new Promise<T>((resolvePromise, rejectPromise) => {
		resolve = resolvePromise
		reject = rejectPromise
	})
	return { promise, resolve, reject }
}

const pageResult = (
	records: (typeof diaryRows)[number][],
	current: number,
	total: number,
) => ({ records, current, page: current, size: 10, total })

const mockShrinkingPageResponses = () => {
	vi.mocked(getEmotionlogPage)
		.mockReset()
		.mockResolvedValueOnce(pageResult(diaryRows, 1, 51))
		.mockResolvedValueOnce(pageResult(diaryRows, 5, 51))
		.mockResolvedValueOnce(pageResult([], 5, 40))
		.mockResolvedValueOnce(pageResult([], 4, 30))
		.mockResolvedValueOnce(pageResult([], 3, 20))
		.mockResolvedValueOnce(pageResult([], 2, 10))
}

describe('admin emotional diary page', () => {
	beforeEach(() => {
		vi.mocked(getEmotionlogPage).mockResolvedValue({
			records: diaryRows,
			current: 1,
			page: 1,
			size: 10,
			total: 2,
		})
		vi.mocked(deleteEmotionLog).mockResolvedValue(undefined)
	})

	afterEach(() => {
		vi.restoreAllMocks()
	})

	const mountPage = () =>
		mount(EmotionalPage, {
			global: {
				stubs: {
					PageHead: true,
					TableSearch: SearchStub,
					ElTable: TableStub,
					ElRate: RateStub,
					ElButton: ButtonStub,
					ElDialog: DialogStub,
					ElPagination: PaginationStub,
					ElAvatar: true,
					ElDescriptions: SlotStub,
					ElDescriptionsItem: SlotStub,
					ElTag: SlotStub,
					ElProgress: true,
				},
			},
		})

	it('renders backend user identity and mood score for distinct diary records', async () => {
		const wrapper = mountPage()
		await flushPromises()

		const headers = wrapper.findAll('thead th').map(header => header.text())
		const cells = (label: string) =>
			wrapper.findAll(`[data-column="${label}"]`).map(cell => cell.text())

		expect(headers).toContain('用户ID')
		expect(headers).toContain('用户名')
		expect(headers).toContain('昵称')
		expect(headers).toContain('情绪评分')
		expect(cells('用户ID')).toEqual(['41', '73'])
		expect(cells('用户名')).toEqual(['alice', 'bob'])
		expect(cells('昵称')).toEqual(['小艾', '小博'])
		expect(cells('情绪评分')).toEqual(['8', '3'])
		expect(wrapper.find('tbody').text()).not.toContain('9101')
		expect(wrapper.find('tbody').text()).not.toContain('9202')
	})

	it('keeps the pagination model numeric while sending validated query parameters', async () => {
		const wrapper = mountPage()
		await flushPromises()

		expect(wrapper.get('[data-testid="pagination"]').attributes('data-current-page-type')).toBe('number')
		await wrapper.get('[data-testid="page-two"]').trigger('click')
		await flushPromises()

		expect(wrapper.get('[data-testid="pagination"]').attributes('data-current-page-type')).toBe('number')
		expect(getEmotionlogPage).toHaveBeenLastCalledWith({
			currentPage: '2',
			size: '10',
		})
	})

	it('resets search and reset to page one and preserves filters when paging', async () => {
		const wrapper = mountPage()
		await flushPromises()

		await wrapper.get('[data-testid="search-first"]').trigger('click')
		await flushPromises()
		expect(getEmotionlogPage).toHaveBeenLastCalledWith({
			currentPage: '1',
			size: '10',
			userId: '41',
			modelScoreRange: '4-6',
		})

		await wrapper.get('[data-testid="page-three"]').trigger('click')
		await flushPromises()
		expect(getEmotionlogPage).toHaveBeenLastCalledWith({
			currentPage: '3',
			size: '10',
			userId: '41',
			modelScoreRange: '4-6',
		})

		await wrapper.get('[data-testid="search-second"]').trigger('click')
		await flushPromises()
		expect(getEmotionlogPage).toHaveBeenLastCalledWith({
			currentPage: '1',
			size: '10',
			userId: '73',
			modelScoreRange: '7-10',
		})

		await wrapper.get('[data-testid="page-two"]').trigger('click')
		await flushPromises()
		await wrapper.get('[data-testid="page-three"]').trigger('click')
		await flushPromises()
		await wrapper.get('[data-testid="search-reset"]').trigger('click')
		await flushPromises()
		expect(getEmotionlogPage).toHaveBeenLastCalledWith({
			currentPage: '1',
			size: '10',
		})
	})

	it('does not reload or delete when the confirmation is cancelled', async () => {
		vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
		const wrapper = mountPage()
		await flushPromises()

		await wrapper.get('.delete-button').trigger('click')
		await flushPromises()

		expect(deleteEmotionLog).not.toHaveBeenCalled()
		expect(getEmotionlogPage).toHaveBeenCalledTimes(1)
	})

	it('reports a successful deletion when the following list refresh fails', async () => {
		const alert = vi.spyOn(ElMessageBox, 'alert').mockResolvedValue(undefined as never)
		vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(undefined as never)
		vi.mocked(getEmotionlogPage)
			.mockResolvedValueOnce({
				records: diaryRows,
				current: 1,
				page: 1,
				size: 10,
				total: 2,
			})
			.mockRejectedValueOnce(new Error('refresh failed'))

		const wrapper = mountPage()
		await flushPromises()
		await wrapper.get('.delete-button').trigger('click')
		await flushPromises()

		expect(deleteEmotionLog).toHaveBeenCalledWith(9101)
		expect(alert).toHaveBeenCalledWith(
			expect.stringContaining('删除已成功'),
			'提示',
			expect.objectContaining({ type: 'warning' }),
		)
		expect(alert).not.toHaveBeenCalledWith(
			'删除失败，请稍后再试',
			expect.anything(),
			expect.anything(),
		)
	})

	it('warns when a successful delete is followed by a refreshed list that still contains the diary', async () => {
		const alert = vi.spyOn(ElMessageBox, 'alert').mockResolvedValue(undefined as never)
		vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(undefined as never)
		vi.mocked(deleteEmotionLog).mockResolvedValue(undefined)
		vi.mocked(getEmotionlogPage)
			.mockResolvedValueOnce(pageResult(diaryRows, 1, 2))
			.mockResolvedValueOnce(pageResult(diaryRows, 1, 2))

		const wrapper = mountPage()
		await flushPromises()
		await wrapper.get('.delete-button').trigger('click')
		await flushPromises()

		expect(deleteEmotionLog).toHaveBeenCalledWith(9101)
		expect(alert).toHaveBeenCalledWith(
			'删除请求已成功，但记录仍显示在列表中，请刷新核对',
			'提示',
			expect.objectContaining({ type: 'warning' }),
		)
		expect(alert).not.toHaveBeenCalledWith(
			'删除成功',
			'提示',
			expect.objectContaining({ type: 'success' }),
		)
		expect(wrapper.findAll('[data-column="用户ID"]').map(cell => cell.text())).toEqual([
			'41',
			'73',
		])
	})

	it('reloads the last valid page after deleting the final row on a later page', async () => {
		vi.spyOn(ElMessageBox, 'alert').mockResolvedValue(undefined as never)
		vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(undefined as never)
		vi.mocked(deleteEmotionLog).mockResolvedValue(undefined)
		vi.mocked(getEmotionlogPage)
			.mockReset()
			.mockResolvedValueOnce({
				records: diaryRows,
				current: 1,
				page: 1,
				size: 10,
				total: 21,
			})
			.mockResolvedValueOnce({
				records: [diaryRows[0]],
				current: 3,
				page: 3,
				size: 10,
				total: 21,
			})
			.mockResolvedValueOnce({
				records: [],
				current: 3,
				page: 3,
				size: 10,
				total: 20,
			})
			.mockResolvedValueOnce({
				records: [diaryRows[1]],
				current: 2,
				page: 2,
				size: 10,
				total: 20,
			})

		const wrapper = mountPage()
		await flushPromises()
		await wrapper.get('[data-testid="page-three"]').trigger('click')
		await flushPromises()
		await wrapper.get('.delete-button').trigger('click')
		await flushPromises()

		expect(deleteEmotionLog).toHaveBeenCalledWith(9101)
		expect(getEmotionlogPage).toHaveBeenCalledTimes(4)
		expect(getEmotionlogPage).toHaveBeenLastCalledWith({
			currentPage: '2',
			size: '10',
		})
		expect(wrapper.findAll('[data-column="用户ID"]').map(cell => cell.text())).toEqual([
			'73',
		])
	})

	it('ignores a late page response and its stale empty-page fallback', async () => {
		const stalePage = deferred<Awaited<ReturnType<typeof getEmotionlogPage>>>()
		const latestSearch = deferred<Awaited<ReturnType<typeof getEmotionlogPage>>>()
		vi.mocked(getEmotionlogPage)
			.mockReset()
			.mockResolvedValue(pageResult(diaryRows, 1, 21))
			.mockResolvedValueOnce(pageResult(diaryRows, 1, 21))
			.mockResolvedValueOnce(pageResult(diaryRows, 1, 21))
			.mockReturnValueOnce(stalePage.promise)
			.mockReturnValueOnce(latestSearch.promise)

		const wrapper = mountPage()
		await flushPromises()
		await wrapper.get('[data-testid="search-first"]').trigger('click')
		await flushPromises()
		await wrapper.get('[data-testid="page-three"]').trigger('click')
		await wrapper.get('[data-testid="search-second"]').trigger('click')

		latestSearch.resolve(pageResult([diaryRows[1]], 1, 1))
		await flushPromises()
		stalePage.resolve(pageResult([], 3, 20))
		await flushPromises()

		expect(getEmotionlogPage).toHaveBeenCalledTimes(4)
		expect(wrapper.findAll('[data-column="用户ID"]').map(cell => cell.text())).toEqual([
			'73',
		])
		expect(wrapper.get('[data-testid="pagination"]').attributes('data-current-page')).toBe('1')
	})

	it('clears stale rows and exposes a retry action when a query fails', async () => {
		vi.mocked(getEmotionlogPage)
			.mockResolvedValueOnce(pageResult(diaryRows, 1, 2))
			.mockRejectedValueOnce(new Error('network unavailable'))
			.mockResolvedValueOnce(pageResult([diaryRows[1]], 1, 1))

		const wrapper = mountPage()
		await flushPromises()
		await wrapper.get('[data-testid="search-second"]').trigger('click')
		await flushPromises()

		expect(wrapper.get('[role="alert"]').text()).toContain('查询失败')
		expect(wrapper.get('tbody').text()).toBe('')
		await wrapper.get('[data-testid="query-retry"]').trigger('click')
		await flushPromises()
		expect(getEmotionlogPage).toHaveBeenCalledTimes(3)
		expect(wrapper.findAll('[data-column="用户ID"]').map(cell => cell.text())).toEqual([
			'73',
		])
	})

	it('prevents duplicate delete confirmation and requests for the same diary', async () => {
		const confirmation = deferred<void>()
		const deletion = deferred<void>()
		const confirm = vi
			.spyOn(ElMessageBox, 'confirm')
			.mockReturnValue(confirmation.promise as never)
		vi.spyOn(ElMessageBox, 'alert').mockResolvedValue(undefined as never)
		vi.mocked(deleteEmotionLog).mockReturnValue(deletion.promise)
		const wrapper = mountPage()
		await flushPromises()
		const deleteButton = wrapper
			.findAllComponents(ButtonStub)
			.find(button => button.props('type') === 'danger')!

		deleteButton.vm.$emit('click')
		await flushPromises()
		expect(confirm).toHaveBeenCalledTimes(1)
		expect(deleteButton.props('disabled')).toBe(true)
		deleteButton.vm.$emit('click')
		expect(confirm).toHaveBeenCalledTimes(1)

		confirmation.resolve()
		await flushPromises()
		expect(deleteEmotionLog).toHaveBeenCalledTimes(1)
		expect(deleteButton.props('loading')).toBe(true)
		deleteButton.vm.$emit('click')
		expect(deleteEmotionLog).toHaveBeenCalledTimes(1)

		deletion.resolve()
		await flushPromises()
	})

	it('keeps a listed record visible and reports deletion as unconfirmed after a delete error', async () => {
		vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(undefined as never)
		const alert = vi.spyOn(ElMessageBox, 'alert').mockResolvedValue(undefined as never)
		vi.mocked(deleteEmotionLog).mockRejectedValue(new Error('delete failed'))
		vi.mocked(getEmotionlogPage)
			.mockResolvedValueOnce(pageResult(diaryRows, 1, 2))
			.mockResolvedValueOnce(pageResult(diaryRows, 1, 2))

		const wrapper = mountPage()
		await flushPromises()
		await wrapper.get('.delete-button').trigger('click')
		await flushPromises()

		expect(getEmotionlogPage).toHaveBeenCalledTimes(2)
		expect(alert).toHaveBeenCalledWith(
			'删除结果未确认，记录仍显示在列表中，请稍后核对',
			'提示',
			expect.objectContaining({ type: 'warning' }),
		)
		expect(alert).not.toHaveBeenCalledWith(
			'删除失败，请稍后再试',
			expect.anything(),
			expect.anything(),
		)
		expect(wrapper.findAll('[data-column="用户ID"]').map(cell => cell.text())).toEqual([
			'41',
			'73',
		])
	})

	it('reports an uncertain delete result when refresh succeeds but the record is absent', async () => {
		vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(undefined as never)
		const alert = vi.spyOn(ElMessageBox, 'alert').mockResolvedValue(undefined as never)
		vi.mocked(deleteEmotionLog).mockRejectedValue(new Error('delete response lost'))
		vi.mocked(getEmotionlogPage)
			.mockResolvedValueOnce(pageResult(diaryRows, 1, 2))
			.mockResolvedValueOnce(pageResult([diaryRows[1]], 1, 1))

		const wrapper = mountPage()
		await flushPromises()
		await wrapper.get('.delete-button').trigger('click')
		await flushPromises()

		expect(alert).toHaveBeenCalledWith(
			'删除结果不确定，记录已不在当前列表中，请手动核对',
			'提示',
			expect.objectContaining({ type: 'warning' }),
		)
		expect(alert).not.toHaveBeenCalledWith(
			'删除失败，请稍后再试',
			expect.anything(),
			expect.anything(),
		)
		expect(wrapper.findAll('[data-column="用户ID"]').map(cell => cell.text())).toEqual([
			'73',
		])
	})

	it('asks for manual verification when a delete error is followed by a refresh failure', async () => {
		vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(undefined as never)
		const alert = vi.spyOn(ElMessageBox, 'alert').mockResolvedValue(undefined as never)
		vi.mocked(deleteEmotionLog).mockRejectedValue(new Error('delete response lost'))
		vi.mocked(getEmotionlogPage)
			.mockResolvedValueOnce(pageResult(diaryRows, 1, 2))
			.mockRejectedValueOnce(new Error('refresh failed'))

		const wrapper = mountPage()
		await flushPromises()
		await wrapper.get('.delete-button').trigger('click')
		await flushPromises()

		expect(alert).toHaveBeenCalledWith(
			'删除结果不确定，列表刷新失败，请手动核对',
			'提示',
			expect.objectContaining({ type: 'warning' }),
		)
		expect(alert).not.toHaveBeenCalledWith(
			'删除失败，请稍后再试',
			expect.anything(),
			expect.anything(),
		)
		expect(wrapper.get('[role="alert"]').text()).toContain('查询失败')
	})

	it('reports a changing list after a rejected delete exhausts page fallback retries', async () => {
		vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(undefined as never)
		const alert = vi.spyOn(ElMessageBox, 'alert').mockResolvedValue(undefined as never)
		vi.mocked(deleteEmotionLog).mockRejectedValue(new Error('delete response lost'))
		mockShrinkingPageResponses()

		const wrapper = mountPage()
		await flushPromises()
		await wrapper.get('[data-testid="page-five"]').trigger('click')
		await flushPromises()
		await wrapper.get('.delete-button').trigger('click')
		await flushPromises()

		expect(getEmotionlogPage).toHaveBeenCalledTimes(6)
		expect(alert).toHaveBeenCalledWith(
			'删除结果不确定，列表仍在变化，暂无法确认状态',
			'提示',
			expect.objectContaining({ type: 'warning' }),
		)
		expect(alert).not.toHaveBeenCalledWith(
			'删除结果不确定，列表刷新失败，请手动核对',
			expect.anything(),
			expect.anything(),
		)
	})

	it('reports confirmed deletion and changing list when successful delete exhausts fallback retries', async () => {
		vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(undefined as never)
		const alert = vi.spyOn(ElMessageBox, 'alert').mockResolvedValue(undefined as never)
		vi.mocked(deleteEmotionLog).mockResolvedValue(undefined)
		mockShrinkingPageResponses()

		const wrapper = mountPage()
		await flushPromises()
		await wrapper.get('[data-testid="page-five"]').trigger('click')
		await flushPromises()
		await wrapper.get('.delete-button').trigger('click')
		await flushPromises()

		expect(getEmotionlogPage).toHaveBeenCalledTimes(6)
		expect(alert).toHaveBeenCalledWith(
			'删除已成功，但列表仍在变化，暂无法确认列表状态',
			'提示',
			expect.objectContaining({ type: 'warning' }),
		)
		expect(alert).not.toHaveBeenCalledWith(
			'删除已成功，但列表刷新失败，请手动刷新列表',
			expect.anything(),
			expect.anything(),
		)
	})

	it('reports malformed and non-object AI analysis without leaking prior data', async () => {
		vi.mocked(getEmotionlogPage).mockResolvedValueOnce(
			pageResult(
				[
					{ ...diaryRows[0], aiEmotionAnalysis: '{"primaryEmotion":"平静","suggestion":"上一条分析建议"}' },
					{ ...diaryRows[1], aiEmotionAnalysis: '{broken json' },
					{ ...diaryRows[1], id: 9303, userId: 81, aiEmotionAnalysis: '[]' },
				],
				1,
				3,
			),
		)
		const wrapper = mountPage()
		await flushPromises()
		const detailButtons = wrapper.findAll('.detail-button')

		await detailButtons[0].trigger('click')
		await flushPromises()
		expect(wrapper.get('[data-testid="detail-dialog"]').text()).toContain('上一条分析建议')

		await detailButtons[1].trigger('click')
		await flushPromises()
		expect(wrapper.get('[data-testid="detail-dialog"]').text()).toContain('分析数据无法读取')
		expect(wrapper.get('[data-testid="detail-dialog"]').text()).not.toContain('上一条分析建议')

		await detailButtons[2].trigger('click')
		await flushPromises()
		expect(wrapper.get('[data-testid="detail-dialog"]').text()).toContain('分析数据无法读取')
		expect(wrapper.get('[data-testid="detail-dialog"]').text()).not.toContain('上一条分析建议')
	})

	it('offers the diary emotion choices and serializes the selected emotion filter', async () => {
		const wrapper = mountPage()
		await flushPromises()
		const fields = wrapper.findComponent(SearchStub).props('formItem') as Array<{
			prop: string
			options?: Array<{ label: string; value: string }>
		}>
		const emotionField = fields.find(field => field.prop === 'dominantEmotion')
		expect(emotionField?.options?.map(option => option.value)).toEqual([
			'开心',
			'平静',
			'焦虑',
			'悲伤',
			'兴奋',
			'疲惫',
			'惊讶',
			'困惑',
		])

		await wrapper.get('[data-testid="search-emotion"]').trigger('click')
		await flushPromises()
		expect(getEmotionlogPage).toHaveBeenLastCalledWith({
			currentPage: '1',
			size: '10',
			dominantEmotion: '疲惫',
		})
	})

	it('bounds empty-page fallback retries when concurrent deletes keep shrinking results', async () => {
		vi.mocked(getEmotionlogPage)
			.mockReset()
			.mockResolvedValue(pageResult(diaryRows, 1, 51))
			.mockResolvedValueOnce(pageResult(diaryRows, 1, 51))
			.mockResolvedValueOnce(pageResult([], 5, 40))
			.mockResolvedValueOnce(pageResult([], 4, 30))
			.mockResolvedValueOnce(pageResult([], 3, 20))
			.mockResolvedValueOnce(pageResult([], 2, 10))

		const wrapper = mountPage()
		await flushPromises()
		await wrapper.get('[data-testid="page-five"]').trigger('click')
		await flushPromises()

		expect(getEmotionlogPage).toHaveBeenCalledTimes(5)
		expect(getEmotionlogPage).toHaveBeenLastCalledWith({
			currentPage: '2',
			size: '10',
		})
		expect(wrapper.get('[data-testid="pagination"]').attributes('data-current-page')).toBe('1')
		expect(wrapper.get('[role="alert"]').text()).toContain('列表仍在变化')
	})
})
