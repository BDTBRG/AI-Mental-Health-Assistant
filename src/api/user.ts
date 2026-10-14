import request from '@/utils/request'
import type {
	RegisterFormQuery,
	StartSession,
	HistorySession,
	Message,
	ListData,
	EmotionAnalysis,
	EmotionDiaryQuery,
	EmotionDiaryVO,
	KnowledgeArticle,
	KnowledgeArticleQuery,
	KnowledgeArticleDetail,
	AgentMemoryItem,
} from '@/types/user'

export function register(
	data: RegisterFormQuery,
): Promise<Record<string, any>> {
	return request.post('/user/add', data)
}

export function startSession(data: {
	initialMessage: string
	sessionTitle: string
	reviewConsent: boolean
	clientRequestId: string
}): Promise<StartSession> {
	return request.post('/psychological-chat/session/start', data)
}

export function recoverSessionStart(clientRequestId: string): Promise<StartSession | null> {
	return request.get(`/psychological-chat/session/start/${encodeURIComponent(clientRequestId)}`)
}

export function updateReviewConsent(sessionId: string, allowed: boolean): Promise<void> {
	return request.put(`/psychological-chat/sessions/${sessionId}/review-consent`, { allowed })
}

export function requestHumanReview(sessionId: string): Promise<void> {
	return request.post(`/psychological-chat/sessions/${sessionId}/review-request`)
}

export function getPersonalizationConsent(): Promise<{ enabled: boolean }> {
	return request.get('/psychological-chat/memories/consent')
}

export function setPersonalizationConsent(allowed: boolean): Promise<void> {
	return request.put('/psychological-chat/memories/consent', { allowed })
}

export function getAgentMemories(): Promise<AgentMemoryItem[]> {
	return request.get('/psychological-chat/memories')
}

export function createAgentMemory(data: { content: string; expiresAt: string | null }): Promise<AgentMemoryItem> {
	return request.post('/psychological-chat/memories', data)
}

export function updateAgentMemory(id: number, data: { content: string; expiresAt: string | null }): Promise<void> {
	return request.put(`/psychological-chat/memories/${id}`, data)
}

export function deleteAgentMemory(id: number): Promise<void> {
	return request.delete(`/psychological-chat/memories/${id}`)
}

export function clearAgentMemories(): Promise<void> {
	return request.delete('/psychological-chat/memories')
}

export function getHistorySessionList(params: {
	pageNum: number
	pageSize: number
}): Promise<ListData<HistorySession>> {
	return request.get('/psychological-chat/sessions', { params })
}

export function deleteSession(sessionId: string): Promise<void> {
	return request.delete(`/psychological-chat/sessions/${sessionId}`)
}

export function getHistorySessionMessages(
	sessionId: string,
): Promise<Message[]> {
	return request.get(`/psychological-chat/sessions/${sessionId}/messages`)
}

export function getEmotionAnalysis(
	sessionId: string,
): Promise<EmotionAnalysis> {
	return request.get(`/psychological-chat/session/${sessionId}/emotion`)
}

export function submitEmotionDiary(data: EmotionDiaryQuery): Promise<void> {
	return request.post('/emotion-diary', data)
}

/** 获取用户自己的情绪日记列表（含AI分析） */
export function getMyDiaries(): Promise<EmotionDiaryVO[]> {
	return request.get('/emotion-diary/my')
}

export function deleteMyEmotionDiary(id: number): Promise<void> {
	return request.delete(`/emotion-diary/my/${id}`)
}

export function getKnowledgeArticlesList(
	params: KnowledgeArticleQuery,
): Promise<ListData<KnowledgeArticle>> {
	return request.get('/knowledge/article/page', { params })
}

export function getKnowledgeArticleDetail(id: string): Promise<KnowledgeArticleDetail> {
	return request.get(`/knowledge/article/${id}`)
}
