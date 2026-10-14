import request from '@/utils/request'
import type {
	LoginForm,
	LoginResponse,
	SearchFormData,
	KnowledgeArticleList,
	KnowledgeArticleData,
	ConsultationSearchParams,
	ConsultationMessage,
	KnowledgeArticle,
	ConsultationSessionItem,
	EmotionLogSearchParams,
	EmotionLogItem,
	ListData,
	DashboardData,
} from '@/types/admin'

export function login(data: LoginForm): Promise<LoginResponse> {
	return request.post<LoginResponse>('/user/login', data)
}

export function getCategoryTree(): Promise<KnowledgeArticleList[]> {
	return request.get<KnowledgeArticleList[]>('/knowledge/category/tree')
}

export function getArticlePage(
	params: SearchFormData,
): Promise<ListData<KnowledgeArticle>> {
	return request.get('/knowledge/article/page', { params })
}

export function uploadFile(
	file: File,
	businessId: string,
): Promise<{ filePath: string }> {
	const formData = new FormData()
	formData.append('file', file)
	formData.append('businessType', 'ARTICLE')
	formData.append('businessId', businessId)
	formData.append('businessField', 'cover')

	return request.post('/file/upload', formData, {
		headers: { 'Content-Type': 'multipart/form-data' },
	})
}

export function createArticle(data: KnowledgeArticleData): Promise<void> {
	return request.post('/knowledge/article', data)
}

export function getArticleById(id: number): Promise<KnowledgeArticleData> {
	return request.get(`/knowledge/article/${id}`)
}

export function updateArticle(
	id: string,
	data: KnowledgeArticleData,
): Promise<void> {
	return request.put(`/knowledge/article/${id}`, data)
}

export function changeArticleStatus(id: number, status: number): Promise<void> {
	return request.put(`/knowledge/article/${id}/status`, { status })
}

export function deleteArticle(id: number): Promise<void> {
	return request.delete(`/knowledge/article/${id}`)
}

export function getConsultationSessionPage(
	params: ConsultationSearchParams,
): Promise<ListData<ConsultationSessionItem>> {
	return request.get('/psychological-chat/sessions', {
		params: {
			pageNum: params.currentPage || 1,
			pageSize: params.size || 10,
			reviewStatus: params.reviewStatus || undefined,
		},
	})
}

export function updateConsultationReviewState(
	sessionId: number,
	targetStatus: 'IN_PROGRESS' | 'RESOLVED' | 'PENDING',
	expectedVersion: number,
): Promise<void> {
	return request.put(`/psychological-chat/admin/sessions/${sessionId}/review-state`, {
		targetStatus,
		expectedVersion,
	})
}

export function getConsultationSessionDetail(
	sessionId: number,
): Promise<ConsultationMessage[]> {
	return request.get(`/psychological-chat/sessions/${sessionId}/messages`)
}

const parseEmotionDiaryPageInteger = (
	value: string | undefined,
	defaultValue: number,
	maximum: number,
	fieldName: string,
) => {
	const normalizedValue = value?.trim()
	if (!normalizedValue) return defaultValue

	const parsedValue = Number(normalizedValue)
	if (
		!/^\d+$/.test(normalizedValue) ||
		!Number.isSafeInteger(parsedValue) ||
		parsedValue < 1 ||
		parsedValue > maximum
	) {
		throw new RangeError(`情绪日记${fieldName}分页参数无效`)
	}
	return parsedValue
}

export async function getEmotionlogPage(
	params: EmotionLogSearchParams,
): Promise<ListData<EmotionLogItem>> {
	const scoreRangeValue = params.modelScoreRange?.trim()
	const scoreRange = scoreRangeValue?.match(/^(\d+)-(\d+)$/)
	if (
		scoreRangeValue &&
		(!scoreRange ||
			Number(scoreRange[1]) < 1 ||
			Number(scoreRange[1]) > 10 ||
			Number(scoreRange[2]) < 1 ||
			Number(scoreRange[2]) > 10 ||
			Number(scoreRange[1]) > Number(scoreRange[2]))
	) {
		throw new RangeError('情绪日记评分范围无效，必须在1到10之间且最小值不大于最大值')
	}
	const userId = params.userId?.trim()
	const dominantEmotion = params.dominantEmotion?.trim()
	const currentPage = parseEmotionDiaryPageInteger(
		params.currentPage,
		1,
		2_147_483_647,
		'当前页',
	)
	const size = parseEmotionDiaryPageInteger(params.size, 10, 100, '每页条数')

	return request.get('/emotion-diary/admin/page', {
		params: {
			currentPage,
			size,
			...(userId ? { userId } : {}),
			...(dominantEmotion ? { dominantEmotion } : {}),
			...(scoreRange
				? {
						minMoodScore: Number(scoreRange[1]),
						maxMoodScore: Number(scoreRange[2]),
					}
				: {}),
		},
	})
}

export function deleteEmotionLog(id: number): Promise<void> {
	return request.delete(`/emotion-diary/admin/${id}`)
}

export function getDashboardData(): Promise<DashboardData> {
	return request.get('/data-analytics/overview')
}

export function logout(): Promise<void> {
	return request.post('/user/logout')
}
