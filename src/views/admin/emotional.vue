<template>
	<div>
		<PageHead title="情绪日志" />
		<TableSearch :formItem="formItem" @search="handleSearch" />
		<div v-if="queryError" class="query-error" role="alert">
			<span>{{ queryError }}</span>
			<button data-testid="query-retry" type="button" @click="handleRetry">
				重试
			</button>
		</div>
		<el-table :data="tableData" style="width: 100%; margin-top: 25px">
			<el-table-column prop="userId" label="用户ID" width="80" />
			<el-table-column prop="username" label="用户名" width="130" />
			<el-table-column prop="nickname" label="昵称" width="100" />
			<el-table-column prop="diaryDate" label="记录日期" width="120" />
			<el-table-column prop="moodScore" label="情绪评分" width="120">
				<template #default="scope">
					<el-rate
						:model-value="scope.row.moodScore"
						:max="10"
						disabled />
				</template>
			</el-table-column>
			<el-table-column label="生活指标" width="120">
				<template #default="scope">
					<div>
						<p>睡眠：{{ scope.row.sleepQuality }} / 5</p>
						<p>压力：{{ scope.row.stressLevel }} / 5</p>
					</div>
				</template>
			</el-table-column>
			<el-table-column
				prop="emotionTriggers"
				label="情绪触发因素"
				width="120" />
			<el-table-column prop="diaryContent" label="日志内容" width="250" />
			<el-table-column label="操作" fixed="right">
				<template #default="scope">
					<el-button
						@click="viewSessionDetail(scope.row)"
						text
						type="primary"
						>详情</el-button
					>
					<el-button
						@click="handleDelete(scope.row)"
						:loading="deletingIds.has(scope.row.id)"
						:disabled="deletingIds.has(scope.row.id)"
						text
						type="danger"
						>删除</el-button
					>
				</template>
			</el-table-column>
		</el-table>
		<el-pagination
			style="margin-top: 25px"
			v-model:current-page="pagination.currentPage"
			:page-size="PAGE_SIZE"
			:total="Number(pagination.total)"
			layout="prev, pager, next, total"
			@current-change="handleCurrentPageChange" />
		<el-dialog
			v-model="detailDialogVisible"
			title="情绪日志详情"
			width="800px"
			:close-on-click-modal="false">
			<div v-if="currentDetail">
				<div class="detail-section">
					<h4>用户信息</h4>
					<el-descriptions :column="2" border>
						<el-descriptions-item label="用户名">{{
							currentDetail.username
						}}</el-descriptions-item>
						<el-descriptions-item label="昵称">{{
							currentDetail.nickname
						}}</el-descriptions-item>
						<el-descriptions-item label="用户ID">{{
							currentDetail.userId
						}}</el-descriptions-item>
						<el-descriptions-item label="记录日期">{{
							currentDetail.diaryDate
						}}</el-descriptions-item>
					</el-descriptions>
				</div>
				<div class="detail-section">
					<h4>情绪状态</h4>
					<el-descriptions :column="2" border>
						<el-descriptions-item label="情绪评分">
							<el-rate
								:model-value="currentDetail.moodScore"
								:max="10"
								disabled />
						</el-descriptions-item>
						<el-descriptions-item label="主要情绪"
							><el-tag
								:type="
									getEmotionTagType(
										currentDetail.dominantEmotion as
											| '高兴'
											| '平静'
											| '兴奋'
											| '愤怒'
											| '悲伤'
											| '焦虑',
									)
								"
								>{{
									currentDetail.dominantEmotion || '-'
								}}</el-tag
							></el-descriptions-item
						>
						<el-descriptions-item label="睡眠质量"
							>{{
								currentDetail.sleepQuality || '-'
							}}/5</el-descriptions-item
						>
						<el-descriptions-item label="压力水平"
							>{{
								currentDetail.stressLevel || '-'
							}}/5</el-descriptions-item
						>
					</el-descriptions>
				</div>
				<div class="detail-section">
					<h4>日志内容</h4>
					<el-descriptions :column="1" border>
						<el-descriptions-item label="情绪触发因素">{{
							currentDetail.emotionTriggers || '无'
						}}</el-descriptions-item>
						<el-descriptions-item label="日志内容">{{
							currentDetail.diaryContent || '无'
						}}</el-descriptions-item>
					</el-descriptions>
				</div>
				<div class="detail-section">
					<h4>AI情绪分析结果</h4>
					<div v-if="aiDataError" class="analysis-data-error" role="alert">
						分析数据无法读取
					</div>
					<div v-else class="ai-analysis-result">
						<el-descriptions :column="2" border>
							<el-descriptions-item label="主要情绪">
								<el-tag
									:type="
										getAiEmotionTagType(
											aiData.primaryEmotion as any,
										)
									"
									>{{ aiData.primaryEmotion }}</el-tag
								>
							</el-descriptions-item>
							<el-descriptions-item label="情绪强度">
								<el-progress
									v-if="typeof aiData.emotionScore === 'number'"
									:percentage="aiData.emotionScore"
									:color="
										getEmotionScoreColor(
											aiData.emotionScore as number,
										)
									"
									:stroke-width="8" />
								<span v-else>暂不可判断</span>
							</el-descriptions-item>
							<el-descriptions-item label="风险等级">
								<el-tag
									:type="aiData.riskLevel == null ? 'info' : getRiskLevelTagType(aiData.riskLevel as 0 | 1 | 2 | 3)"
									>{{ aiData.riskLevel == null ? '未知' : aiData.riskLevel }}</el-tag
								>
							</el-descriptions-item>
							<el-descriptions-item label="情绪性质">
								<el-tag
									:type="aiData.isNegative == null ? 'info' : aiData.isNegative ? 'danger' : 'success'">
									{{ aiData.isNegative == null ? '未能判断' : aiData.isNegative ? '负面情绪' : '正面情绪' }}
								</el-tag>
							</el-descriptions-item>
						</el-descriptions>
						<div class="ai-suggestion-section">
							<h5>专业建议</h5>
							<div class="suggestion-content">
								{{ aiData.suggestion || '暂无专业建议' }}
							</div>
						</div>
						<div class="ai-risk-section">
							<h5>风险评估</h5>
							<div class="risk-content">
								{{ aiData.riskDescription || '暂无风险评估' }}
							</div>
						</div>
						<div class="ai-improvements-section">
							<h5>改善建议</h5>
							<ul class="improvement-list">
								<li
									v-for="item in aiData.improvementSuggestions"
									:key="item">
									{{ item }}
								</li>
							</ul>
						</div>
					</div>
				</div>
				<div class="detail-section">
					<h4>时间信息</h4>
					<el-descriptions :column="2" border>
						<el-descriptions-item label="创建时间">{{
							currentDetail.createdAt
						}}</el-descriptions-item>
						<el-descriptions-item label="更新时间">{{
							currentDetail.updatedAt
						}}</el-descriptions-item>
					</el-descriptions>
				</div>
			</div>
			<template #footer>
				<el-button @click="detailDialogVisible = false">关闭</el-button>
			</template>
		</el-dialog>
	</div>
</template>

<script setup lang="ts">
import PageHead from '@/components/PageHead.vue'
import TableSearch from '@/components/tableSearch.vue'
import type {
	FormItemConfig,
	EmotionLogSearchParams,
	SearchFormData,
	EmotionLogItem,
} from '@/types/admin'
import { getEmotionlogPage, deleteEmotionLog } from '@/api/admin'
import { reactive, ref, onMounted } from 'vue'
import { ElMessageBox } from 'element-plus'

const getEmotionTagType = (
	emotion: '高兴' | '平静' | '兴奋' | '愤怒' | '悲伤' | '焦虑',
) => {
	const emotionTypes = {
		高兴: 'success',
		平静: 'info',
		兴奋: 'warning',
		愤怒: 'danger',
		悲伤: 'info',
		焦虑: 'warning',
	}
	return emotionTypes[emotion] || 'info'
}

const getAiEmotionTagType = (
	emotion:
		| '快乐'
		| '平静'
		| '兴奋'
		| '满足'
		| '愤怒'
		| '悲伤'
		| '焦虑'
		| '恐惧'
		| '沮丧'
		| '压力',
) => {
	const emotionTagMap = {
		快乐: 'success',
		平静: 'info',
		兴奋: 'warning',
		满足: 'success',
		愤怒: 'danger',
		悲伤: 'info',
		焦虑: 'warning',
		恐惧: 'danger',
		沮丧: 'info',
		压力: 'warning',
	}
	return emotionTagMap[emotion] || 'info'
}

const getEmotionScoreColor = (score: number) => {
	if (score >= 80) return '#f56c6c'
	if (score >= 60) return '#e6a23c'
	if (score >= 40) return '#909399'
	return '#67c23a'
}

const getRiskLevelTagType = (riskLevel: 0 | 1 | 2 | 3) => {
	const riskTagMap = { 0: 'success', 1: 'info', 2: 'warning', 3: 'danger' }
	return riskTagMap[riskLevel] || 'info'
}

const formItem: FormItemConfig[] = [
	{
		label: '用户ID',
		prop: 'userId',
		comp: 'el-input',
		placeholder: '请输入用户ID',
	},
	{
		label: '主要情绪',
		prop: 'dominantEmotion',
		comp: 'el-select',
		placeholder: '请选择主要情绪',
		options: ['开心', '平静', '焦虑', '悲伤', '兴奋', '疲惫', '惊讶', '困惑'].map(
			emotion => ({ label: emotion, value: emotion }),
		),
	},
	{
		label: '情绪评分',
		prop: 'modelScoreRange',
		comp: 'el-select',
		placeholder: '情绪评分范围',
		options: [
			{ label: '低分（1-3）', value: '1-3' },
			{ label: '中分（4-6）', value: '4-6' },
			{ label: '高分（7-10）', value: '7-10' },
		],
	},
]

const tableData = ref<EmotionLogItem[]>([])

const PAGE_SIZE = 10
const MAX_PAGE_FALLBACK_RETRIES = 3
const pagination = reactive({ currentPage: 1, total: '0' })
const searchFilters = ref<EmotionLogSearchParams>({})
const queryError = ref('')
const deletingIds = ref(new Set<number>())
let latestQuerySequence = 0

type EmotionLogsLoadResult = 'loaded' | 'failed' | 'unstable' | 'stale'

const loadEmotionLogs = async (): Promise<EmotionLogsLoadResult> => {
	const querySequence = ++latestQuerySequence
	const params: EmotionLogSearchParams = {
		...searchFilters.value,
		currentPage: String(pagination.currentPage),
		size: String(PAGE_SIZE),
	}
	let requestedPage = Number(params.currentPage) || 1
	let retryCount = 0
	tableData.value = []
	queryError.value = ''

	try {
		let response = await getEmotionlogPage(params)
		while (true) {
			if (querySequence !== latestQuerySequence) return 'stale'

			const responseSize = Number(response.size) || PAGE_SIZE
			const lastValidPage = Math.max(
				1,
				Math.ceil(response.total / responseSize),
			)
			if (
				response.records.length > 0 ||
				requestedPage <= lastValidPage ||
				retryCount >= MAX_PAGE_FALLBACK_RETRIES
			) {
				break
			}

			requestedPage = lastValidPage
			pagination.currentPage = lastValidPage
			retryCount += 1
			response = await getEmotionlogPage({
				...params,
				currentPage: String(pagination.currentPage),
			})
		}

		if (querySequence !== latestQuerySequence) return 'stale'

		const responseSize = Number(response.size) || PAGE_SIZE
		const lastValidPage = Math.max(
			1,
			Math.ceil(response.total / responseSize),
		)
		if (response.records.length === 0 && requestedPage > lastValidPage) {
			pagination.currentPage = lastValidPage
			pagination.total = response.total.toString()
			queryError.value = '列表仍在变化，请重试'
			return 'unstable'
		}

		tableData.value = response.records as EmotionLogItem[]
		pagination.total = response.total.toString()
		return 'loaded'
	} catch {
		if (querySequence !== latestQuerySequence) return 'stale'
		tableData.value = []
		pagination.total = '0'
		queryError.value = '查询失败，请重试'
		return 'failed'
	}
}

const handleSearch = async (formData?: SearchFormData) => {
	searchFilters.value = formData
		? { ...(formData as EmotionLogSearchParams) }
		: {}
	pagination.currentPage = 1
	await loadEmotionLogs()
}

const handleCurrentPageChange = (currentPage: number) => {
	pagination.currentPage = currentPage
	return loadEmotionLogs()
}

const handleRetry = () => loadEmotionLogs()

const detailDialogVisible = ref<boolean>(false)
const currentDetail = ref<EmotionLogItem | null>(null)
const aiData = ref<Record<string, unknown>>({})
const aiDataError = ref(false)
const viewSessionDetail = (row: EmotionLogItem) => {
	currentDetail.value = row
	detailDialogVisible.value = true
	aiData.value = {}
	aiDataError.value = false
	if (!row.aiEmotionAnalysis) {
		aiData.value = { primaryEmotion: '暂无AI分析结果' }
		return
	}

	try {
		const parsedData: unknown = JSON.parse(row.aiEmotionAnalysis)
		if (
			parsedData === null ||
			typeof parsedData !== 'object' ||
			Array.isArray(parsedData)
		) {
			throw new TypeError('AI 情绪分析数据必须为对象')
		}
		aiData.value = parsedData as Record<string, unknown>
	} catch {
		aiDataError.value = true
	}
}

const handleDelete = async (row: EmotionLogItem) => {
	if (deletingIds.value.has(row.id)) return
	deletingIds.value.add(row.id)

	try {
		try {
			await ElMessageBox.confirm('确定要删除这条情绪日志吗？', '提示', {
				confirmButtonText: '删除',
				cancelButtonText: '取消',
				type: 'warning',
			})
		} catch {
			return
		}

		try {
			await deleteEmotionLog(row.id)
		} catch {
			const refreshResult = await loadEmotionLogs()
			if (refreshResult === 'unstable') {
				await ElMessageBox.alert(
					'删除结果不确定，列表仍在变化，暂无法确认状态',
					'提示',
					{ type: 'warning' },
				)
				return
			}
			if (refreshResult === 'failed') {
				await ElMessageBox.alert(
					'删除结果不确定，列表刷新失败，请手动核对',
					'提示',
					{ type: 'warning' },
				)
				return
			}
			if (refreshResult === 'stale') {
				await ElMessageBox.alert(
					'删除结果不确定，列表已变化，请手动核对',
					'提示',
					{ type: 'warning' },
				)
				return
			}
			if (tableData.value.some(item => item.id === row.id)) {
				await ElMessageBox.alert(
					'删除结果未确认，记录仍显示在列表中，请稍后核对',
					'提示',
					{ type: 'warning' },
				)
				return
			}
			await ElMessageBox.alert(
				'删除结果不确定，记录已不在当前列表中，请手动核对',
				'提示',
				{ type: 'warning' },
			)
			return
		}

		const refreshResult = await loadEmotionLogs()
		if (refreshResult === 'unstable') {
			await ElMessageBox.alert(
				'删除已成功，但列表仍在变化，暂无法确认列表状态',
				'提示',
				{ type: 'warning' },
			)
			return
		}
		if (refreshResult === 'failed') {
			await ElMessageBox.alert(
				'删除已成功，但列表刷新失败，请手动刷新列表',
				'提示',
				{ type: 'warning' },
			)
			return
		}
		if (
			refreshResult === 'loaded' &&
			tableData.value.some(item => item.id === row.id)
		) {
			await ElMessageBox.alert(
				'删除请求已成功，但记录仍显示在列表中，请刷新核对',
				'提示',
				{ type: 'warning' },
			)
			return
		}

		await ElMessageBox.alert('删除成功', '提示', { type: 'success' })
	} finally {
		deletingIds.value.delete(row.id)
	}
}

onMounted(() => {
	handleSearch()
})
</script>
<style scoped lang="scss">
.detail-content {
	.detail-section {
		margin-bottom: 24px;

		h4 {
			margin: 0 0 16px 0;
			color: #303133;
			font-size: 16px;

			i {
				margin-right: 8px;
				color: #409eff;
			}
		}
	}
}

// AI分析相关样式
.ai-analysis-status {
	.ai-status-tag {
		margin-bottom: 4px;

		i {
			margin-right: 4px;
		}
	}

	.ai-analysis-preview {
		font-size: 11px;
		color: #909399;
		margin-top: 2px;
	}
}

.ai-analysis-result {
	.ai-keywords-section,
	.ai-suggestion-section,
	.ai-risk-section,
	.ai-improvements-section {
		margin-top: 16px;
		padding: 12px;
		background-color: #f8f9fa;
		border-radius: 4px;

		h5 {
			margin: 0 0 8px 0;
			color: #606266;
			font-size: 14px;
			font-weight: 600;

			i {
				margin-right: 6px;
				color: #909399;
			}
		}
	}

	.keywords-container {
		display: flex;
		flex-wrap: wrap;
		gap: 6px;

		.keyword-tag {
			background-color: #e1f3d8;
			color: #67c23a;
			border-color: #b3d8a4;
		}
	}

	.suggestion-content,
	.risk-content {
		line-height: 1.6;
		color: #606266;
		background-color: white;
		padding: 8px;
		border-radius: 4px;
		border: 1px solid #ebeef5;
	}

	.improvement-list {
		margin: 0;
		padding-left: 20px;

		li {
			margin-bottom: 4px;
			color: #606266;
			line-height: 1.5;
		}
	}

	.ai-analysis-meta {
		margin-top: 16px;
		padding-top: 12px;
		border-top: 1px solid #ebeef5;

		.analysis-time {
			margin: 0;
			font-size: 12px;
			color: #909399;

			i {
				margin-right: 4px;
			}
		}
	}

	.el-progress {
		.el-progress__text {
			font-size: 12px !important;
		}
	}
}
</style>
