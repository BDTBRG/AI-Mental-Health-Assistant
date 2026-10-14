<template>
	<div class="consultation-container">
		<div class="sidebar">
			<div class="ai-assistant-info">
				<div class="breathing-circle">
					<el-image
						:src="iconUrl1"
						style="width: 25px; height: 25px"
						alt="AI Assistant" />
				</div>
				<h3 class="assistant-name">宁渡AI助手</h3>
				<div class="online-status">
					<div class="status-dot"></div>
					在线服务中
				</div>
			</div>
			<div class="emotion-garden">
				<div class="garden-header">
					<div class="garden-title">情绪花园</div>
				</div>
				<div class="emotion-info">
					<div class="emotion-name">
						{{ currentEmotion.primaryEmotion }}
					</div>
					<div class="emotion-score">
						{{ currentEmotion.emotionScore ?? '—' }}
					</div>
				</div>
				<div class="warm-tips">
					<div class="emotion-status-text">
						<span class="status-label">今天感觉</span>
						<span class="status-emotion">{{
							currentEmotion.riskStatus === 'unknown' ||
							currentEmotion.isNegative === null
								? '暂不可判断'
								: currentEmotion.isNegative
									? '需要关注'
									: '很不错'
						}}</span>
					</div>
					<div class="emotion-intensity">
						<span class="intensity-dots">
							<span
								v-for="dot in 3"
								:key="dot"
								class="dot"
								:class="{
									active:
										getIntensityClass(
											currentEmotion.emotionScore,
										) >= dot,
								}"></span>
							<span class="intensity-text">
								{{ getRiskText(currentEmotion.riskLevel) }}
							</span>
						</span>
					</div>
					<div
						class="warm-suggestion"
						v-if="currentEmotion.suggestion">
						<div class="suggestion-icon">💝</div>
						<div class="suggestion-content">
							<div class="suggestion-title">给你的小建议</div>
							<div class="suggestion-text">
								{{ currentEmotion.suggestion }}
							</div>
						</div>
					</div>
					<div
						class="healing-actions"
						v-if="currentEmotion.improvementSuggestions.length">
						<div class="actions-title">治愈小行动</div>
						<div class="actions-list">
							<div
								v-for="action in currentEmotion.improvementSuggestions"
								:key="action"
								class="action-item">
								<div class="action-icon">✨</div>
								<div class="action-text">{{ action }}</div>
							</div>
						</div>
					</div>
					<div
						class="risk-notice"
						v-if="
							currentEmotion.analysisStatus === 'unavailable' ||
							(currentEmotion.isNegative === true &&
								currentEmotion.riskLevel != null &&
								currentEmotion.riskLevel > 1)
						">
						<div class="notice-icon">🤗</div>
						<div class="notice-content">
							<div class="notice-title">{{
								currentEmotion.analysisStatus === 'unavailable'
									? '分析暂不可用'
									: '温馨提示'
							}}</div>
							<div class="notice-text">
								{{ currentEmotion.riskDescription }}
							</div>
						</div>
					</div>
				</div>
			</div>
			<div class="session-history">
				<h4 class="section-title">会话列表</h4>
				<div class="session-list">
					<div
						v-for="session in sessionList"
						:key="session.id"
						@click="handleSwitchSession(session)"
						class="session-item">
						<div class="session-info">
							<div class="session-title">
								<span>{{ session.sessionTitle }}</span>
								<div class="session-meta">
									<span class="session-time">{{
										session.lastMessageTime
									}}</span>
								</div>
								<div class="session-preview">
									{{ session.lastMessageContent }}
								</div>
								<div class="session-stats">
									<span
										><el-icon><ChatRound /></el-icon>
										{{ session.messageCount || 0 }}</span
									>
									<span
										><el-icon><Clock /></el-icon>
										{{ session.durationMinutes || 0 }}
										分钟</span
									>
								</div>
							</div>
							<div class="session-actions">
								<el-button
									text
									type="danger"
									size="small"
									@click.stop="
										handleDeleteSession(
											session.id.toString(),
										)
									"
									><el-icon><DeleteFilled /></el-icon
								></el-button>
							</div>
						</div>
					</div>
				</div>
			</div>
		</div>
		<div class="chat-main">
			<div class="chat-header">
				<div class="header-left">
					<div class="chat-avatar">
						<el-image
							:src="iconUrl2"
							style="width: 30px; height: 30px"
							alt="Chat Avatar" />
					</div>
					<div class="chat-info">
						<h2>宁渡AI助手</h2>
					<p>随时倾听，温暖陪伴</p>
					</div>
				</div>
				<div class="review-consent">
					<el-checkbox
						v-model="reviewConsent"
						:disabled="isLoadingSession || sessionLoadError"
						@change="handleReviewConsentChange">
						允许授权人员查看本次对话以进行人工复核
					</el-checkbox>
					<el-tooltip content="默认关闭。授权后，管理端复核队列可查看本次对话；你可以随时撤回。" placement="bottom">
						<el-icon class="consent-help"><InfoFilled /></el-icon>
					</el-tooltip>
					<el-button text type="primary" size="small" @click="openMemoryDialog">管理个性化记忆</el-button>
					<el-button
						v-if="currentSession.status !== 'TEMP' && reviewConsent"
						:disabled="isLoadingSession || sessionLoadError || isAiTying || ['PENDING', 'IN_PROGRESS'].includes(reviewStatus)"
						size="small"
						type="warning"
						@click="handleRequestReview">
						{{ reviewStatus === 'PENDING' ? '等待人工复核' : reviewStatus === 'IN_PROGRESS' ? '人工复核处理中' : '申请人工复核' }}
					</el-button>
				</div>
				<el-button
					circle
					:disabled="isStartingSession || isRecoveringStart || sessionStartRecoveryError"
					@click="createNewSession"
					title="新建会话">
					<el-icon><Plus /></el-icon>
				</el-button>
			</div>
			<div ref="chatMessagesContainer" class="chat-messages">
				<div v-if="isLoadingSession" class="session-load-state" role="status">
					正在加载会话…
				</div>
				<div v-else-if="sessionLoadError" class="session-load-state session-load-error" role="alert">
					<p>会话加载失败，请重试</p>
					<el-button
						class="retry-session-load"
						text
						type="primary"
						@click="retrySessionLoad">
						重新加载
					</el-button>
				</div>
				<div v-else-if="isRecoveringStart" class="session-load-state" role="status">
					正在恢复上次发送的消息…
				</div>
				<div v-else-if="sessionStartRecoveryError" class="session-load-state session-load-error" role="alert">
					<p>暂时无法确认上次消息是否已提交</p>
					<el-button
						class="retry-start-recovery"
						text
						type="primary"
						@click="retryPendingSessionStart">
						重试恢复
					</el-button>
					<el-button
						class="resume-start-entry"
						text
						type="primary"
						@click="resumePendingStartEntry">
						重新输入并继续
					</el-button>
				</div>
				<template v-else>
				<el-button v-if="hasOlderMessages" text type="primary" class="load-older" @click="loadOlderMessages">
					加载更早的对话（{{ messageList.length - renderedMessages.length }} 条）
				</el-button>
				<div v-if="!messageList.length" class="message-item ai-message">
					<div class="message-avatar">
						<el-image
							:src="iconUrl1"
							style="width: 18px; height: 18px"
							alt="AI" />
					</div>
					<div class="message-content">
						<div class="message-bubble">
							<p>
								您好！我是小暖，您的AI心理健康助手。很高兴陪伴您，为您提供温暖的心理支持。请告诉我，今天您感觉怎么样？有什么想要分享的吗？
							</p>
						</div>
						<div class="message-time">刚刚</div>
					</div>
				</div>
				<div
					v-for="message in renderedMessages"
					:key="message.id"
					class="message-item"
					:class="
						message.senderType === 1 ? 'user-message' : 'ai-message'
					">
					<div class="message-avatar">
						<el-image
							:src="
								message.senderType === 1 ? iconUrl3 : iconUrl1
							"
							style="width: 18px; height: 18px"
							alt="Avatar" />
					</div>
					<div class="message-content">
						<div class="message-bubble">
							<div
								v-if="
									message.senderType === 2 &&
									isAiTying &&
									!message.content
								"
								class="typing-indicator">
								<div class="typing-dot"></div>
								<div class="typing-dot"></div>
								<div class="typing-dot"></div>
							</div>
							<div
								v-else-if="message.isError"
								class="error-message">
								<p>{{ message.content }}</p>
								<el-button
									v-if="message.canReconnect"
									class="retry-stream"
									text
									type="primary"
									@click.stop="retryAiResponse(message)">
									重新连接本轮回复
								</el-button>
							</div>
							<MarkdownRenderer
								v-else-if="
									message.senderType === 2 && !message.isError
								"
								:content="message.content"
								:is-ai-message="true" />
							<p
								v-else-if="message.content"
								class="plain-message">{{ message.content }}</p>
						</div>
						<div class="message-time">
							{{
								message.senderType === 2 && isAiTying ?
									'AI正在输入...'
								:	message.createdAt
							}}
						</div>
					</div>
				</div>
				</template>
			</div>
			<div class="chat-input">
				<div class="input-container">
					<el-input
						v-model="userMessage"
						placeholder="请输入您想要分享的内容..."
						type="textarea"
						:rows="3"
						@keyup.enter="handleEnter"
						class="message-input"
						clearable />
					<div class="input-footer">
						<span>按 Enter 键发送，shift + enter换行</span>
						<span>{{ userMessage.length }}/500</span>
					</div>
				</div>
				<el-button
					type="primary"
					class="send-btn"
					:disabled="isStartingSession || isLoadingSession || sessionLoadError || isRecoveringStart || sessionStartRecoveryError || isAiTying"
					@click="sendMessage">
					<el-icon><Promotion /></el-icon>
				</el-button>
			</div>
		</div>
	</div>
	<el-dialog v-model="showMemoryDialog" title="个性化记忆" width="600px">
		<el-alert type="info" :closable="false" show-icon>
			<template #title>只有你主动保存的信息会作为个人化背景。关闭授权后，后续对话不再读取记忆或近期自评；删除会立即清除已保存内容。</template>
		</el-alert>
		<div v-if="memoryLoadState === 'loading'" class="memory-load-state" role="status">正在读取授权和记忆状态…</div>
		<div v-else-if="memoryLoadState === 'error'" class="memory-load-state memory-load-error" role="alert">
			<p>无法确认个性化记忆授权与列表状态。为避免误报成未授权或空列表，请重新读取后再管理。</p>
			<el-button class="retry-memory-settings" :loading="memoryLoading" @click="loadMemorySettings">重试读取</el-button>
		</div>
		<template v-else-if="memoryLoadState === 'ready'">
			<div class="memory-consent-row">
				<span>允许使用个性化记忆</span>
				<el-switch v-model="memoryEnabled" :disabled="memoryLoading || memoryConsentSaving || isAiTying" @change="handleMemoryConsentChange" />
			</div>
			<div v-if="memoryEnabled" class="memory-editor">
				<el-input v-model="memoryDraft" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="例如：我更喜欢简短直接的建议；工作日晚上通常有时间练习" />
				<div class="memory-editor-footer">
					<el-date-picker v-model="memoryExpiresAt" type="date" value-format="YYYY-MM-DD" clearable placeholder="有效期（可选）" />
					<div>
						<el-button v-if="editingMemoryId !== null" @click="resetMemoryEditor">取消编辑</el-button>
						<el-button type="primary" :loading="memorySaving" @click="saveAgentMemory">{{ editingMemoryId === null ? '保存记忆' : '保存修改' }}</el-button>
					</div>
				</div>
			</div>
			<el-empty v-if="!agentMemories.length" :description="memoryEnabled ? '还没有主动保存的记忆' : '没有已保存的记忆'" :image-size="70" />
			<div v-if="agentMemories.length" class="memory-list">
				<div v-for="memory in agentMemories" :key="memory.id" class="memory-item">
					<div class="memory-content">{{ memory.content }}</div>
					<div class="memory-meta">来源：用户主动添加 · {{ memory.expiresAt ? `有效至 ${memory.expiresAt}` : '长期有效' }}</div>
					<div class="memory-actions">
						<el-button text type="primary" @click="editAgentMemory(memory)">纠正</el-button>
						<el-button text type="danger" @click="removeAgentMemory(memory.id)">删除</el-button>
					</div>
				</div>
			</div>
		</template>
		<template #footer>
			<el-button v-if="memoryLoadState === 'ready' && agentMemories.length" type="danger" plain @click="clearAllAgentMemories">删除全部并撤回授权</el-button>
			<el-button @click="showMemoryDialog = false">关闭</el-button>
		</template>
	</el-dialog>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
	startSession,
	updateReviewConsent,
	requestHumanReview,
	getPersonalizationConsent,
	setPersonalizationConsent,
	getAgentMemories,
	createAgentMemory,
	updateAgentMemory,
	deleteAgentMemory,
	clearAgentMemories,
	getHistorySessionList,
	recoverSessionStart,
	deleteSession,
	getHistorySessionMessages,
	getEmotionAnalysis,
} from '@/api/user'
import type {
	HistorySession,
	ListData,
	Message,
	StartSession,
	EmotionAnalysis,
	AgentMemoryItem,
} from '@/types/user'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import { fetchEventSource } from '@microsoft/fetch-event-source'

const iconUrl1 = new URL('@/assets/robot-fill.png', import.meta.url).href
const iconUrl2 = new URL('@/assets/like.png', import.meta.url).href
const iconUrl3 = new URL('@/assets/users.png', import.meta.url).href

const isAiTying = ref<boolean>(false)
const isStartingSession = ref(false)
const isRecoveringStart = ref(false)
const sessionStartRecoveryError = ref(false)
const activeStreamController = ref<AbortController | null>(null)
let activeStreamSequence = 0
let componentActive = false
let pendingStartMessage: {
	requestId: string
	message: string
	sessionTitle: string
	reviewConsent: boolean
} | null = null
let resumePendingTurnOnLoad = false

const currentSession = ref<Record<string, string>>({
	sessionId: `temp_${Date.now()}`,
	status: 'TEMP',
	sessionTitle: `宁渡AI助手-${new Date().toLocaleString()}`,
})

const userMessage = ref<string>('')

const reviewConsent = ref(false)
const reviewStatus = ref<'NONE' | 'PENDING' | 'IN_PROGRESS' | 'RESOLVED' | 'CANCELLED'>('NONE')
const showMemoryDialog = ref(false)
const memoryLoading = ref(false)
const memorySaving = ref(false)
const memoryConsentSaving = ref(false)
const memoryLoadState = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const memoryEnabled = ref(false)
const agentMemories = ref<AgentMemoryItem[]>([])
const memoryDraft = ref('')
const memoryExpiresAt = ref<string | null>(null)
const editingMemoryId = ref<number | null>(null)
let memoryLoadSequence = 0

const loadMemorySettings = async () => {
	const sequence = ++memoryLoadSequence
	memoryLoading.value = true
	memoryLoadState.value = 'loading'
	try {
		const [consent, memories] = await Promise.all([
			getPersonalizationConsent(),
			getAgentMemories(),
		])
		if (sequence !== memoryLoadSequence) return
		memoryEnabled.value = consent.enabled
		agentMemories.value = memories
		memoryLoadState.value = 'ready'
	} catch {
		if (sequence !== memoryLoadSequence) return
		memoryLoadState.value = 'error'
	} finally {
		if (sequence === memoryLoadSequence) memoryLoading.value = false
	}
}

const openMemoryDialog = async () => {
	showMemoryDialog.value = true
	await loadMemorySettings()
}

const handleMemoryConsentChange = async (allowed: string | number | boolean) => {
	if (memoryLoadState.value !== 'ready' || memoryConsentSaving.value || isAiTying.value) return
	const nextValue = Boolean(allowed)
	memoryConsentSaving.value = true
	try {
		await setPersonalizationConsent(nextValue)
		ElMessage.success(nextValue ? '已开启个性化记忆' : '已关闭个性化记忆读取')
	} catch {
		memoryLoadState.value = 'error'
		ElMessage.error('授权状态更新失败，当前状态无法确认，请重新读取')
	} finally {
		memoryConsentSaving.value = false
	}
}

const resetMemoryEditor = () => {
	editingMemoryId.value = null
	memoryDraft.value = ''
	memoryExpiresAt.value = null
}

const saveAgentMemory = async () => {
	const content = memoryDraft.value.trim()
	if (!content) {
		ElMessage.warning('请先填写需要记住的内容')
		return
	}
	memorySaving.value = true
	try {
		const payload = { content, expiresAt: memoryExpiresAt.value }
		if (editingMemoryId.value === null) {
			const created = await createAgentMemory(payload)
			agentMemories.value.unshift(created)
		} else {
			await updateAgentMemory(editingMemoryId.value, payload)
			agentMemories.value = await getAgentMemories()
		}
		resetMemoryEditor()
		ElMessage.success('个性化记忆已保存')
	} catch {
		ElMessage.error('保存失败，请刷新后重试')
	} finally {
		memorySaving.value = false
	}
}

const editAgentMemory = (memory: AgentMemoryItem) => {
	editingMemoryId.value = memory.id
	memoryDraft.value = memory.content
	memoryExpiresAt.value = memory.expiresAt
}

const removeAgentMemory = async (id: number) => {
	try {
		await deleteAgentMemory(id)
		agentMemories.value = agentMemories.value.filter(memory => memory.id !== id)
		if (editingMemoryId.value === id) resetMemoryEditor()
	} catch {
		ElMessage.error('删除失败，请刷新后重试')
	}
}

const clearAllAgentMemories = async () => {
	try {
		await ElMessageBox.confirm('删除后，已保存的个人记忆会被清除，个性化记忆授权也会关闭。', '确认删除全部记忆', { type: 'warning' })
		await clearAgentMemories()
		agentMemories.value = []
		memoryEnabled.value = false
		resetMemoryEditor()
		ElMessage.success('已删除全部记忆并撤回授权')
	} catch (error) {
		if (error !== 'cancel' && error !== 'close') ElMessage.error('操作失败')
	}
}

const messageList = ref<Message[]>([])
const isLoadingSession = ref(false)
const sessionLoadError = ref(false)
const sessionLoadTarget = ref<HistorySession | null>(null)
const chatMessagesContainer = ref<HTMLElement | null>(null)
const visibleMessageCount = ref(40)
const renderedMessages = computed(() => messageList.value.slice(-visibleMessageCount.value))
const hasOlderMessages = computed(() => messageList.value.length > visibleMessageCount.value)
const createLocalMessageId = () => Number(`${Date.now()}${Math.floor(Math.random() * 1000)}`)

const scrollChatToBottom = async () => {
	await nextTick()
	const container = chatMessagesContainer.value
	if (container) container.scrollTop = container.scrollHeight
}

const loadOlderMessages = async () => {
	const container = chatMessagesContainer.value
	const previousHeight = container?.scrollHeight ?? 0
	const previousTop = container?.scrollTop ?? 0
	visibleMessageCount.value += 40
	await nextTick()
	if (container) container.scrollTop = previousTop + container.scrollHeight - previousHeight
}

const sessionList = ref<HistorySession[]>([])

const createEmptyEmotion = (): EmotionAnalysis => ({
	primaryEmotion: '等待分析',
	emotionScore: null,
	isNegative: null,
	riskLevel: null,
	riskStatus: 'unknown',
	analysisStatus: 'idle',
	suggestion: '开始对话后，AI将实时分析你的情绪状态',
	improvementSuggestions: [],
	riskDescription:
		'尚未开始分析',
})

const currentEmotion = ref<EmotionAnalysis>(createEmptyEmotion())
let emotionRequestSequence = 0
let sessionLoadSequence = 0

const normalizeSessionId = (sessionId: string) =>
	sessionId.startsWith('session_') ? sessionId.slice('session_'.length) : sessionId

const resetCurrentEmotion = () => {
	emotionRequestSequence++
	currentEmotion.value = createEmptyEmotion()
}

const invalidateActiveStream = () => {
	activeStreamSequence++
	activeStreamController.value?.abort()
	activeStreamController.value = null
	isAiTying.value = false
}

const createNewSession = async () => {
	sessionLoadSequence++
	invalidateActiveStream()
	isLoadingSession.value = false
	sessionLoadError.value = false
	sessionLoadTarget.value = null
	isRecoveringStart.value = false
	sessionStartRecoveryError.value = false
	currentSession.value = {
		sessionId: `temp_${Date.now()}`,
		status: 'TEMP',
		sessionTitle: `宁渡AI助手-${new Date().toLocaleString()}`,
	}
	messageList.value = []
	visibleMessageCount.value = 40
	reviewConsent.value = false
	reviewStatus.value = 'NONE'
	resetCurrentEmotion()
}

const handleReviewConsentChange = async (allowed: string | number | boolean) => {
	const nextValue = Boolean(allowed)
	if (currentSession.value.status === 'TEMP') return

	try {
		await updateReviewConsent(currentSession.value.sessionId, nextValue)
		ElMessage.success(nextValue ? '已授权本次会话人工复核' : '已撤回本次会话复核授权')
		if (!nextValue && ['PENDING', 'IN_PROGRESS'].includes(reviewStatus.value)) {
			reviewStatus.value = 'CANCELLED'
		}
		getHistorySessionPage()
	} catch {
		reviewConsent.value = !nextValue
	}
}

const handleRequestReview = async () => {
	if (!reviewConsent.value || currentSession.value.status === 'TEMP') return
	try {
		await requestHumanReview(currentSession.value.sessionId)
		reviewStatus.value = 'PENDING'
		ElMessage.success('已提交人工复核申请')
		await getHistorySessionPage()
	} catch {
		ElMessage.error('提交复核申请失败，请刷新后重试')
	}
}

const sendMessage = () => {
	if (!userMessage.value.trim()) {
		ElMessage.warning('请输入消息内容')
		return
	}
	if (isAiTying.value) {
		ElMessage.warning('AI正在输入，请稍后...')
		return
	}
	if (isStartingSession.value) {
		ElMessage.warning('正在创建会话，请稍候...')
		return
	}
	if (isLoadingSession.value || sessionLoadError.value) {
		ElMessage.warning('请先完成会话加载，再发送消息')
		return
	}
	if (userMessage.value.length > 500) {
		ElMessage.warning('消息内容不能超过500字')
		return
	}

	const message = userMessage.value.trim()

	if (currentSession.value.status === 'TEMP') {
		void startNewSession(message)
	} else {
		messageList.value.push({
			id: Date.now(),
			sessionId: Number(currentSession.value.sessionId),
			senderType: 1,
			content: message,
			createdAt: new Date().toISOString(),
		})
		scrollChatToBottom()
		startAiResponse(
			currentSession.value.sessionId,
			message,
			createClientRequestId(),
			undefined,
			true,
		)
	}
}

const startNewSession = async (message: string) => {
	if (isStartingSession.value) return
	isStartingSession.value = true
	const targetSessionId = currentSession.value.sessionId
	const sessionTitle = currentSession.value.sessionTitle
	const consentSnapshot = reviewConsent.value
	const unresolvedRequestId = pendingStartMessage?.requestId ?? readPendingSessionStart()
	pendingStartMessage = {
		requestId: unresolvedRequestId || createClientRequestId(),
		message,
		sessionTitle,
		reviewConsent: consentSnapshot,
	}
	const clientRequestId = pendingStartMessage.requestId
	savePendingSessionStart(clientRequestId)
	const sessionParams = {
		initialMessage: message,
		sessionTitle,
		reviewConsent: consentSnapshot,
		clientRequestId,
	}
	try {
		const data = await startSession(sessionParams)
		const sessionId = String(data.sessionId)
		savePendingTurn(sessionId, clientRequestId)
		clearPendingSessionStart(clientRequestId)
		pendingStartMessage = null
		if (!componentActive) return
		if (
			currentSession.value.sessionId !== targetSessionId ||
			currentSession.value.status !== 'TEMP'
		) {
			void getHistorySessionPage()
			return
		}

		currentSession.value.sessionId = sessionId
		currentSession.value.status = data.status
		if (userMessage.value.trim() === message) userMessage.value = ''
		void getHistorySessionPage()

		messageList.value.push({
			id: createLocalMessageId(),
			sessionId: Number(sessionId),
			senderType: 1,
			content: message,
			createdAt: new Date().toISOString(),
		})
		scrollChatToBottom()
		void startAiResponse(
			sessionId,
			message,
			clientRequestId,
		)
	} catch {
		if (componentActive && currentSession.value.sessionId === targetSessionId) {
			try {
				const recovered = await recoverSessionStart(clientRequestId)
				if (recovered) {
					if (userMessage.value.trim() === message) userMessage.value = ''
					isStartingSession.value = false
					await openRecoveredSession(clientRequestId, recovered)
					return
				}
			} catch {
				// A failed lookup cannot prove that the start request did not commit.
			}
			ElMessage.warning('会话创建结果暂未确认，输入已保留；重试会沿用同一请求标识')
		}
	} finally {
		isStartingSession.value = false
	}
}

const createClientRequestId = () =>
	window.crypto?.randomUUID?.() ??
	`req-${Date.now()}-${Math.random().toString(36).slice(2)}`

const pendingSessionStartKey = 'amha:pending-session-start'

const readPendingSessionStart = () => {
	try {
		return sessionStorage.getItem(pendingSessionStartKey)
	} catch {
		return null
	}
}

const savePendingSessionStart = (requestId: string) => {
	try {
		sessionStorage.setItem(pendingSessionStartKey, requestId)
	} catch {
		// The in-memory retry path still works when browser storage is unavailable.
	}
}

const clearPendingSessionStart = (requestId?: string) => {
	try {
		if (!requestId || readPendingSessionStart() === requestId) {
			sessionStorage.removeItem(pendingSessionStartKey)
		}
	} catch {
		// Storage can be unavailable in private browsing contexts.
	}
}

const retryPendingSessionStart = () => {
	const requestId = readPendingSessionStart()
	if (requestId) void recoverPendingSessionStart(requestId)
}

const resumePendingStartEntry = () => {
	if (!readPendingSessionStart()) return
	pendingStartMessage = null
	sessionStartRecoveryError.value = false
	isRecoveringStart.value = false
	if (currentSession.value.status !== 'TEMP') createNewSession()
}

const openRecoveredSession = async (requestId: string, recovered: StartSession) => {
	const sessionId = String(recovered.sessionId)
	savePendingTurn(sessionId, requestId)
	clearPendingSessionStart(requestId)
	pendingStartMessage = null
	isRecoveringStart.value = false
	sessionStartRecoveryError.value = false
	const startedAt = recovered.startTime ? new Date(recovered.startTime).toISOString() : new Date().toISOString()
	const session: HistorySession = {
		id: Number(sessionId),
		userId: 0,
		userNickname: '',
		sessionTitle: recovered.sessionTitle || '宁渡AI助手',
		startedAt,
		durationMinutes: 0,
		messageCount: recovered.messageCount,
		lastMessageContent: '',
		lastMessageTime: startedAt,
		reviewConsent: Boolean(recovered.reviewConsent),
		reviewStatus: 'NONE',
	}
	await handleSwitchSession(session)
}

const recoverPendingSessionStart = async (requestId: string) => {
	isRecoveringStart.value = true
	sessionStartRecoveryError.value = false
	try {
		const recovered = await recoverSessionStart(requestId)
		if (!componentActive) return
		if (!recovered) {
			isRecoveringStart.value = false
			sessionStartRecoveryError.value = true
			return
		}
		await openRecoveredSession(requestId, recovered)
	} catch {
		if (componentActive) {
			isRecoveringStart.value = false
			sessionStartRecoveryError.value = true
		}
	}
}

const pendingTurnKey = (sessionId: string) =>
	`amha:pending-chat-turn:${sessionId}`

const readPendingTurn = (sessionId: string) => {
	try {
		return sessionStorage.getItem(pendingTurnKey(sessionId))
	} catch {
		return null
	}
}

const savePendingTurn = (sessionId: string, requestId: string) => {
	try {
		sessionStorage.setItem(pendingTurnKey(sessionId), requestId)
	} catch {
		// The active stream remains usable even when browser storage is unavailable.
	}
}

const clearPendingTurn = (sessionId: string) => {
	try {
		sessionStorage.removeItem(pendingTurnKey(sessionId))
	} catch {
		// Storage can be unavailable in private browsing contexts.
	}
}

const startAiResponse = async (
	sessionId: string,
	requestContent: string,
	clientRequestId: string,
	existingAiMessage?: Message,
	clearDraftOnAccept = false,
) => {
	if (isAiTying.value) {
		ElMessage.warning('AI正在输入，请稍后...')
		return
	}
	if (
		normalizeSessionId(currentSession.value.sessionId) !==
		normalizeSessionId(sessionId)
	) return

	const streamSequence = ++activeStreamSequence
	const isCurrentStream = () =>
		streamSequence === activeStreamSequence &&
		normalizeSessionId(currentSession.value.sessionId) ===
			normalizeSessionId(sessionId)
	const aiMessage: Message = existingAiMessage ?? {
		id: createLocalMessageId(),
		sessionId: Number(sessionId),
		senderType: 2,
		content: '',
		createdAt: new Date().toISOString(),
		isError: false,
	}
	aiMessage.content = ''
	aiMessage.isError = false
	aiMessage.canReconnect = false
	aiMessage.clientRequestId = clientRequestId
	aiMessage.requestContent = requestContent
	if (!existingAiMessage) messageList.value.push(aiMessage)
	isAiTying.value = true
	savePendingTurn(sessionId, clientRequestId)
	scrollChatToBottom()

	const ctrl = new AbortController()
	activeStreamController.value = ctrl
	let streamFinished = false
	let reconnectAttempts = 0
	let reconnectNoticeShown = false
	const maxReconnectAttempts = 3
	try {
		await fetchEventSource('/api/psychological-chat/stream', {
			method: 'POST',
			headers: {
				'Content-Type': 'application/json',
				Token: localStorage.getItem('token') as string,
				Accept: 'text/event-stream',
			},
			body: JSON.stringify({
				sessionId,
				clientRequestId,
				userMessage: requestContent,
			}),
			signal: ctrl.signal,
			async onopen(response) {
				if (!isCurrentStream()) return
				if (
					!response.headers.get('Content-Type')?.includes('text/event-stream')
				) {
					throw new Error('服务器暂时无法建立流式连接')
				}
				if (clearDraftOnAccept && userMessage.value.trim() === requestContent) {
					userMessage.value = ''
				}
			},
			onmessage: event => {
				if (!isCurrentStream()) return
				const raw = event.data.trim()
				if (!raw) return
				const eventName = event.event
				if (eventName === 'done') {
					streamFinished = true
					isAiTying.value = false
					aiMessage.canReconnect = false
					clearPendingTurn(sessionId)
					getCurrentEmotion(sessionId)
					void getHistorySessionPage()
					void getHistorySessionMessages(sessionId)
						.then(messages => {
							if (!isCurrentStream()) return
							messageList.value = messages
							visibleMessageCount.value = 40
							void scrollChatToBottom()
						})
						.catch(() => {})
					return
				}
				if (eventName === 'reset') {
					aiMessage.content = ''
					aiMessage.isError = false
					aiMessage.canReconnect = false
					return
				}
				const payload = JSON.parse(raw)
				if (
					eventName !== 'error' &&
					String(payload.code) === '200' &&
					payload.data?.content
				) {
					aiMessage.content += payload.data.content
					scrollChatToBottom()
				} else {
					streamFinished = true
					aiMessage.content = payload.message || 'AI回复失败，请稍后再试'
					aiMessage.isError = true
					aiMessage.canReconnect = false
					isAiTying.value = false
					clearPendingTurn(sessionId)
					ElMessage.error(payload.message || 'AI回复失败，请稍后再试')
				}
			},
			onerror: error => {
				if (ctrl.signal.aborted || !isCurrentStream()) throw error
				reconnectAttempts += 1
				if (reconnectAttempts > maxReconnectAttempts) throw error
				if (!reconnectNoticeShown) {
					ElMessage.warning('连接中断，正在恢复本轮回复…')
					reconnectNoticeShown = true
				}
			},
			onclose: () => {
				if (!ctrl.signal.aborted && isCurrentStream() && !streamFinished) {
					throw new Error('回复连接关闭，当前回复尚未完成')
				}
			},
		})
		if (!streamFinished && !ctrl.signal.aborted && isCurrentStream()) {
			throw new Error('回复连接关闭，当前回复尚未完成')
		}
	} catch (error) {
		if (
			ctrl.signal.aborted ||
			(error instanceof Error && error.name === 'AbortError') ||
			!isCurrentStream()
		) return
		const isFatal = Boolean((error as Error & { fatal?: boolean })?.fatal)
		aiMessage.content = isFatal ?
			(error instanceof Error ? error.message : '服务器无法建立流式连接')
		:	'连接中断，回复未完成'
		aiMessage.isError = true
		aiMessage.canReconnect = !isFatal
		if (isFatal) clearPendingTurn(sessionId)
		ElMessage.error(aiMessage.content)
	} finally {
		if (streamSequence === activeStreamSequence) {
			isAiTying.value = false
			if (activeStreamController.value === ctrl) activeStreamController.value = null
		}
	}
}

const retryAiResponse = (message: Message) => {
	if (
		!message.canReconnect ||
		!message.clientRequestId ||
		message.requestContent === undefined ||
		String(message.sessionId) !== currentSession.value.sessionId
	) return
	void startAiResponse(
		String(message.sessionId),
		message.requestContent,
		message.clientRequestId,
		message,
	)
}

const getHistorySessionPage = async () => {
	let data: ListData<HistorySession>
	try {
		data = await getHistorySessionList({
			pageNum: 1,
			pageSize: 10,
		})
	} catch {
		if (componentActive) ElMessage.error('会话列表加载失败，请稍后重试')
		return
	}
	if (!componentActive) return
	// console.log('会话列表数据：', data)
	sessionList.value = data.records
	if (resumePendingTurnOnLoad) {
		resumePendingTurnOnLoad = false
		const pendingSession = data.records.find(item => readPendingTurn(item.id.toString()))
		if (pendingSession && currentSession.value.status === 'TEMP') {
			void handleSwitchSession(pendingSession)
		}
	}
}

const handleEnter = (event: KeyboardEvent) => {
	if (event.key === 'Enter' && !event.shiftKey) {
		event.preventDefault()
		sendMessage()
	}
}

const retrySessionLoad = () => {
	if (sessionLoadTarget.value) void handleSwitchSession(sessionLoadTarget.value)
}

const handleSwitchSession = async (session: HistorySession) => {
	if (isStartingSession.value || isRecoveringStart.value || sessionStartRecoveryError.value) {
		ElMessage.warning('请先完成当前会话恢复，再切换会话')
		return
	}
	const loadSequence = ++sessionLoadSequence
	invalidateActiveStream()
	isRecoveringStart.value = false
	sessionStartRecoveryError.value = false
	sessionLoadTarget.value = session
	isLoadingSession.value = true
	sessionLoadError.value = false
	messageList.value = []
	visibleMessageCount.value = 40
	currentSession.value.sessionId = session.id.toString()
	currentSession.value.status = 'ACTIVE'
	currentSession.value.sessionTitle = session.sessionTitle
	reviewConsent.value = Boolean(session.reviewConsent)
	reviewStatus.value = session.reviewStatus ?? 'NONE'
	resetCurrentEmotion()
	try {
		const data = await getHistorySessionMessages(session.id.toString())
		if (!componentActive || loadSequence !== sessionLoadSequence) return
		messageList.value = data
		visibleMessageCount.value = 40
		getCurrentEmotion(session.id.toString())
		const pendingRequestId = readPendingTurn(session.id.toString())
		const lastMessage = data[data.length - 1]
		if (pendingRequestId && lastMessage?.senderType === 1) {
			startAiResponse(
				session.id.toString(),
				lastMessage.content,
				pendingRequestId,
			)
		}
		else {
			if (pendingRequestId) clearPendingTurn(session.id.toString())
			scrollChatToBottom()
		}
	} catch {
		if (componentActive && loadSequence === sessionLoadSequence) {
			sessionLoadError.value = true
		}
	} finally {
		if (componentActive && loadSequence === sessionLoadSequence) {
			isLoadingSession.value = false
		}
	}
}

const handleDeleteSession = async (sessionId: string) => {
	if (isAiTying.value) {
		ElMessage.warning('AI正在回复中，请稍后再删除')
		return
	}
	if (
		currentSession.value.sessionId === 'session_' + sessionId ||
		currentSession.value.sessionId === sessionId
	) {
		createNewSession()
	}
	await deleteSession(sessionId)
	await getHistorySessionPage()
}

const getIntensityClass = (score: number | null): number => {
	if (score === null) return 0
	if (score >= 61) return 3
	if (score >= 31) return 2
	return 1
}

const getRiskText = (level: number | null): string => {
	if (level === null) return '未知'
	switch (level) {
		case 0:
			return '正常'
		case 1:
			return '关注'
		case 2:
			return '预警'
		case 3:
			return '危机'
		default:
			return '未知'
	}
}

const getCurrentEmotion = async (sessionId: string) => {
	const requestedSessionId = normalizeSessionId(sessionId)
	if (normalizeSessionId(currentSession.value.sessionId) !== requestedSessionId) {
		return
	}
	if (sessionId.startsWith('temp_')) {
		resetCurrentEmotion()
		return
	}
	const requestSequence = ++emotionRequestSequence
	currentEmotion.value = createEmptyEmotion()
	try {
		const data = await getEmotionAnalysis(`session_${requestedSessionId}`)
		if (
			requestSequence !== emotionRequestSequence ||
			normalizeSessionId(currentSession.value.sessionId) !== requestedSessionId
		) return
		currentEmotion.value = data?.primaryEmotion ? data : createEmptyEmotion()
	} catch {
		// 静默失败，维持当前会话的默认状态
	}
}

onMounted(() => {
	componentActive = true
	const pendingStartRequestId = readPendingSessionStart()
	if (pendingStartRequestId) {
		void recoverPendingSessionStart(pendingStartRequestId)
	} else {
		resumePendingTurnOnLoad = true
		createNewSession()
	}
	void getHistorySessionPage()
})

onBeforeUnmount(() => {
	componentActive = false
	sessionLoadSequence++
	invalidateActiveStream()
})
</script>

<style scoped lang="scss">
.consultation-container {
	margin: 0 auto;
	width: 1200px;
	display: flex;
	gap: 20px;
	padding: 20px;
	.sidebar {
		width: 320px;
		.ai-assistant-info {
			margin-bottom: 20px;
			background: linear-gradient(
				135deg,
				rgba(255, 255, 255, 0.9) 0%,
				rgba(255, 252, 248, 0.95) 100%
			);
			border-radius: 16px;
			padding: 16px;
			box-shadow:
				0 8px 32px rgba(251, 146, 60, 0.06),
				0 2px 8px rgba(0, 0, 0, 0.04);
			border: 1px solid rgba(251, 146, 60, 0.08);
			backdrop-filter: blur(10px);
			transition: all 0.3s ease;
			.breathing-circle {
				width: 60px;
				height: 60px;
				background: linear-gradient(135deg, #fb923c 0%, #f59e0b 100%);
				border-radius: 50%;
				display: flex;
				align-items: center;
				justify-content: center;
				margin: 0 auto 12px;
				animation: breathing 4s ease-in-out infinite;
				box-shadow: 0 6px 24px rgba(251, 146, 60, 0.25);
				position: relative;
			}
			.assistant-name {
				font-size: 16px;
				font-weight: 700;
				background: linear-gradient(135deg, #fb923c, #f59e0b);
				-webkit-background-clip: text;
				-webkit-text-fill-color: transparent;
				text-align: center;
				background-clip: text;
				margin: 0 0 12px;
			}
			.online-status {
				display: flex;
				align-items: center;
				justify-content: center;
				color: #059669;
				font-size: 12px;
				font-weight: 600;
				.status-dot {
					width: 8px;
					height: 8px;
					background: #059669;
					border-radius: 50%;
					margin-right: 8px;
					animation: pulse 2s infinite;
					box-shadow: 0 0 8px rgba(5, 150, 105, 0.4);
				}
			}
		}
		.session-history {
			background: white;
			border-radius: 16px;
			padding: 16px;
			box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
			margin-bottom: 20px;
			min-height: 250px;
			display: flex;
			flex-direction: column;
			.section-title {
				font-size: 16px;
				font-weight: 600;
				color: #333;
				margin: 0 0 16px;
				display: flex;
				align-items: center;
				justify-content: space-between;
			}
			.session-list {
				overflow-y: auto;
				max-height: 200px;
				scrollbar-width: thin;
				scrollbar-color: rgba(64, 150, 255, 0.3) transparent;
				.session-item {
					position: relative;
					display: flex;
					align-items: flex-start;
					gap: 12px;
					padding: 12px;
					margin-bottom: 8px;
					border-radius: 12px;
					cursor: pointer;
					transition: all 0.3s ease;
					border: 2px solid transparent;
					&:hover {
						background: #f8f9ff;
						border-color: #e6f0ff;
					}
					&.active {
						background: #e6f0ff;
						border-color: #4096ff;
					}
					.session-info {
						flex: 1;
						.session-title {
							font-weight: 500;
							font-size: 14px;
							color: #333;
							margin-bottom: 4px;
							white-space: nowrap;
							overflow: hidden;
							text-overflow: ellipsis;
							.session-meta {
								display: flex;
								align-items: center;
								gap: 8px;
								margin-bottom: 6px;
								.session-time {
									font-size: 12px;
									color: #999;
								}
							}
							.session-preview {
								width: 200px;
								font-size: 12px;
								color: #666;
								margin-bottom: 6px;
								white-space: nowrap;
								overflow: hidden;
								text-overflow: ellipsis;
							}
							.session-stats {
								display: flex;
								align-items: center;
								gap: 12px;
								span {
									font-size: 12px;
									color: #999;
									display: flex;
									align-items: center;
									gap: 4px;
								}
							}
						}
						.session-actions {
							position: absolute;
							top: 10px;
							right: 12px;
						}
					}
				}
				.no-sessions-text {
					text-align: center;
					font-size: 14px;
					color: #999;
				}
			}
		}
		.emotion-garden {
			background: linear-gradient(
				135deg,
				#fef9e7 0%,
				#fcf4e6 50%,
				#f6f0e8 100%
			);
			border-radius: 20px;
			padding: 16px;
			margin-bottom: 20px;
			box-shadow: 0 8px 32px rgba(252, 244, 230, 0.8);
			border: 1px solid rgba(255, 255, 255, 0.2);
			position: relative;
			overflow: hidden;
			min-height: 300px;

			.garden-header {
				display: flex;
				align-items: center;
				justify-content: space-between;
				margin-bottom: 20px;
				position: relative;
				z-index: 2;
				.garden-title {
					display: flex;
					align-items: center;
					gap: 8px;
					font-size: 16px;
					font-weight: 600;
					color: #8b4513;
				}
			}
			.emotion-info {
				margin: 0 auto;
				width: 80px;
				height: 80px;
				border-radius: 50%;
				display: flex;
				flex-direction: column;
				align-items: center;
				justify-content: center;
				z-index: 10;
				box-shadow: 0 4px 16px rgba(0, 0, 0, 0.1);
				border: 2px solid rgba(255, 255, 255, 0.8);
				background: linear-gradient(
					135deg,
					#ff9a9e 0%,
					#fecfef 50%,
					#fecfef 100%
				);
				color: #fff;
				.emotion-name {
					font-size: 15px;
					font-weight: 600;
					line-height: 1;
					margin-bottom: 2px;
				}
				.emotion-score {
					font-size: 14px;
					font-weight: 700;
					opacity: 0.9;
				}
			}
			.warm-tips {
				text-align: center;
				margin-bottom: 16px;
				.emotion-status-text {
					margin-bottom: 12px;
					.status-label {
						font-size: 14px;
						color: #8b7355;
						margin-right: 8px;
					}
					.status-emotion {
						font-size: 16px;
						font-weight: 600;
						padding: 4px 12px;
						border-radius: 16px;
						display: inline-block;
					}
				}
				.emotion-intensity {
					margin-bottom: 16px;
					display: flex;
					align-items: center;
					justify-content: center;
					gap: 8px;
					.intensity-dots {
						display: flex;
						gap: 4px;
						.dot {
							width: 8px;
							height: 8px;
							border-radius: 50%;
							background: #e0e0e0;
							transition: all 0.3s ease;
							&.active {
								background: linear-gradient(
									135deg,
									#ff9a9e,
									#fecfef
								);
								transform: scale(1.2);
								box-shadow: 0 2px 8px rgba(255, 154, 158, 0.4);
							}
						}
					}
					.intensity-text {
						font-size: 12px;
						color: #8b7355;
						font-weight: 500;
					}
				}
				.warm-suggestion {
					background: linear-gradient(
						135deg,
						rgba(255, 255, 255, 0.95),
						rgba(255, 255, 255, 0.8)
					);
					border-radius: 16px;
					padding: 12px;
					margin-bottom: 16px;
					display: flex;
					align-items: flex-start;
					gap: 10px;
					border: 1px solid rgba(255, 255, 255, 0.6);
					box-shadow: 0 6px 20px rgba(0, 0, 0, 0.08);
					.suggestion-icon {
						font-size: 20px;
						flex-shrink: 0;
						margin-top: 2px;
					}
					.suggestion-content {
						text-align: left;
						flex: 1;
						.suggestion-title {
							font-size: 14px;
							font-weight: 600;
							color: #8b7355;
							margin-bottom: 6px;
						}
						.suggestion-text {
							font-size: 13px;
							color: #6b5b47;
							line-height: 1.5;
						}
					}
				}
				.healing-actions {
					margin-bottom: 16px;
					.actions-title {
						display: flex;
						align-items: center;
						justify-content: center;
						gap: 8px;
						font-size: 14px;
						font-weight: 600;
						color: #8b7355;
						margin-bottom: 16px;
					}
					.actions-list {
						display: flex;
						flex-direction: column;
						gap: 10px;
						.action-item {
							background: linear-gradient(
								135deg,
								rgba(255, 255, 255, 0.9),
								rgba(255, 255, 255, 0.7)
							);
							border-radius: 12px;
							padding: 12px;
							display: flex;
							align-items: center;
							gap: 10px;
							border: 1px solid rgba(255, 255, 255, 0.5);
							box-shadow: 0 4px 12px rgba(0, 0, 0, 0.06);
							text-align: left;
							.action-icon {
								font-size: 14px;
								color: #ffd700;
								flex-shrink: 0;
							}
							.action-text {
								font-size: 12px;
								color: #6b5b47;
								line-height: 1.4;
								flex: 1;
							}
						}
					}
				}
				.risk-notice {
					background: linear-gradient(135deg, #fff9e6, #ffeaa7);
					border-radius: 16px;
					padding: 16px;
					display: flex;
					align-items: flex-start;
					gap: 12px;
					border: 1px solid rgba(255, 234, 167, 0.6);
					box-shadow: 0 6px 20px rgba(255, 234, 167, 0.3);
					.notice-icon {
						font-size: 20px;
						flex-shrink: 0;
						margin-top: 2px;
					}
					.notice-content {
						flex: 1;
						.notice-title {
							font-size: 14px;
							font-weight: 600;
							color: #d4840f;
							margin-bottom: 6px;
						}
						.notice-text {
							font-size: 13px;
							color: #b8740c;
							line-height: 1.5;
						}
					}
				}
			}
		}
	}
	.chat-main {
		background: linear-gradient(
			135deg,
			rgba(255, 255, 255, 0.95) 0%,
			rgba(255, 252, 250, 0.98) 100%
		);
		border-radius: 20px;
		box-shadow:
			0 12px 40px rgba(251, 146, 60, 0.08),
			0 4px 16px rgba(0, 0, 0, 0.04);
		border: 1px solid rgba(251, 146, 60, 0.1);
		backdrop-filter: blur(10px);
		display: flex;
		flex-direction: column;
		overflow: hidden;
		flex: 1;
	.chat-header {
			background: linear-gradient(135deg, #fb923c 0%, #f59e0b 100%);
			color: white;
			padding: 20px 24px;
			display: flex;
			align-items: center;
			justify-content: space-between;
			position: relative;
			flex-shrink: 0;
			.header-left {
				display: flex;
				align-items: center;
				.chat-avatar {
					width: 48px;
					height: 48px;
					background: rgba(255, 255, 255, 0.25);
					border-radius: 50%;
					display: flex;
					align-items: center;
					justify-content: center;
					margin-right: 16px;
					box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
					position: relative;
					z-index: 1;
				}
				.chat-info {
					h2 {
						font-size: 20px;
						font-weight: 700;
						margin-bottom: 4px;
					}
					p {
						font-size: 14px;
					}
				}
			}
		}
		.chat-messages {
			flex: 1;
			overflow-y: auto;
			padding: 24px;
			display: flex;
			flex-direction: column;
			gap: 16px;
			background: linear-gradient(
				135deg,
				rgba(255, 255, 255, 0.02) 0%,
				rgba(255, 252, 248, 0.05) 100%
			);
			min-height: 0;
			max-height: calc(100vh - 200px);
			scrollbar-width: thin;
			scrollbar-color: rgba(251, 146, 60, 0.3) transparent;
			.session-load-state {
				align-self: center;
				margin: auto;
				color: #6b7280;
				text-align: center;
				.session-load-error p { margin: 0 0 8px; }
			}
			.load-older { align-self: center; flex-shrink: 0; }
			.message-item {
				display: flex;
				align-items: flex-start;
				gap: 12px;
				.message-avatar {
					width: 32px;
					height: 32px;
					border-radius: 50%;
					display: flex;
					align-items: center;
					justify-content: center;
					font-size: 14px;
					color: white;
					flex-shrink: 0;
				}
				&.ai-message {
					.message-avatar {
						background: linear-gradient(135deg, #fb923c, #f59e0b);
						box-shadow: 0 4px 12px rgba(251, 146, 60, 0.3);
					}
				}
				&.user-message {
					.message-avatar {
						background: linear-gradient(135deg, #6b7280, #4b5563);
						box-shadow: 0 4px 12px rgba(107, 114, 128, 0.3);
					}
				}
				.message-content {
					max-width: 70%;
					.message-bubble {
						.plain-message {
							margin: 0;
							white-space: pre-wrap;
							overflow-wrap: anywhere;
						}
						background: linear-gradient(
							135deg,
							rgba(255, 255, 255, 0.9) 0%,
							rgba(255, 252, 248, 0.95) 100%
						);
						border-radius: 16px;
						padding: 12px 16px;
						position: relative;
						animation: fadeInUp 0.4s ease-out;
						border: 1px solid rgba(251, 146, 60, 0.1);
						box-shadow: 0 4px 16px rgba(251, 146, 60, 0.05);
						.typing-indicator {
							display: flex;
							gap: 4px;
							padding: 8px 0;
							.typing-dot {
								width: 8px;
								height: 8px;
								background: #ccc;
								border-radius: 50%;
								animation: typing 1.5s ease-in-out infinite;
								&:nth-child(2) {
									animation-delay: 0.2s;
								}
								&:nth-child(3) {
									animation-delay: 0.4s;
								}
							}
						}
						/* 错误消息样式 */
						.error-message {
							background: linear-gradient(
								135deg,
								#fef2f2 0%,
								#fecaca 100%
							);
							border: 1px solid #f87171;
							border-radius: 12px;
							padding: 12px 16px;
							color: #991b1b;
							font-weight: 500;
							display: flex;
							align-items: center;
							gap: 8px;
						}
					}
					.message-time {
						font-size: 12px;
						color: #999;
						margin-top: 4px;
					}
				}
			}
		}
		.chat-input {
			border-top: 1px solid rgba(251, 146, 60, 0.1);
			padding: 20px 24px;
			display: flex;
			gap: 12px;
			align-items: flex-end;
			background: linear-gradient(
				135deg,
				rgba(255, 255, 255, 0.5) 0%,
				rgba(255, 252, 248, 0.7) 100%
			);
			backdrop-filter: blur(10px);
			flex-shrink: 0;
			.input-container {
				flex: 1;
			}
			.input-footer {
				display: flex;
				justify-content: space-between;
				align-items: center;
				font-size: 12px;
				color: #78716c;
				font-weight: 500;
			}
			.send-btn {
				height: 60px;
				width: 60px;
				border-radius: 16px;
				background: linear-gradient(
					135deg,
					#fb923c 0%,
					#f59e0b 100%
				) !important;
				border: none !important;
				box-shadow: 0 6px 20px rgba(251, 146, 60, 0.25);
				transition: all 0.3s ease;
			}
		}
	}
	.review-consent {
		display: flex;
		align-items: center;
		gap: 6px;
		max-width: 350px;
		font-size: 12px;
		color: #6b7280;
	}
	.memory-consent-row { display: flex; align-items: center; justify-content: space-between; margin: 18px 0; padding: 12px 14px; background: #f8fafc; border-radius: 8px; color: #374151; }
	.memory-load-state { margin-top: 18px; color: #6b7280; line-height: 1.6; }
	.memory-load-error { color: #9f1239; }
	.memory-load-error p { margin: 0 0 10px; }
	.memory-editor { display: flex; flex-direction: column; gap: 10px; margin-bottom: 18px; }
	.memory-editor-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
	.memory-list { display: flex; flex-direction: column; gap: 10px; max-height: 260px; overflow-y: auto; }
	.memory-item { padding: 12px; border: 1px solid #e5e7eb; border-radius: 8px; }
	.memory-content { color: #374151; line-height: 1.6; white-space: pre-wrap; }
	.memory-meta { margin-top: 5px; color: #9ca3af; font-size: 12px; }
	.memory-actions { display: flex; justify-content: flex-end; margin-top: 4px; }
	.consent-help {
		color: #9ca3af;
		cursor: help;
	}
}
</style>
