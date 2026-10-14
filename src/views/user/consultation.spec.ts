import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Consultation from './consultation.vue'
import { fetchEventSource } from '@microsoft/fetch-event-source'
import { getHistorySessionMessages } from '@/api/user'
import type { HistorySession, Message } from '@/types/user'

const mocks = vi.hoisted(() => ({
	startSession: vi.fn(),
	recoverSessionStart: vi.fn(),
	updateReviewConsent: vi.fn(),
	requestHumanReview: vi.fn(),
	getPersonalizationConsent: vi.fn(),
	setPersonalizationConsent: vi.fn(),
	getAgentMemories: vi.fn(),
	createAgentMemory: vi.fn(),
	updateAgentMemory: vi.fn(),
	deleteAgentMemory: vi.fn(),
	clearAgentMemories: vi.fn(),
	getHistorySessionList: vi.fn(),
	deleteSession: vi.fn(),
	getHistorySessionMessages: vi.fn(),
	getEmotionAnalysis: vi.fn(),
	fetchEventSource: vi.fn(),
}))

vi.mock('@/api/user', () => ({
	startSession: mocks.startSession,
	recoverSessionStart: mocks.recoverSessionStart,
	updateReviewConsent: mocks.updateReviewConsent,
	requestHumanReview: mocks.requestHumanReview,
	getPersonalizationConsent: mocks.getPersonalizationConsent,
	setPersonalizationConsent: mocks.setPersonalizationConsent,
	getAgentMemories: mocks.getAgentMemories,
	createAgentMemory: mocks.createAgentMemory,
	updateAgentMemory: mocks.updateAgentMemory,
	deleteAgentMemory: mocks.deleteAgentMemory,
	clearAgentMemories: mocks.clearAgentMemories,
	getHistorySessionList: mocks.getHistorySessionList,
	deleteSession: mocks.deleteSession,
	getHistorySessionMessages: mocks.getHistorySessionMessages,
	getEmotionAnalysis: mocks.getEmotionAnalysis,
}))

vi.mock('@microsoft/fetch-event-source', () => ({
	fetchEventSource: mocks.fetchEventSource,
}))

const InputStub = defineComponent({
	name: 'ElInput',
	props: { modelValue: { type: String, default: '' } },
	emits: ['update:modelValue'],
	setup(props, { attrs, emit }) {
		return () =>
			h('textarea', {
				...attrs,
				'data-testid': 'message-input',
				value: props.modelValue,
				onInput: (event: Event) =>
					emit('update:modelValue', (event.target as HTMLTextAreaElement).value),
			})
	},
})

const ButtonStub = defineComponent({
	name: 'ElButton',
	emits: ['click'],
	setup(_, { attrs, emit, slots }) {
		return () =>
			h(
				'button',
				{
					...attrs,
					onClick: (event: MouseEvent) => emit('click', event),
				},
				slots.default?.(),
			)
	},
})

const session: HistorySession = {
	id: 7,
	userId: 4,
	userNickname: '测试用户',
	sessionTitle: '已保存的会话',
	startedAt: '2026-10-14T08:00:00',
	durationMinutes: 2,
	messageCount: 2,
	lastMessageContent: '历史回复',
	lastMessageTime: '2026-10-14T08:02:00',
}

const userMessage = (id: number, content: string): Message => ({
	id,
	sessionId: session.id,
	senderType: 1,
	content,
	createdAt: '2026-10-14T08:00:00',
})

const assistantMessage = (id: number, content: string): Message => ({
	id,
	sessionId: session.id,
	senderType: 2,
	content,
	createdAt: '2026-10-14T08:01:00',
})

const response = {
	headers: { get: () => 'text/event-stream; charset=utf-8' },
} as unknown as Response

const completeStream = async (_url: string, options: any) => {
	await options.onopen(response)
	options.onmessage({
		id: '1',
		event: 'message',
		data: JSON.stringify({ code: '200', data: { content: '已完成回复' } }),
	})
	options.onmessage({
		id: '2',
		event: 'done',
		data: JSON.stringify({ code: '200', data: { done: true } }),
	})
}

const mountPage = () =>
	mount(Consultation, {
		global: {
			stubs: {
				'el-alert': true,
				'el-button': ButtonStub,
				'el-checkbox': true,
				'el-date-picker': true,
				'el-dialog': true,
				'el-empty': true,
				'el-icon': true,
				'el-image': true,
				'el-input': InputStub,
				'el-switch': true,
				'el-tooltip': true,
				ChatRound: true,
				Clock: true,
				DeleteFilled: true,
				InfoFilled: true,
				Plus: true,
				Promotion: true,
			},
		},
	})

describe('consultation conversation recovery', () => {
	let wrapper: ReturnType<typeof mountPage> | undefined

	beforeEach(() => {
		sessionStorage.clear()
		vi.stubGlobal('localStorage', { getItem: vi.fn(() => 'test-token') })
		mocks.getHistorySessionList.mockResolvedValue({ records: [session] })
		mocks.getHistorySessionMessages.mockResolvedValue([])
		mocks.recoverSessionStart.mockResolvedValue(null)
		mocks.getEmotionAnalysis.mockResolvedValue(null)
		mocks.startSession.mockResolvedValue({
			sessionId: '101',
			status: 'ACTIVE',
			sessionTitle: '新会话',
		})
		mocks.fetchEventSource.mockImplementation(completeStream)
	})

	afterEach(() => {
		wrapper?.unmount()
		wrapper = undefined
		vi.unstubAllGlobals()
	})

	it('keeps the first message as a draft when session creation fails', async () => {
		mocks.startSession.mockRejectedValueOnce(new Error('network timeout'))
		wrapper = mountPage()
		await flushPromises()

		const input = wrapper.get('[data-testid="message-input"]')
		await input.setValue('我今天觉得很难受')
		await wrapper.get('.send-btn').trigger('click')
		await flushPromises()

		expect((input.element as HTMLTextAreaElement).value).toBe('我今天觉得很难受')
		expect(mocks.startSession).toHaveBeenCalledTimes(1)
		expect(fetchEventSource).not.toHaveBeenCalled()
	})

	it('reuses a request id when retrying the same first message without storing its text', async () => {
		mocks.startSession.mockRejectedValueOnce(new Error('response lost'))
		wrapper = mountPage()
		await flushPromises()

		const input = wrapper.get('[data-testid="message-input"]')
		const content = '不应写入浏览器存储的首条消息'
		await input.setValue(content)
		await wrapper.get('.send-btn').trigger('click')
		await flushPromises()

		const firstRequestId = mocks.startSession.mock.calls[0][0].clientRequestId
		expect(firstRequestId).toBeTruthy()
		expect(sessionStorage.getItem('amha:pending-session-start')).toBe(firstRequestId)
		expect(sessionStorage.getItem('amha:pending-session-start')).not.toContain(content)

		await wrapper.get('.send-btn').trigger('click')
		await flushPromises()
		expect(mocks.startSession).toHaveBeenCalledTimes(2)
		expect(mocks.startSession.mock.calls[1][0].clientRequestId).toBe(firstRequestId)
		expect(fetchEventSource).toHaveBeenCalledTimes(1)
	})

	it('recovers an accepted first message after refresh using only its request id', async () => {
		const requestId = 'start-request-recover'
		const content = '服务端已接收并持久化的首条消息'
		sessionStorage.setItem('amha:pending-session-start', requestId)
		mocks.recoverSessionStart.mockResolvedValueOnce({
			sessionId: 101,
			status: 'ACTIVE',
			sessionTitle: '已恢复的会话',
			messageCount: 1,
			reviewConsent: false,
		})
		mocks.getHistorySessionMessages.mockResolvedValueOnce([{
			id: 31,
			sessionId: 101,
			senderType: 1,
			content,
			createdAt: '2026-10-14T08:00:00',
		}])
		wrapper = mountPage()
		await flushPromises()

		expect(mocks.recoverSessionStart).toHaveBeenCalledWith(requestId)
		expect(sessionStorage.getItem('amha:pending-session-start')).toBeNull()
		expect(fetchEventSource).toHaveBeenCalledTimes(1)
		expect(JSON.parse(vi.mocked(fetchEventSource).mock.calls[0][1].body as string))
			.toMatchObject({ clientRequestId: requestId, userMessage: content })
	})

	it('keeps an unknown start request id and reuses it when the user re-enters the message', async () => {
		const requestId = 'start-request-not-found'
		sessionStorage.setItem('amha:pending-session-start', requestId)
		mocks.recoverSessionStart.mockResolvedValueOnce(null)
		wrapper = mountPage()
		await flushPromises()

		expect(sessionStorage.getItem('amha:pending-session-start')).toBe(requestId)
		expect(wrapper.get('.send-btn').attributes('disabled')).toBeDefined()
		expect(wrapper.find('.resume-start-entry').exists()).toBe(true)
		expect(mocks.startSession).not.toHaveBeenCalled()

		await wrapper.get('.resume-start-entry').trigger('click')
		const input = wrapper.get('[data-testid="message-input"]')
		await input.setValue('我重新输入后仍沿用原请求标识')
		await wrapper.get('.send-btn').trigger('click')
		await flushPromises()

		expect(mocks.startSession).toHaveBeenCalledWith(expect.objectContaining({ clientRequestId: requestId }))
		expect(sessionStorage.getItem('amha:pending-session-start')).toBeNull()
	})

	it('reuses the unresolved start id if the user edits the first-message draft', async () => {
		mocks.startSession.mockRejectedValueOnce(new Error('response lost'))
		wrapper = mountPage()
		await flushPromises()

		const input = wrapper.get('[data-testid="message-input"]')
		await input.setValue('第一次提交的文字')
		await wrapper.get('.send-btn').trigger('click')
		await flushPromises()
		const requestId = mocks.startSession.mock.calls[0][0].clientRequestId

		await input.setValue('修改后的文字')
		await wrapper.get('.send-btn').trigger('click')
		await flushPromises()

		expect(mocks.startSession).toHaveBeenCalledTimes(2)
		expect(mocks.startSession.mock.calls[1][0]).toMatchObject({
			initialMessage: '修改后的文字',
			clientRequestId: requestId,
		})
	})

	it('opens the committed session found after an ambiguous start response', async () => {
		const content = '服务端已经保存但创建响应丢失的消息'
		mocks.startSession.mockRejectedValueOnce(new Error('response lost'))
		mocks.recoverSessionStart.mockResolvedValueOnce({
			sessionId: 104,
			status: 'ACTIVE',
			sessionTitle: '已提交的首轮会话',
			messageCount: 1,
		})
		mocks.getHistorySessionMessages.mockResolvedValueOnce([{
			id: 1041,
			sessionId: 104,
			senderType: 1,
			content,
			createdAt: '2026-10-14T08:00:00',
		}])
		wrapper = mountPage()
		await flushPromises()

		const input = wrapper.get('[data-testid="message-input"]')
		await input.setValue(content)
		await wrapper.get('.send-btn').trigger('click')
		await flushPromises()

		expect(mocks.recoverSessionStart).toHaveBeenCalledWith(
			mocks.startSession.mock.calls[0][0].clientRequestId,
		)
		expect(sessionStorage.getItem('amha:pending-session-start')).toBeNull()
		expect(JSON.parse(vi.mocked(fetchEventSource).mock.calls[0][1].body as string)).toMatchObject({
			sessionId: '104',
			userMessage: content,
		})
	})

	it('preserves the recovery marker and offers retry when the recovery request fails', async () => {
		const requestId = 'start-request-network-error'
		sessionStorage.setItem('amha:pending-session-start', requestId)
		mocks.recoverSessionStart
			.mockRejectedValueOnce(new Error('network unavailable'))
			.mockResolvedValueOnce({
				sessionId: 103,
				status: 'ACTIVE',
				sessionTitle: '重试恢复会话',
				messageCount: 1,
			})
		mocks.getHistorySessionMessages.mockResolvedValueOnce([])
		wrapper = mountPage()
		await flushPromises()

		expect(sessionStorage.getItem('amha:pending-session-start')).toBe(requestId)
		expect(wrapper.find('.retry-start-recovery').exists()).toBe(true)
		await wrapper.get('.retry-start-recovery').trigger('click')
		await flushPromises()

		expect(sessionStorage.getItem('amha:pending-session-start')).toBeNull()
		expect(wrapper.text()).not.toContain('暂时无法恢复上次发送的消息')
	})

	it('does not create duplicate sessions while the first-session request is pending', async () => {
		let resolveStart!: (value: { sessionId: string; status: string }) => void
		mocks.startSession.mockReturnValueOnce(
			new Promise(resolve => {
				resolveStart = resolve
			}),
		)
		wrapper = mountPage()
		await flushPromises()

		const input = wrapper.get('[data-testid="message-input"]')
		await input.setValue('请陪我聊一会儿')
		await wrapper.get('.send-btn').trigger('click')
		await input.setValue('请陪我聊一会儿')
		await wrapper.get('.send-btn').trigger('click')

		expect(mocks.startSession).toHaveBeenCalledTimes(1)
		resolveStart({ sessionId: '101', status: 'ACTIVE' })
		await flushPromises()
	})

	it('keeps an active-session draft until the server accepts the turn', async () => {
		mocks.fetchEventSource
			.mockImplementationOnce(completeStream)
			.mockRejectedValueOnce(new Error('request did not reach the server'))
		wrapper = mountPage()
		await flushPromises()

		const input = wrapper.get('[data-testid="message-input"]')
		await input.setValue('首次回复之后的待发送消息')
		await wrapper.get('.send-btn').trigger('click')
		await flushPromises()
		expect((input.element as HTMLTextAreaElement).value).toBe('')

		const content = '网络未接通时也要保留在输入框中的草稿'
		await input.setValue(content)
		await wrapper.get('.send-btn').trigger('click')
		await flushPromises()

		expect((input.element as HTMLTextAreaElement).value).toBe(content)
		expect(wrapper.find('.retry-stream').exists()).toBe(true)
		const pendingId = sessionStorage.getItem('amha:pending-chat-turn:101')
		expect(pendingId).toBeTruthy()
		expect(pendingId).not.toContain(content)
	})

	it('keeps the persisted first turn for recovery when session creation resolves after unmount', async () => {
		let resolveStart!: (value: { sessionId: string; status: string }) => void
		mocks.startSession.mockReturnValueOnce(
			new Promise(resolve => {
				resolveStart = resolve
			}),
		)
		wrapper = mountPage()
		await flushPromises()
		await wrapper.get('[data-testid="message-input"]').setValue('暂存中的倾诉')
		await wrapper.get('.send-btn').trigger('click')
		await nextTick()
		const requestId = mocks.startSession.mock.calls[0][0].clientRequestId

		wrapper.unmount()
		resolveStart({ sessionId: '102', status: 'ACTIVE' })
		await flushPromises()

		expect(fetchEventSource).not.toHaveBeenCalled()
		expect(mocks.deleteSession).not.toHaveBeenCalled()
		expect(sessionStorage.getItem('amha:pending-chat-turn:102')).toBe(requestId)
	})

	it('does not replay a completed turn when stale session storage remains', async () => {
		mocks.getHistorySessionMessages.mockResolvedValueOnce([
			userMessage(1, '我想聊聊最近的压力'),
			assistantMessage(2, '我们可以慢慢梳理。'),
		])
		sessionStorage.setItem('amha:pending-chat-turn:7', 'request-completed')
		wrapper = mountPage()
		await flushPromises()

		expect(fetchEventSource).not.toHaveBeenCalled()
		expect(sessionStorage.getItem('amha:pending-chat-turn:7')).toBeNull()
		expect(wrapper.text()).toContain('我们可以慢慢梳理。')
	})

	it('automatically reattaches when persisted history ends with the pending user message', async () => {
		mocks.getHistorySessionMessages.mockResolvedValueOnce([
			userMessage(1, '我想聊聊最近的压力'),
		])
		sessionStorage.setItem('amha:pending-chat-turn:7', 'request-pending')
		wrapper = mountPage()
		await flushPromises()

		expect(getHistorySessionMessages).toHaveBeenCalledWith('7')
		expect(fetchEventSource).toHaveBeenCalledTimes(1)
		expect(JSON.parse(vi.mocked(fetchEventSource).mock.calls[0][1].body as string)).toMatchObject({
			clientRequestId: 'request-pending',
			userMessage: '我想聊聊最近的压力',
		})
	})

	it('ignores late stream events after switching to another session', async () => {
		let oldStreamOptions: any
		mocks.fetchEventSource.mockImplementationOnce((_url: string, options: any) => {
			oldStreamOptions = options
			return new Promise((_resolve, reject) => {
				options.signal.addEventListener('abort', () =>
					reject(new DOMException('aborted', 'AbortError')),
				)
			})
		})
		mocks.getHistorySessionMessages.mockResolvedValueOnce([
			userMessage(11, '另一段会话中的文字'),
		])
		wrapper = mountPage()
		await flushPromises()

		await wrapper.get('[data-testid="message-input"]').setValue('第一段会话中的文字')
		await wrapper.get('.send-btn').trigger('click')
		await flushPromises()
		await wrapper.get('.session-item').trigger('click')
		await flushPromises()

		oldStreamOptions.onmessage({
			id: 'stale',
			event: 'message',
			data: JSON.stringify({ code: '200', data: { content: '旧流迟到内容' } }),
		})
		await nextTick()

		expect(wrapper.text()).toContain('另一段会话中的文字')
		expect(wrapper.text()).not.toContain('旧流迟到内容')
	})

	it('releases the sending state and offers reconnect after bounded stream failures', async () => {
		mocks.fetchEventSource.mockImplementationOnce(async (_url: string, options: any) => {
			await options.onopen(response)
			for (let attempt = 0; attempt < 4; attempt += 1) {
				options.onerror(new Error('connection lost'))
			}
		})
		wrapper = mountPage()
		await flushPromises()

		await wrapper.get('[data-testid="message-input"]').setValue('我需要一点支持')
		await wrapper.get('.send-btn').trigger('click')
		await flushPromises()

		expect(wrapper.text()).toContain('连接中断')
		expect(wrapper.find('.retry-stream').exists()).toBe(true)
		expect(wrapper.find('.typing-indicator').exists()).toBe(false)
		const originalRequest = JSON.parse(
			vi.mocked(fetchEventSource).mock.calls[0][1].body as string,
		)
		await wrapper.get('.retry-stream').trigger('click')
		await flushPromises()
		expect(fetchEventSource).toHaveBeenCalledTimes(2)
		expect(
			JSON.parse(vi.mocked(fetchEventSource).mock.calls[1][1].body as string)
				.clientRequestId,
		).toBe(originalRequest.clientRequestId)
	})

	it('treats a clean close without a terminal event as an interrupted turn', async () => {
		mocks.fetchEventSource.mockImplementationOnce(async (_url: string, options: any) => {
			await options.onopen(response)
			options.onclose()
		})
		wrapper = mountPage()
		await flushPromises()

		await wrapper.get('[data-testid="message-input"]').setValue('我还在等待回复')
		await wrapper.get('.send-btn').trigger('click')
		await flushPromises()

		expect(wrapper.text()).toContain('连接中断，回复未完成')
		expect(wrapper.find('.retry-stream').exists()).toBe(true)
		expect(wrapper.find('.typing-indicator').exists()).toBe(false)
	})

	it('preserves a pending turn and offers reconnect when the stream returns non-SSE data', async () => {
		mocks.fetchEventSource.mockImplementationOnce(async (_url: string, options: any) => {
			await options.onopen({
				headers: { get: () => 'application/json' },
				status: 401,
			} as unknown as Response)
		})
		wrapper = mountPage()
		await flushPromises()

		await wrapper.get('[data-testid="message-input"]').setValue('服务器接受了消息但流式连接失败')
		await wrapper.get('.send-btn').trigger('click')
		await flushPromises()
		const requestId = mocks.startSession.mock.calls[0][0].clientRequestId

		expect(sessionStorage.getItem('amha:pending-chat-turn:101')).toBe(requestId)
		expect(wrapper.find('.retry-stream').exists()).toBe(true)
		expect(wrapper.text()).toContain('连接中断')
	})

	it('blocks sending and clears the previous transcript while another session loads', async () => {
		let resolveHistory!: (value: Message[]) => void
		mocks.getHistorySessionMessages
			.mockResolvedValueOnce([userMessage(21, '旧会话内容')])
			.mockReturnValueOnce(new Promise(resolve => { resolveHistory = resolve }))
		wrapper = mountPage()
		await flushPromises()

		await wrapper.get('.session-item').trigger('click')
		await flushPromises()
		expect(wrapper.text()).toContain('旧会话内容')

		await wrapper.get('.session-item').trigger('click')
		await wrapper.get('[data-testid="message-input"]').setValue('切换时保留的草稿')
		await wrapper.get('.send-btn').trigger('click')

		expect(wrapper.text()).not.toContain('旧会话内容')
		expect(wrapper.text()).toContain('正在加载会话')
		expect(wrapper.get('.send-btn').attributes('disabled')).toBeDefined()
		expect(fetchEventSource).not.toHaveBeenCalled()
		expect(mocks.startSession).not.toHaveBeenCalled()

		resolveHistory([userMessage(22, '目标会话内容')])
		await flushPromises()
		expect(wrapper.text()).toContain('目标会话内容')
		expect((wrapper.get('[data-testid="message-input"]').element as HTMLTextAreaElement).value)
			.toBe('切换时保留的草稿')
	})

	it('shows a retryable error when session history cannot load and preserves the draft', async () => {
		mocks.getHistorySessionMessages
			.mockRejectedValueOnce(new Error('history unavailable'))
			.mockResolvedValueOnce([userMessage(23, '重试后加载的会话')])
		wrapper = mountPage()
		await flushPromises()
		await wrapper.get('[data-testid="message-input"]').setValue('不要丢失这段草稿')

		await wrapper.get('.session-item').trigger('click')
		await flushPromises()

		expect(wrapper.text()).toContain('会话加载失败，请重试')
		expect(wrapper.find('.retry-session-load').exists()).toBe(true)
		expect(wrapper.get('.send-btn').attributes('disabled')).toBeDefined()
		expect((wrapper.get('[data-testid="message-input"]').element as HTMLTextAreaElement).value)
			.toBe('不要丢失这段草稿')
		expect(fetchEventSource).not.toHaveBeenCalled()
		expect(mocks.getHistorySessionMessages).toHaveBeenCalledTimes(1)

		await wrapper.get('.retry-session-load').trigger('click')
		await flushPromises()
		expect(mocks.getHistorySessionMessages).toHaveBeenCalledTimes(2)
		expect(wrapper.text()).toContain('重试后加载的会话')
		expect(wrapper.text()).not.toContain('会话加载失败，请重试')
		expect((wrapper.get('[data-testid="message-input"]').element as HTMLTextAreaElement).value)
			.toBe('不要丢失这段草稿')
	})
})
