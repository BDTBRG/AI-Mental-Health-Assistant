import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Consultation from './consultation.vue'
import { fetchEventSource } from '@microsoft/fetch-event-source'
import { getHistorySessionMessages } from '@/api/user'
import type { ChatTurnRecovery, HistorySession, Message, StartSession } from '@/types/user'

const mocks = vi.hoisted(() => ({
	startSession: vi.fn(),
	recoverSessionStart: vi.fn(),
	recoverChatTurn: vi.fn(),
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
	recoverChatTurn: mocks.recoverChatTurn,
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

const DialogStub = defineComponent({
	props: { modelValue: { type: Boolean, default: false } },
	setup(props, { slots }) {
		return () => props.modelValue
			? h('section', { 'data-testid': 'memory-dialog' }, [
				slots.default?.(),
				h('footer', slots.footer?.()),
			])
			: null
	},
})

const SwitchStub = defineComponent({
	props: {
		modelValue: { type: Boolean, default: false },
		disabled: { type: Boolean, default: false },
	},
	emits: ['update:modelValue', 'change'],
	setup(props, { emit }) {
		return () => h('button', {
			'data-testid': 'memory-switch',
			'aria-pressed': String(props.modelValue),
			disabled: props.disabled,
			onClick: () => {
				const nextValue = !props.modelValue
				emit('update:modelValue', nextValue)
				emit('change', nextValue)
			},
		})
	},
})

const EmptyStub = defineComponent({
	props: { description: { type: String, default: '' } },
	setup(props) {
		return () => h('div', { 'data-testid': 'memory-empty' }, props.description)
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

const contextResponse = (payload: Record<string, unknown>) => ({
	status: 200,
	headers: { get: () => 'application/json' },
	clone: () => ({ json: async () => payload }),
} as unknown as Response)

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
				'el-dialog': DialogStub,
				'el-empty': EmptyStub,
				'el-icon': true,
				'el-image': true,
				'el-input': InputStub,
				'el-switch': SwitchStub,
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
		mocks.getHistorySessionList.mockReset().mockResolvedValue({ records: [session] })
		mocks.getHistorySessionMessages.mockReset().mockResolvedValue([])
		mocks.recoverSessionStart.mockReset().mockResolvedValue(null)
		mocks.recoverChatTurn.mockReset().mockResolvedValue(null)
		mocks.getEmotionAnalysis.mockReset().mockResolvedValue(null)
		mocks.fetchEventSource.mockReset().mockImplementation(completeStream)
		mocks.startSession.mockReset().mockResolvedValue({
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
		mocks.recoverChatTurn.mockResolvedValueOnce({
			clientRequestId: requestId,
			status: 'PENDING',
			userMessageId: 31,
			userMessage: content,
			recoverable: true,
		})
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
		mocks.recoverChatTurn.mockImplementationOnce(async (_sessionId, clientRequestId) => ({
			clientRequestId,
			status: 'PENDING',
			userMessageId: 1041,
			userMessage: content,
			recoverable: true,
		}))
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

	it('recovers a pending turn through its request id before reattaching', async () => {
		const requestId = 'request-pending'
		mocks.getHistorySessionMessages.mockResolvedValueOnce([
			userMessage(1, '我想聊聊最近的压力'),
		])
		mocks.recoverChatTurn.mockResolvedValueOnce({
			clientRequestId: requestId,
			status: 'RUNNING',
			userMessageId: 1,
			userMessage: '我想聊聊最近的压力',
			recoverable: true,
		})
		sessionStorage.setItem('amha:pending-chat-turn:7', requestId)
		wrapper = mountPage()
		await flushPromises()

		expect(getHistorySessionMessages).toHaveBeenCalledWith('7')
		expect(mocks.recoverChatTurn).toHaveBeenCalledWith('7', requestId)
		expect(fetchEventSource).toHaveBeenCalledTimes(1)
		expect(JSON.parse(vi.mocked(fetchEventSource).mock.calls[0][1].body as string)).toMatchObject({
			clientRequestId: requestId,
			userMessage: '我想聊聊最近的压力',
		})
	})

	it('recovers the exact linked user message instead of pairing the request id with the latest message', async () => {
		const requestId = 'stale-request-after-refresh'
		const originalMessage = '原快照过期请求的确切内容'
		sessionStorage.setItem('amha:pending-chat-turn:7', requestId)
		const history = [
			userMessage(71, originalMessage),
			userMessage(72, '另一设备后来写入的消息'),
		]
		mocks.getHistorySessionMessages.mockResolvedValue(history)
		mocks.recoverChatTurn.mockResolvedValueOnce({
			clientRequestId: requestId,
			status: 'CONTEXT_UNAVAILABLE',
			userMessageId: 71,
			userMessage: originalMessage,
			recoverable: true,
		})
		wrapper = mountPage()
		await flushPromises()

		expect(mocks.recoverChatTurn).toHaveBeenCalledWith('7', requestId)
		expect(fetchEventSource).toHaveBeenCalledTimes(1)
		expect(JSON.parse(vi.mocked(fetchEventSource).mock.calls[0][1].body as string))
			.toMatchObject({ clientRequestId: requestId, userMessage: originalMessage })
		expect(wrapper.text()).toContain(originalMessage)
		expect(wrapper.text()).toContain('另一设备后来写入的消息')
	})

	it('clears an unrecoverable turn marker without replaying another message', async () => {
		const requestId = 'superseded-request-after-refresh'
		sessionStorage.setItem('amha:pending-chat-turn:7', requestId)
		mocks.getHistorySessionMessages.mockResolvedValueOnce([
			userMessage(73, '后续设备消息'),
		])
		mocks.recoverChatTurn.mockResolvedValueOnce({
			clientRequestId: requestId,
			status: 'CONTEXT_SUPERSEDED',
			userMessageId: null,
			userMessage: null,
			recoverable: false,
		})
		wrapper = mountPage()
		await flushPromises()

		expect(fetchEventSource).not.toHaveBeenCalled()
		expect(sessionStorage.getItem('amha:pending-chat-turn:7')).toBeNull()
		expect(wrapper.text()).toContain('后续设备消息')
	})

	it('preserves the pending marker and blocks replay when recovery echoes a different request id', async () => {
		const requestId = 'pending-request-recovery-id-mismatch'
		sessionStorage.setItem('amha:pending-chat-turn:7', requestId)
		mocks.recoverChatTurn.mockResolvedValueOnce({
			clientRequestId: 'another-request-from-server',
			status: 'PENDING',
			userMessageId: 74,
			userMessage: '不能与其他请求绑定的消息',
			recoverable: true,
		})
		wrapper = mountPage()
		await flushPromises()

		expect(sessionStorage.getItem('amha:pending-chat-turn:7')).toBe(requestId)
		expect(wrapper.get('.send-btn').attributes('disabled')).toBeDefined()
		expect(wrapper.get('.pending-turn-recovery-protocol-error').text())
			.toContain('恢复接口返回的请求标识与本地记录不一致')
		expect(fetchEventSource).not.toHaveBeenCalled()
	})

	it('discovers an older pending session through summary pages without expanding the visible list', async () => {
		const requestId = 'pending-on-an-older-session-page'
		const content = '较旧会话中需要恢复的消息'
		const olderSession: HistorySession = { ...session, id: 7, sessionTitle: '较旧的待恢复会话' }
		const firstPage = {
			records: Array.from({ length: 10 }, (_, index) => ({
				...session,
				id: 30 - index,
				sessionTitle: `最近会话 ${index + 1}`,
			})),
			current: 1,
			size: 10,
			total: 11,
		}
		const secondPage = { records: [olderSession], current: 2, size: 10, total: 11 }
		mocks.getHistorySessionList.mockImplementation(async ({ pageNum }: { pageNum: number }) =>
			pageNum === 1 ? firstPage : secondPage,
		)
		mocks.getHistorySessionMessages.mockImplementation(async sessionId =>
			sessionId === '7' ? [{ ...userMessage(71, content), sessionId: 7 }] : [],
		)
		mocks.recoverChatTurn.mockResolvedValueOnce({
			clientRequestId: requestId,
			status: 'PENDING',
			userMessageId: 71,
			userMessage: content,
			recoverable: true,
		})
		mocks.fetchEventSource.mockImplementationOnce(async (_url: string, options: any) => {
			await options.onopen(response)
		})
		sessionStorage.setItem('amha:pending-chat-turn:7', requestId)
		wrapper = mountPage()
		await flushPromises()

		expect(mocks.getHistorySessionList).toHaveBeenNthCalledWith(1, { pageNum: 1, pageSize: 10 })
		expect(mocks.getHistorySessionList).toHaveBeenNthCalledWith(2, { pageNum: 2, pageSize: 10 })
		expect(wrapper.findAll('.session-item')).toHaveLength(10)
		expect(mocks.getHistorySessionMessages).toHaveBeenCalledWith('7')
		expect(mocks.recoverChatTurn).toHaveBeenCalledWith('7', requestId)
		expect(fetchEventSource).toHaveBeenCalledTimes(1)
		expect(JSON.parse(vi.mocked(fetchEventSource).mock.calls[0][1].body as string))
			.toMatchObject({ clientRequestId: requestId, userMessage: content })
	})

	it('lets the user select an older pending session from a recovery queue without changing the visible page', async () => {
		const latestRequestId = 'latest-pending-session'
		const olderRequestId = 'older-pending-session'
		const olderContent = '第 11 个会话里的待恢复消息'
		const olderSession: HistorySession = { ...session, id: 7, sessionTitle: '第 11 个待恢复会话' }
		const firstPage = {
			records: Array.from({ length: 10 }, (_, index) => ({
				...session,
				id: 30 - index,
				sessionTitle: `最近会话 ${index + 1}`,
			})),
			current: 1,
			size: 10,
			total: 11,
		}
		mocks.getHistorySessionList.mockImplementation(async ({ pageNum }: { pageNum: number }) =>
			pageNum === 1 ? firstPage : { records: [olderSession], current: 2, size: 10, total: 11 },
		)
		mocks.getHistorySessionMessages.mockImplementation(async (sessionId: string) =>
			sessionId === '7'
				? [{ ...userMessage(71, olderContent), sessionId: 7 }]
				: [{ ...userMessage(70, '较新的待恢复消息'), sessionId: 30 }],
		)
		mocks.recoverChatTurn.mockImplementation(async (_sessionId: string, clientRequestId: string) => ({
			clientRequestId,
			status: 'PENDING',
			userMessageId: clientRequestId === olderRequestId ? 71 : 70,
			userMessage: clientRequestId === olderRequestId ? olderContent : '较新的待恢复消息',
			recoverable: true,
		}))
		mocks.fetchEventSource.mockImplementation(async (_url: string, options: any) => {
			await options.onopen(response)
		})
		sessionStorage.setItem('amha:pending-chat-turn:30', latestRequestId)
		sessionStorage.setItem('amha:pending-chat-turn:7', olderRequestId)
		wrapper = mountPage()
		await flushPromises()

		expect(wrapper.findAll('.session-item')).toHaveLength(10)
		expect(wrapper.findAll('.pending-recovery-session')).toHaveLength(2)
		expect(wrapper.get('[data-testid="pending-recovery-session-7"]').text())
			.toContain('第 11 个待恢复会话')
		expect(wrapper.get('[data-testid="pending-recovery-session-7"]').text())
			.toContain('待恢复')
		await wrapper.get('[data-testid="pending-recovery-session-7"]').trigger('click')
		await flushPromises()

		expect(mocks.recoverChatTurn).toHaveBeenCalledWith('7', olderRequestId)
		expect(wrapper.text()).toContain(olderContent)
		expect(fetchEventSource).toHaveBeenCalledTimes(2)
		expect(JSON.parse(vi.mocked(fetchEventSource).mock.calls[1][1].body as string))
			.toMatchObject({ sessionId: '7', clientRequestId: olderRequestId, userMessage: olderContent })
	})

	it('scans pending turns while a pending first-session recovery is waiting without auto-replaying both', async () => {
		const startRequestId = 'pending-start-and-turn-recovery'
		const olderRequestId = 'older-turn-during-start-recovery'
		let resolveStartRecovery!: (value: StartSession | null) => void
		mocks.recoverSessionStart.mockReturnValueOnce(new Promise(resolve => {
			resolveStartRecovery = resolve
		}))
		mocks.getHistorySessionList.mockResolvedValueOnce({
			records: [session],
			current: 1,
			size: 10,
			total: 1,
		})
		mocks.getHistorySessionMessages.mockImplementation(async sessionId =>
			sessionId === '101' ? [userMessage(101, '首条消息恢复内容')] : [userMessage(71, '旧会话待恢复内容')],
		)
		mocks.recoverChatTurn.mockImplementation(async (sessionId: string, clientRequestId: string) => ({
			clientRequestId,
			status: 'PENDING',
			userMessageId: sessionId === '101' ? 101 : 71,
			userMessage: sessionId === '101' ? '首条消息恢复内容' : '旧会话待恢复内容',
			recoverable: true,
		}))
		sessionStorage.setItem('amha:pending-session-start', startRequestId)
		sessionStorage.setItem('amha:pending-chat-turn:7', olderRequestId)
		wrapper = mountPage()
		await flushPromises()

		expect(wrapper.get('[data-testid="pending-recovery-session-7"]').text())
			.toContain('已保存的会话')
		expect(mocks.recoverChatTurn).not.toHaveBeenCalled()
		expect(fetchEventSource).not.toHaveBeenCalled()

		resolveStartRecovery({
			sessionId: 101,
			status: 'ACTIVE',
			startTime: 1700000000000,
			sessionTitle: '恢复出的首条会话',
			messageCount: 1,
		})
		await flushPromises()

		expect(mocks.recoverChatTurn).toHaveBeenCalledTimes(1)
		expect(mocks.recoverChatTurn).toHaveBeenNthCalledWith(1, '101', startRequestId)
		expect(fetchEventSource).toHaveBeenCalledTimes(1)
		expect(sessionStorage.getItem('amha:pending-chat-turn:7')).toBe(olderRequestId)

		await wrapper.get('[data-testid="pending-recovery-session-7"]').trigger('click')
		await flushPromises()
		expect(mocks.recoverChatTurn).toHaveBeenCalledTimes(2)
		expect(mocks.recoverChatTurn).toHaveBeenNthCalledWith(2, '7', olderRequestId)
		expect(fetchEventSource).toHaveBeenCalledTimes(2)
	})

	it('does not leave the send gate stuck when deleting the session during pending-turn recovery', async () => {
		const requestId = 'delete-session-during-recovery'
		let resolveRecovery!: (value: ChatTurnRecovery | null) => void
		mocks.getHistorySessionMessages.mockResolvedValueOnce([userMessage(72, '恢复等待中的消息')])
		mocks.recoverChatTurn.mockReturnValueOnce(new Promise(resolve => {
			resolveRecovery = resolve
		}))
		sessionStorage.setItem('amha:pending-chat-turn:7', requestId)
		wrapper = mountPage()
		await flushPromises()
		expect(wrapper.get('.send-btn').attributes('disabled')).toBeDefined()

		await wrapper.get('.session-actions button').trigger('click')
		await flushPromises()
		expect(mocks.deleteSession).toHaveBeenCalledWith('7')
		expect(wrapper.get('.send-btn').attributes('disabled')).toBeUndefined()
		expect(wrapper.get('button[title="新建会话"]').attributes('disabled')).toBeUndefined()
		expect(sessionStorage.getItem('amha:pending-chat-turn:7')).toBeNull()

		resolveRecovery({
			clientRequestId: requestId,
			status: 'PENDING',
			userMessageId: 72,
			userMessage: '恢复等待中的消息',
			recoverable: true,
		})
		await flushPromises()
		expect(fetchEventSource).not.toHaveBeenCalled()
		expect(wrapper.get('.send-btn').attributes('disabled')).toBeUndefined()
	})

	it('releases the old recovery gate when switching sessions before recovery completes', async () => {
		const requestId = 'switch-session-during-recovery'
		const otherSession: HistorySession = { ...session, id: 8, sessionTitle: '另一个会话' }
		let resolveRecovery!: (value: ChatTurnRecovery | null) => void
		mocks.getHistorySessionList.mockResolvedValueOnce({ records: [session, otherSession] })
		mocks.getHistorySessionMessages.mockImplementation(async sessionId =>
			sessionId === '7' ? [userMessage(73, '恢复等待中的消息')] : [{ ...userMessage(80, '切换后的会话'), sessionId: 8 }],
		)
		mocks.recoverChatTurn.mockReturnValueOnce(new Promise(resolve => {
			resolveRecovery = resolve
		}))
		sessionStorage.setItem('amha:pending-chat-turn:7', requestId)
		wrapper = mountPage()
		await flushPromises()
		expect(wrapper.get('.send-btn').attributes('disabled')).toBeDefined()

		await wrapper.findAll('.session-item')[1].trigger('click')
		await flushPromises()
		expect(wrapper.text()).toContain('切换后的会话')
		expect(wrapper.get('.send-btn').attributes('disabled')).toBeUndefined()

		resolveRecovery({
			clientRequestId: requestId,
			status: 'PENDING',
			userMessageId: 73,
			userMessage: '恢复等待中的消息',
			recoverable: true,
		})
		await flushPromises()
		expect(fetchEventSource).not.toHaveBeenCalled()
		expect(wrapper.get('.send-btn').attributes('disabled')).toBeUndefined()
	})

	it('blocks a new first message until pending-turn recovery has checked session history', async () => {
		const requestId = 'restore-before-first-send'
		sessionStorage.setItem('amha:pending-chat-turn:7', requestId)
		let resolveSessionList!: (value: { records: HistorySession[] }) => void
		mocks.getHistorySessionList.mockReturnValueOnce(new Promise(resolve => {
			resolveSessionList = resolve
		}))
		mocks.getHistorySessionMessages.mockResolvedValueOnce([
			userMessage(51, '刷新前已保存的待处理消息'),
		])
		mocks.recoverChatTurn.mockResolvedValueOnce({
			clientRequestId: requestId,
			status: 'PENDING',
			userMessageId: 51,
			userMessage: '刷新前已保存的待处理消息',
			recoverable: true,
		})
		wrapper = mountPage()
		await nextTick()

		expect(wrapper.get('.send-btn').attributes('disabled')).toBeDefined()
		expect(fetchEventSource).not.toHaveBeenCalled()

		resolveSessionList({ records: [session] })
		await flushPromises()

		expect(fetchEventSource).toHaveBeenCalledTimes(1)
		expect(JSON.parse(vi.mocked(fetchEventSource).mock.calls[0][1].body as string))
			.toMatchObject({ clientRequestId: requestId, userMessage: '刷新前已保存的待处理消息' })
	})

	it('keeps new-session navigation disabled while a pending session history is loading', async () => {
		const requestId = 'restore-during-history-load'
		sessionStorage.setItem('amha:pending-chat-turn:7', requestId)
		let resolveHistory!: (messages: Message[]) => void
		mocks.getHistorySessionMessages.mockReturnValueOnce(new Promise(resolve => {
			resolveHistory = resolve
		}))
		mocks.recoverChatTurn.mockResolvedValueOnce({
			clientRequestId: requestId,
			status: 'CONTEXT_UNAVAILABLE',
			userMessageId: 52,
			userMessage: '恢复中的持久化消息',
			recoverable: true,
		})
		wrapper = mountPage()
		await flushPromises()

		expect(wrapper.get('.send-btn').attributes('disabled')).toBeDefined()
		expect(wrapper.get('button[title="新建会话"]').attributes('disabled')).toBeDefined()
		expect(fetchEventSource).not.toHaveBeenCalled()

		resolveHistory([userMessage(52, '恢复中的持久化消息')])
		await flushPromises()

		expect(fetchEventSource).toHaveBeenCalledTimes(1)
		expect(JSON.parse(vi.mocked(fetchEventSource).mock.calls[0][1].body as string))
			.toMatchObject({ clientRequestId: requestId, userMessage: '恢复中的持久化消息' })
	})

	it('deduplicates retries while the initial session-list recovery request is pending', async () => {
		const requestId = 'restore-after-list-error'
		sessionStorage.setItem('amha:pending-chat-turn:7', requestId)
		let resolveSessionList!: (value: { records: HistorySession[] }) => void
		mocks.getHistorySessionList
			.mockRejectedValueOnce(new Error('temporary list failure'))
			.mockReturnValueOnce(new Promise(resolve => {
				resolveSessionList = resolve
			}))
		mocks.getHistorySessionMessages.mockResolvedValueOnce([
			userMessage(53, '列表重试后恢复的持久化消息'),
		])
		mocks.recoverChatTurn.mockResolvedValueOnce({
			clientRequestId: requestId,
			status: 'CONTEXT_UNAVAILABLE',
			userMessageId: 53,
			userMessage: '列表重试后恢复的持久化消息',
			recoverable: true,
		})
		wrapper = mountPage()
		await flushPromises()
		expect(mocks.getHistorySessionList).toHaveBeenCalledTimes(1)

		const retryButton = wrapper.get('.retry-session-load')
		await retryButton.trigger('click')
		await nextTick()
		expect(mocks.getHistorySessionList).toHaveBeenCalledTimes(2)
		expect(retryButton.attributes('disabled')).toBeDefined()

		await retryButton.trigger('click')
		expect(mocks.getHistorySessionList).toHaveBeenCalledTimes(2)

		resolveSessionList({ records: [session] })
		await flushPromises()
		expect(fetchEventSource).toHaveBeenCalledTimes(1)
		expect(JSON.parse(vi.mocked(fetchEventSource).mock.calls[0][1].body as string))
			.toMatchObject({ clientRequestId: requestId, userMessage: '列表重试后恢复的持久化消息' })
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

	it('shows summary preparation as a manual check and reuses the same request id', async () => {
		mocks.fetchEventSource
			.mockImplementationOnce(completeStream)
			.mockImplementationOnce(async (_url: string, options: any) => {
				const requestId = JSON.parse(options.body).clientRequestId
				await options.onopen(contextResponse({
					code: 'CONTEXT_SUMMARY_PENDING',
					message: '正在整理较早的对话，请稍后重试',
					data: { clientRequestId: requestId, retryable: true, retryAfterMs: 1000 },
				}))
			})
			.mockImplementationOnce(async (_url: string, options: any) => {
				const requestId = JSON.parse(options.body).clientRequestId
				await options.onopen(contextResponse({
					code: 'CONTEXT_SUMMARY_PENDING',
					message: '正在整理较早的对话，请稍后重试',
					data: { clientRequestId: requestId, retryable: true, retryAfterMs: 1000 },
				}))
			})
		wrapper = mountPage()
		await flushPromises()

		const input = wrapper.get('[data-testid="message-input"]')
		await input.setValue('需要保留但不能存进浏览器存储的消息')
		await wrapper.get('.send-btn').trigger('click')
		await flushPromises()
		await input.setValue('希望基于旧上下文继续的一条消息')
		await wrapper.get('.send-btn').trigger('click')
		await flushPromises()

		expect(wrapper.text()).toContain('正在整理较早的对话')
		expect(wrapper.text()).toContain('你的消息已保留')
		expect(wrapper.text()).toContain('希望基于旧上下文继续的一条消息')
		expect(wrapper.get('.retry-stream').text()).toContain('重新检查')
		expect((input.element as HTMLTextAreaElement).value).toBe('')
		expect(wrapper.get('.send-btn').attributes('disabled')).toBeDefined()
		expect(fetchEventSource).toHaveBeenCalledTimes(2)
		const requestId = JSON.parse(vi.mocked(fetchEventSource).mock.calls[1][1].body as string).clientRequestId
		expect(sessionStorage.getItem('amha:pending-chat-turn:101')).toBe(requestId)
		expect(sessionStorage.getItem('amha:pending-chat-turn:101'))
			.not.toContain('希望基于旧上下文继续的一条消息')

		await wrapper.get('.retry-stream').trigger('click')
		await flushPromises()
		expect(fetchEventSource).toHaveBeenCalledTimes(3)
		expect(JSON.parse(vi.mocked(fetchEventSource).mock.calls[2][1].body as string))
			.toMatchObject({ clientRequestId: requestId, userMessage: '希望基于旧上下文继续的一条消息' })
	})

	it('distinguishes unavailable summary from ongoing work and keeps a manual retry', async () => {
		mocks.fetchEventSource
			.mockImplementationOnce(completeStream)
			.mockImplementationOnce(async (_url: string, options: any) => {
				const requestId = JSON.parse(options.body).clientRequestId
				await options.onopen(contextResponse({
					code: 'CONTEXT_SUMMARY_UNAVAILABLE',
					message: '当前暂时无法整理较早的对话，请稍后重试',
					data: { clientRequestId: requestId, retryable: true, retryAfterMs: 1000 },
				}))
			})
		wrapper = mountPage()
		await flushPromises()

		const input = wrapper.get('[data-testid="message-input"]')
		await input.setValue('首轮消息')
		await wrapper.get('.send-btn').trigger('click')
		await flushPromises()
		await input.setValue('等待故障恢复的消息')
		await wrapper.get('.send-btn').trigger('click')
		await flushPromises()

		expect(wrapper.text()).toContain('暂时无法整理较早的对话')
		expect(wrapper.text()).not.toContain('正在整理较早的对话')
		expect(wrapper.get('.retry-stream').text()).toContain('稍后重试')
		expect(fetchEventSource).toHaveBeenCalledTimes(2)
		const requestId = JSON.parse(vi.mocked(fetchEventSource).mock.calls[1][1].body as string).clientRequestId
		expect(sessionStorage.getItem('amha:pending-chat-turn:101')).toBe(requestId)
	})

	it('keeps the original pending marker and send gate when the server echoes a different request id', async () => {
		let requestId = ''
		const echoedRequestId = 'server-echoed-another-request'
		mocks.fetchEventSource
			.mockImplementationOnce(completeStream)
			.mockImplementationOnce(async (_url: string, options: any) => {
				requestId = JSON.parse(options.body).clientRequestId
				await options.onopen(contextResponse({
					code: 'CONTEXT_SUMMARY_PENDING',
					message: '正在整理较早的对话，请稍后重试',
					data: { clientRequestId: echoedRequestId, retryable: true, retryAfterMs: 1000 },
				}))
			})
		wrapper = mountPage()
		await flushPromises()

		const input = wrapper.get('[data-testid="message-input"]')
		await input.setValue('首轮消息')
		await wrapper.get('.send-btn').trigger('click')
		await flushPromises()
		await input.setValue('响应轮次标识不匹配时要保留的消息')
		await wrapper.get('.send-btn').trigger('click')
		await flushPromises()

		expect(requestId).not.toBe(echoedRequestId)
		expect(sessionStorage.getItem('amha:pending-chat-turn:101')).toBe(requestId)
		expect(wrapper.get('.send-btn').attributes('disabled')).toBeDefined()
		expect(wrapper.text()).toContain('无法与本次请求匹配')
		expect(fetchEventSource).toHaveBeenCalledTimes(2)
	})

	it('preserves the existing history timestamp on the recovered user message', async () => {
		const requestId = 'recover-with-history-created-at'
		const createdAt = '2026-10-10T07:30:00'
		const originalContent = '历史中已有准确时间的消息'
		mocks.getHistorySessionMessages.mockResolvedValueOnce([
			{ ...userMessage(81, originalContent), createdAt },
		])
		mocks.recoverChatTurn.mockResolvedValueOnce({
			clientRequestId: requestId,
			status: 'PENDING',
			userMessageId: 81,
			userMessage: originalContent,
			recoverable: true,
		})
		mocks.fetchEventSource.mockImplementationOnce(async (_url: string, options: any) => {
			await options.onopen(response)
		})
		sessionStorage.setItem('amha:pending-chat-turn:7', requestId)
		wrapper = mountPage()
		await flushPromises()

		expect(wrapper.get('.user-message .message-time').text()).toBe(createdAt)
	})

	it('marks the timestamp unknown when a recovered message is absent from history', async () => {
		const requestId = 'recover-without-history-created-at'
		mocks.getHistorySessionMessages.mockResolvedValueOnce([])
		mocks.recoverChatTurn.mockResolvedValueOnce({
			clientRequestId: requestId,
			status: 'PENDING',
			userMessageId: 82,
			userMessage: '恢复接口确认但历史接口没有的消息',
			recoverable: true,
		})
		mocks.fetchEventSource.mockImplementationOnce(async (_url: string, options: any) => {
			await options.onopen(response)
		})
		sessionStorage.setItem('amha:pending-chat-turn:7', requestId)
		wrapper = mountPage()
		await flushPromises()

		expect(wrapper.get('.user-message .message-time').text()).toBe('时间未知')
	})

	it('keeps the pending marker when exact turn recovery is temporarily unavailable', async () => {
		const requestId = 'pending-recovery-network-error'
		sessionStorage.setItem('amha:pending-chat-turn:7', requestId)
		mocks.recoverChatTurn
			.mockRejectedValueOnce(new Error('recovery endpoint unavailable'))
			.mockResolvedValueOnce({
				clientRequestId: requestId,
				status: 'CONTEXT_UNAVAILABLE',
				userMessageId: 74,
				userMessage: '可在重试后安全恢复的消息',
				recoverable: true,
			})
		mocks.getHistorySessionMessages.mockResolvedValue([userMessage(74, '可在重试后安全恢复的消息')])
		wrapper = mountPage()
		await flushPromises()

		expect(sessionStorage.getItem('amha:pending-chat-turn:7')).toBe(requestId)
		expect(wrapper.text()).toContain('会话加载失败，请重试')
		expect(fetchEventSource).not.toHaveBeenCalled()

		await wrapper.get('.retry-session-load').trigger('click')
		await flushPromises()

		expect(mocks.recoverChatTurn).toHaveBeenCalledTimes(2)
		expect(fetchEventSource).toHaveBeenCalledTimes(1)
		expect(JSON.parse(vi.mocked(fetchEventSource).mock.calls[0][1].body as string))
			.toMatchObject({ clientRequestId: requestId, userMessage: '可在重试后安全恢复的消息' })
	})

	it('keeps the request retryable when its prepared context snapshot becomes stale', async () => {
		let requestId = ''
		mocks.fetchEventSource
			.mockImplementationOnce(completeStream)
			.mockImplementationOnce(async (_url: string, options: any) => {
				requestId = JSON.parse(options.body).clientRequestId
				await options.onopen(contextResponse({
					code: 'CONTEXT_SNAPSHOT_STALE',
					message: '会话内容已更新，请重试本条消息',
					data: { clientRequestId: requestId, retryable: true, retryAfterMs: 1 },
				}))
			})
			.mockImplementationOnce(completeStream)
		wrapper = mountPage()
		await flushPromises()

		const input = wrapper.get('[data-testid="message-input"]')
		await input.setValue('首轮消息')
		await wrapper.get('.send-btn').trigger('click')
		await flushPromises()
		await input.setValue('在旧上下文准备期间产生的新消息')
		await wrapper.get('.send-btn').trigger('click')
		await flushPromises()

		expect(wrapper.text()).toContain('会话内容刚刚发生变化')
		expect(wrapper.get('.retry-stream').text()).toContain('重试本轮')
		expect(sessionStorage.getItem('amha:pending-chat-turn:101')).toBe(requestId)
		expect(wrapper.get('.send-btn').attributes('disabled')).toBeDefined()
		expect((input.element as HTMLTextAreaElement).value).toBe('')

		await wrapper.get('.retry-stream').trigger('click')
		await flushPromises()
		expect(JSON.parse(vi.mocked(fetchEventSource).mock.calls[2][1].body as string))
			.toMatchObject({ clientRequestId: requestId, userMessage: '在旧上下文准备期间产生的新消息' })
		expect((input.element as HTMLTextAreaElement).value).toBe('')
		expect(sessionStorage.getItem('amha:pending-chat-turn:101')).toBeNull()
	})

	it('blocks a second send while a turn is pending and preserves a newer recovery marker', async () => {
		const respondWithPending = async (_url: string, options: any) => {
			const requestId = JSON.parse(options.body).clientRequestId
			await options.onopen(contextResponse({
				code: 'CONTEXT_SUMMARY_PENDING',
				message: '正在整理较早的对话，请稍后重试',
				data: { clientRequestId: requestId, retryable: true, retryAfterMs: 1000 },
			}))
		}
		mocks.fetchEventSource
			.mockImplementationOnce(completeStream)
			.mockImplementationOnce(respondWithPending)
			.mockImplementationOnce(async (_url: string, options: any) => {
				const requestId = JSON.parse(options.body).clientRequestId
				await options.onopen(contextResponse({
					code: 'CONTEXT_REQUEST_SUPERSEDED',
					message: '该请求已被后续对话替代，请从最近一条消息继续',
					data: { clientRequestId: requestId, retryable: false, retryAfterMs: 0 },
				}))
			})
		wrapper = mountPage()
		await flushPromises()

		const input = wrapper.get('[data-testid="message-input"]')
		await input.setValue('首轮消息')
		await wrapper.get('.send-btn').trigger('click')
		await flushPromises()

		await input.setValue('较早的待整理消息')
		await wrapper.get('.send-btn').trigger('click')
		await flushPromises()
		const olderRequestId = JSON.parse(vi.mocked(fetchEventSource).mock.calls[1][1].body as string).clientRequestId
		expect(wrapper.get('.send-btn').attributes('disabled')).toBeDefined()
		expect(fetchEventSource).toHaveBeenCalledTimes(2)
		await input.setValue('不能越过待处理轮次发送的新消息')
		await wrapper.get('.send-btn').trigger('click')
		await flushPromises()
		expect(fetchEventSource).toHaveBeenCalledTimes(2)
		expect((input.element as HTMLTextAreaElement).value).toBe('不能越过待处理轮次发送的新消息')
		const newerRequestId = 'newer-request-from-a-recovery-race'
		sessionStorage.setItem('amha:pending-chat-turn:101', newerRequestId)

		await wrapper.get('.retry-stream').trigger('click')
		await flushPromises()

		expect(JSON.parse(vi.mocked(fetchEventSource).mock.calls[2][1].body as string)
			.clientRequestId).toBe(olderRequestId)
		expect(sessionStorage.getItem('amha:pending-chat-turn:101')).toBe(newerRequestId)
	})

	it.each([
		{
			code: 'CONTEXT_WINDOW_TOO_LARGE',
			message: '近期对话超过当前安全上下文上限',
		},
		{
			code: 'CONTEXT_REQUEST_SUPERSEDED',
			message: '这条请求已被后续消息替代',
		},
	])('does not offer automatic retry for terminal context response $code', async ({ code, message }) => {
		mocks.fetchEventSource
			.mockImplementationOnce(completeStream)
			.mockImplementationOnce(async (_url: string, options: any) => {
				const requestId = JSON.parse(options.body).clientRequestId
				await options.onopen(contextResponse({
					code,
					message,
					data: { clientRequestId: requestId, retryable: false, retryAfterMs: 0 },
				}))
			})
		wrapper = mountPage()
		await flushPromises()

		const input = wrapper.get('[data-testid="message-input"]')
		await input.setValue('首轮消息')
		await wrapper.get('.send-btn').trigger('click')
		await flushPromises()
		await input.setValue('需要人工决定如何继续的消息')
		await wrapper.get('.send-btn').trigger('click')
		await flushPromises()

		expect(wrapper.text()).toContain(message)
		expect(wrapper.find('.retry-stream').exists()).toBe(false)
		expect(sessionStorage.getItem('amha:pending-chat-turn:101')).toBeNull()
		expect((input.element as HTMLTextAreaElement).value).toBe('')
		expect(fetchEventSource).toHaveBeenCalledTimes(2)
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

describe('personalization memory state', () => {
	let wrapper: ReturnType<typeof mountPage> | undefined

	beforeEach(() => {
		mocks.getPersonalizationConsent.mockReset()
		mocks.getAgentMemories.mockReset()
		mocks.setPersonalizationConsent.mockReset()
		mocks.getHistorySessionList.mockReset().mockResolvedValue({ records: [session] })
		mocks.getHistorySessionMessages.mockReset().mockResolvedValue([])
		mocks.getEmotionAnalysis.mockReset().mockResolvedValue(null)
		mocks.fetchEventSource.mockReset().mockImplementation(completeStream)
	})

	afterEach(() => {
		wrapper?.unmount()
		wrapper = undefined
	})

	const openMemorySettings = async () => {
		wrapper = mountPage()
		await flushPromises()
		const openButton = wrapper.findAll('button').find(button => button.text().includes('管理个性化记忆'))
		if (!openButton) throw new Error('memory settings button was not rendered')
		await openButton.trigger('click')
		await flushPromises()
	}

	it('does not present failed consent or memory reads as disabled and empty', async () => {
		mocks.getPersonalizationConsent.mockRejectedValueOnce(new Error('consent unavailable'))
		mocks.getAgentMemories.mockResolvedValueOnce([])
		await openMemorySettings()

		expect(wrapper?.text()).toContain('无法确认个性化记忆授权与列表状态')
		expect(wrapper?.find('[data-testid="memory-empty"]').exists()).toBe(false)
		expect(wrapper?.find('[data-testid="memory-switch"]').exists()).toBe(false)
		expect(wrapper?.find('.retry-memory-settings').exists()).toBe(true)
	})

	it('reloads consent and memories before showing the empty or saved state', async () => {
		mocks.getPersonalizationConsent
			.mockRejectedValueOnce(new Error('temporary failure'))
			.mockResolvedValueOnce({ enabled: true })
		mocks.getAgentMemories
			.mockResolvedValueOnce([])
			.mockResolvedValueOnce([{ id: 9, content: '偏好简短回答', expiresAt: null }])
		await openMemorySettings()

		await wrapper?.get('.retry-memory-settings').trigger('click')
		await flushPromises()

		expect(wrapper?.get('[data-testid="memory-switch"]').attributes('disabled')).toBeUndefined()
		expect(wrapper?.get('[data-testid="memory-switch"]').attributes('aria-pressed')).toBe('true')
		expect(wrapper?.find('[data-testid="memory-empty"]').exists()).toBe(false)
		expect(wrapper?.text()).toContain('偏好简短回答')
		expect(wrapper?.text()).not.toContain('无法确认个性化记忆授权与列表状态')
	})

	it('hides consent controls when an update response leaves the server state uncertain', async () => {
		mocks.getPersonalizationConsent.mockResolvedValueOnce({ enabled: false })
		mocks.getAgentMemories.mockResolvedValueOnce([])
		mocks.setPersonalizationConsent.mockRejectedValueOnce(new Error('response lost'))
		await openMemorySettings()

		await wrapper?.get('[data-testid="memory-switch"]').trigger('click')
		await flushPromises()

		expect(mocks.setPersonalizationConsent).toHaveBeenCalledWith(true)
		expect(wrapper?.text()).toContain('无法确认个性化记忆授权与列表状态')
		expect(wrapper?.find('[data-testid="memory-switch"]').exists()).toBe(false)
		expect(wrapper?.find('.retry-memory-settings').exists()).toBe(true)
	})
})
