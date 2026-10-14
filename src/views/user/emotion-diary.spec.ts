import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import EmotionDiary from './emotion-diary.vue'
import { submitEmotionDiary } from '@/api/user'

const mocks = vi.hoisted(() => ({
	getMyDiaries: vi.fn(),
	submitEmotionDiary: vi.fn(),
	deleteMyEmotionDiary: vi.fn(),
	messageSuccess: vi.fn(),
	messageError: vi.fn(),
	messageWarning: vi.fn(),
	messageInfo: vi.fn(),
}))

vi.mock('@/api/user', () => ({
	getMyDiaries: mocks.getMyDiaries,
	submitEmotionDiary: mocks.submitEmotionDiary,
	deleteMyEmotionDiary: mocks.deleteMyEmotionDiary,
}))

vi.mock('element-plus', async importOriginal => {
	const actual = await importOriginal<typeof import('element-plus')>()
	return {
		...actual,
		ElMessage: {
			success: mocks.messageSuccess,
			error: mocks.messageError,
			warning: mocks.messageWarning,
			info: mocks.messageInfo,
		},
		ElMessageBox: { confirm: vi.fn() },
	}
})

const InputStub = defineComponent({
	props: ['modelValue'],
	emits: ['update:modelValue'],
	setup(props, { emit }) {
		return () => h('textarea', {
			value: props.modelValue,
			onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLTextAreaElement).value),
		})
	},
})

const RateStub = defineComponent({
	emits: ['update:modelValue'],
	setup(_, { emit }) {
		return () => h('button', { 'data-testid': 'rate', onClick: () => emit('update:modelValue', 7) })
	},
})

const SelectStub = defineComponent({
	emits: ['update:modelValue'],
	setup(_, { emit }) {
		return () => h('button', { 'data-testid': 'select', onClick: () => emit('update:modelValue', 3) })
	},
})

const ButtonStub = defineComponent({
	props: ['loading'],
	emits: ['click'],
	setup(props, { emit, slots }) {
		return () => h('button', {
			'data-loading': String(Boolean(props.loading)),
			onClick: () => emit('click'),
		}, slots.default?.())
	},
})

async function mountAndFillDiary() {
	const wrapper = mount(EmotionDiary, {
		global: {
			stubs: {
				'el-button': ButtonStub,
				'el-empty': true,
				'el-icon': true,
				'el-image': true,
				'el-input': InputStub,
				'el-option': true,
				'el-rate': RateStub,
				'el-select': SelectStub,
				'el-tag': true,
				Loading: true,
				MarkdownRenderer: true,
			},
		},
	})
	await flushPromises()
	await wrapper.get('[data-testid="rate"]').trigger('click')
	await wrapper.find('.emotion-card').trigger('click')
	const details = wrapper.findAll('.detail-form textarea')
	await details[0].setValue('工作压力')
	await details[1].setValue('今天有些疲惫')
	for (const select of wrapper.findAll('[data-testid="select"]')) await select.trigger('click')
	return wrapper
}

describe('emotion diary submission feedback', () => {
	beforeEach(() => {
		vi.useFakeTimers()
		mocks.getMyDiaries.mockReset()
		mocks.submitEmotionDiary.mockReset()
		mocks.deleteMyEmotionDiary.mockReset()
		mocks.messageSuccess.mockReset()
		mocks.messageError.mockReset()
		mocks.messageWarning.mockReset()
		mocks.messageInfo.mockReset()
	})

	afterEach(() => vi.useRealTimers())

	it('reports analysis polling failure separately after the diary was saved', async () => {
		mocks.getMyDiaries.mockRejectedValue(new Error('network unavailable'))
		mocks.submitEmotionDiary.mockResolvedValue(undefined)
		const wrapper = await mountAndFillDiary()

		await wrapper.get('.action-buttons button:nth-child(2)').trigger('click')
		await flushPromises()
		await vi.advanceTimersByTimeAsync(3000)
		await flushPromises()

		expect(submitEmotionDiary).toHaveBeenCalledOnce()
		expect(mocks.messageError).not.toHaveBeenCalledWith('提交失败，请稍后再试')
		expect(wrapper.find('.ai-analysis-card').exists()).toBe(true)
		expect(wrapper.text()).toContain('日记已保存，但暂时无法获取分析进度')
	})

	it('keeps a completed analysis visible after clearing the submitted form', async () => {
		mocks.getMyDiaries
			.mockRejectedValueOnce(new Error('initial history unavailable'))
			.mockResolvedValue([{ id: 8, diaryDate: '2026-10-14', moodScore: 7, dominantEmotion: '疲惫', hasAiEmotionAnalysis: true, aiEmotionAnalysis: '已保存的分析' }])
		mocks.submitEmotionDiary.mockResolvedValue(undefined)
		const wrapper = await mountAndFillDiary()

		await wrapper.get('.action-buttons button:nth-child(2)').trigger('click')
		await flushPromises()
		await vi.advanceTimersByTimeAsync(3000)
		await flushPromises()

		expect(wrapper.find('.ai-analysis-card').exists()).toBe(true)
		expect(wrapper.text()).toContain('已保存的分析')
	})

	it('does not persist the same diary twice while the first request is pending', async () => {
		let resolveSave: (() => void) | undefined
		mocks.submitEmotionDiary.mockImplementation(() => new Promise<void>(resolve => {
			resolveSave = resolve
		}))
		mocks.getMyDiaries.mockRejectedValue(new Error('history unavailable'))
		const wrapper = await mountAndFillDiary()
		const submitButton = wrapper.get('.action-buttons button:nth-child(2)').element as HTMLButtonElement

		submitButton.click()
		submitButton.click()
		await flushPromises()

		expect(submitEmotionDiary).toHaveBeenCalledOnce()
		resolveSave?.()
		await flushPromises()
	})

	it('clears pending indicators when the diary persistence request fails', async () => {
		mocks.getMyDiaries.mockRejectedValue(new Error('history unavailable'))
		mocks.submitEmotionDiary.mockRejectedValue(new Error('save failed'))
		const wrapper = await mountAndFillDiary()

		await wrapper.get('.action-buttons button:nth-child(2)').trigger('click')
		await flushPromises()

		expect(mocks.messageError).toHaveBeenCalledWith('提交失败，请稍后再试')
		expect(wrapper.get('.action-buttons button:nth-child(2)').attributes('data-loading')).toBe('false')
		expect(wrapper.find('.ai-analysis-card').exists()).toBe(false)
	})
})
