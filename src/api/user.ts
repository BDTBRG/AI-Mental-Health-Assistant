import request from '@/utils/request'
import type {
	RegisterFormQuery,
	StartSession,
	HistorySession,
	Message,
	ListData,
	EmotionAnalysis,
	EmotionDiaryQuery,
	KnowledgeArticle,
	KnowledgeArticleQuery,
	KnowledgeArticleDetail,
} from '@/types/user'

export function register(
	data: RegisterFormQuery,
): Promise<Record<string, any>> {
	return request.post('/user/add', data)
}

export function startSession(data: {
	initialMessage: string
	sessionTitle: string
}): Promise<StartSession> {
	return request.post('/psychological-chat/session/start', data)
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

export function getKnowledgeArticlesList(
	params: KnowledgeArticleQuery,
): Promise<ListData<KnowledgeArticle>> {
	return request.get('/knowledge/article/page', { params })
}

export function getKnowledgeArticleDetail(id: string): Promise<KnowledgeArticleDetail> {
	return request.get(`/knowledge/article/${id}`)
}
