<template>
	<div class="emotionDiary-container">
		<div class="header-section">
			<div class="header-content">
				<el-image :src="iconUrl" style="width: 60px; height: 60px"></el-image>
				<h1>情绪日记</h1>
			</div>
		</div>

		<div class="content">
			<!-- 提交成功后的AI分析卡片 -->
			<div class="diary-card ai-analysis-card" v-if="showAiResult">
				<div class="title">🤖 AI情感分析</div>
				<div class="analysis-loading" v-if="aiResultLoading">
					<el-icon class="is-loading"><Loading /></el-icon>
					<span>AI正在分析你的情绪日记，请稍候...</span>
				</div>
				<div class="analysis-content" v-else-if="aiAnalysisText">
					<MarkdownRenderer :content="aiAnalysisText" :is-ai-message="true" />
				</div>
				<div class="analysis-error" v-else>
					<p>AI分析暂时不可用，请稍后再试。你的日记已经保存成功。</p>
				</div>
			</div>

			<!-- 日记填写表单 -->
			<div class="diary-card">
				<div class="title">今日情绪评分</div>
				<div class="section">
					<p>您今天的整体情绪状态如何？（1-10分）</p>
					<div class="rate">
						<el-rate
							v-model="diaryForm.moodScore"
							:text="emotionStatus"
							show-texts
							:max="10"
							size="large" />
					</div>
				</div>
			</div>

			<div class="diary-card">
				<div class="title">主要情绪</div>
				<div class="emotion-grid">
					<div
						class="emotion-card"
						v-for="emotion in emotionOptions"
						:key="emotion.name"
						@click="selectEmotion(emotion.name)"
						:class="{ selected: emotion.name === diaryForm.dominantEmotion }">
						<el-image :src="emotion.url" style="width: 50px; height: 50px" />
						<div class="emotion-name">{{ emotion.name }}</div>
					</div>
				</div>
			</div>

			<div class="diary-card">
				<div class="title">详细记录</div>
				<div class="detail-form">
					<div class="form-group">
						<div class="form-label">情绪触发因素</div>
						<el-input
							v-model="diaryForm.emotionTriggers"
							type="textarea"
							placeholder="今天什么事情触发了你的情绪？"
							:rows="3"
							max-length="1000"
							show-word-limit />
					</div>
					<div class="form-group">
						<div class="form-label">今日感想</div>
						<el-input
							v-model="diaryForm.diaryContent"
							type="textarea"
							placeholder="写下你今天的感受、想法或者任何想记录的内容..."
							:rows="5"
							max-length="2000"
							show-word-limit />
					</div>
					<div class="life-indicators">
						<div class="indicator-group">
							<div class="form-label">睡眠质量</div>
							<el-select v-model="diaryForm.sleepQuality" placeholder="请选择">
								<el-option label="很差" :value="1"></el-option>
								<el-option label="较差" :value="2"></el-option>
								<el-option label="一般" :value="3"></el-option>
								<el-option label="良好" :value="4"></el-option>
								<el-option label="优秀" :value="5"></el-option>
							</el-select>
						</div>
						<div class="indicator-group">
							<div class="form-label">压力水平</div>
							<el-select v-model="diaryForm.stressLevel" placeholder="请选择">
								<el-option label="很低" :value="1"></el-option>
								<el-option label="较低" :value="2"></el-option>
								<el-option label="中等" :value="3"></el-option>
								<el-option label="较高" :value="4"></el-option>
								<el-option label="很高" :value="5"></el-option>
							</el-select>
						</div>
					</div>
					<div class="action-buttons">
						<el-button @click="resetDiaryForm">重置</el-button>
						<el-button type="primary" @click="submitDiaryEntry" :loading="submitting">
							提交记录
						</el-button>
					</div>
				</div>
			</div>

			<!-- 历史日记列表 -->
			<div class="diary-card" v-if="diaryHistory.length > 0">
				<div class="title">📖 历史日记</div>
				<div class="history-list">
					<div class="history-item" v-for="diary in diaryHistory" :key="diary.id">
						<div class="history-header">
							<div class="history-date">{{ diary.diaryDate }}</div>
							<div class="history-emotion">
								<span class="emotion-tag">{{ diary.dominantEmotion }}</span>
								<span class="mood-score">{{ diary.moodScore }}/10</span>
							</div>
							<div class="analysis-status" v-if="diary.hasAiEmotionAnalysis">
								<el-tag type="success" size="small">已分析</el-tag>
							</div>
							<div class="analysis-status" v-else-if="diary.aiAnalysisStatus === 'PENDING'">
								<el-tag type="warning" size="small">分析中</el-tag>
							</div>
						</div>
						<div class="history-content">
							<div class="content-preview">{{ diary.diaryContentPreview || diary.diaryContent }}</div>
							<div class="ai-analysis" v-if="diary.aiEmotionAnalysis">
								<div class="analysis-label">💭 AI分析</div>
								<div class="analysis-text">{{ diary.aiEmotionAnalysis }}</div>
							</div>
						</div>
						<div class="history-meta">
							<span v-if="diary.sleepQuality">睡眠: {{ ['','很差','较差','一般','良好','优秀'][diary.sleepQuality] }}</span>
							<span v-if="diary.stressLevel">压力: {{ ['','很低','较低','中等','较高','很高'][diary.stressLevel] }}</span>
						</div>
					</div>
				</div>
			</div>
		</div>
	</div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import type { EmotionDiaryQuery, EmotionDiaryVO } from '@/types/user'
import { dayjs, ElMessage } from 'element-plus'
import { submitEmotionDiary, getMyDiaries } from '@/api/user'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'

const iconUrl = new URL('@/assets/like.png', import.meta.url).href

const emotionStatus = [
	'绝望崩溃', '消沉抑郁', '焦虑烦躁', '低落不悦', '平静淡然',
	'轻松惬意', '愉悦舒心', '欢欣满足', '兴奋欣喜', '极致幸福',
]

const emotionOptions = [
	{ name: '开心', url: new URL('@/assets/开心.png', import.meta.url).href },
	{ name: '平静', url: new URL('@/assets/平静.png', import.meta.url).href },
	{ name: '焦虑', url: new URL('@/assets/焦虑.png', import.meta.url).href },
	{ name: '悲伤', url: new URL('@/assets/悲伤.png', import.meta.url).href },
	{ name: '兴奋', url: new URL('@/assets/兴奋.png', import.meta.url).href },
	{ name: '疲惫', url: new URL('@/assets/疲惫.png', import.meta.url).href },
	{ name: '惊讶', url: new URL('@/assets/惊讶.png', import.meta.url).href },
	{ name: '困惑', url: new URL('@/assets/困惑.png', import.meta.url).href },
]

const diaryForm = reactive<EmotionDiaryQuery>({
	diaryContent: '',
	diaryDate: dayjs().format('YYYY-MM-DD'),
	dominantEmotion: '',
	emotionTriggers: '',
	moodScore: 0,
	sleepQuality: null,
	stressLevel: null,
})

const submitting = ref(false)
const showAiResult = ref(false)
const aiResultLoading = ref(false)
const aiAnalysisText = ref('')

const diaryHistory = ref<EmotionDiaryVO[]>([])

const selectEmotion = (emotionName: string) => {
	diaryForm.dominantEmotion = emotionName
}

const resetDiaryForm = () => {
	diaryForm.diaryContent = ''
	diaryForm.dominantEmotion = ''
	diaryForm.emotionTriggers = ''
	diaryForm.moodScore = 0
	diaryForm.sleepQuality = null
	diaryForm.stressLevel = null
	showAiResult.value = false
	aiAnalysisText.value = ''
}

const submitDiaryEntry = async () => {
	if (!diaryForm.moodScore) { ElMessage.error('请选择情绪评分'); return }
	if (!diaryForm.dominantEmotion) { ElMessage.error('请选择主要情绪'); return }
	if (!diaryForm.emotionTriggers) { ElMessage.error('请填写情绪触发因素'); return }
	if (!diaryForm.diaryContent) { ElMessage.error('请填写今日感想'); return }
	if (!diaryForm.sleepQuality) { ElMessage.error('请选择睡眠质量'); return }
	if (!diaryForm.stressLevel) { ElMessage.error('请选择压力水平'); return }

	submitting.value = true
	showAiResult.value = true
	aiResultLoading.value = true
	aiAnalysisText.value = ''

	try {
		await submitEmotionDiary(diaryForm)
		ElMessage.success('情绪日记提交成功，AI正在分析中...')

		// 轮询等待AI分析完成（最多等待15秒）
		let attempts = 0
		const maxAttempts = 5
		const pollInterval = 3000

		while (attempts < maxAttempts) {
			await new Promise(resolve => setTimeout(resolve, pollInterval))
			const diaries = await getMyDiaries()
			diaryHistory.value = diaries
			// 找到最新的日记
			if (diaries.length > 0) {
				const latest = diaries[0]
				if (latest.hasAiEmotionAnalysis && latest.aiEmotionAnalysis) {
					aiAnalysisText.value = latest.aiEmotionAnalysis
					aiResultLoading.value = false
					break
				} else if (latest.aiAnalysisStatus === 'FAILED') {
					aiResultLoading.value = false
					ElMessage.warning('AI分析暂不可用，请稍后再试')
					break
				}
			}
			attempts++
		}
		if (aiResultLoading.value) {
			aiResultLoading.value = false
			// 可能分析还在进行中
			ElMessage.info('AI分析可能需要一些时间，请稍后在历史记录中查看')
		}
		resetDiaryForm()
	} catch (error) {
		ElMessage.error('提交失败，请稍后再试')
		showAiResult.value = false
	} finally {
		submitting.value = false
	}
}

const loadDiaryHistory = async () => {
	try {
		const diaries = await getMyDiaries()
		diaryHistory.value = diaries
	} catch {
		// 静默失败
	}
}

onMounted(() => {
	loadDiaryHistory()
})
</script>

<style scoped lang="scss">
.emotionDiary-container {
	background: linear-gradient(135deg, #fafbfc 0%, #f7f9fc 50%, #f2f6fa 100%);
	min-height: calc(100vh - 60px);

	.header-section {
		background: linear-gradient(135deg, #7ed321 0%, #f5a623 100%);
		color: white;
		padding: 48px;
		.header-content {
			display: flex;
			align-items: center;
			gap: 12px;
			h1 { font-size: 32px; font-weight: 700; }
		}
	}

	.content {
		margin: 0 auto;
		width: 980px;
		padding: 20px;

		.diary-card {
			margin-bottom: 20px;
			background: white;
			border-radius: 10px;
			padding: 20px;
			box-shadow: 0 4px 6px rgba(0, 0, 0, 0.05);

			.title {
				margin-bottom: 20px;
				font-size: 20px;
				font-weight: 600;
				color: #374151;
			}

			.section {
				margin-bottom: 20px;
				p { font-size: 15px; color: #6b7280; margin-bottom: 15px; }
			}

			.emotion-grid {
				display: flex;
				flex-wrap: wrap;
				gap: 10px;
				.emotion-card {
					padding: 15px;
					border: 2px solid #e5e7eb;
					border-radius: 15px;
					text-align: center;
					cursor: pointer;
					background: #f9fafb;
					transition: all 0.2s ease;
					.emotion-name {
						margin-top: 10px;
						padding: 0 40px;
						color: #374151;
						font-size: 14px;
					}
					&:hover { border-color: #b8d9a6; background: #f5fbf2; }
					&.selected {
						border-color: #7ed321;
						background: #f0fdf4;
						transform: translateY(-3px);
						box-shadow: 0 4px 12px rgba(126, 211, 33, 0.15);
					}
				}
			}

			.detail-form {
				.form-label { margin: 10px 0; color: #374151; font-weight: 500; }
				.life-indicators {
					display: flex;
					gap: 20px;
					.indicator-group { flex: 1; }
				}
				.action-buttons { margin-top: 30px; display: flex; gap: 12px; }
			}
		}

		/* AI分析卡片 */
		.ai-analysis-card {
			border-left: 4px solid #7ed321;
			background: linear-gradient(135deg, #f8fdf5 0%, #fefdf7 100%);

			.analysis-loading {
				display: flex;
				align-items: center;
				gap: 10px;
				padding: 20px;
				color: #6b7280;
				font-size: 14px;
			}

			.analysis-content {
				padding: 10px 0;
				line-height: 1.8;
				color: #374151;
			}

			.analysis-error {
				padding: 20px;
				color: #9ca3af;
				text-align: center;
			}
		}

		/* 历史记录 */
		.history-list {
			display: flex;
			flex-direction: column;
			gap: 16px;

			.history-item {
				border: 1px solid #e5e7eb;
				border-radius: 12px;
				padding: 16px;
				transition: all 0.2s;
				&:hover { border-color: #d1d5db; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }

				.history-header {
					display: flex;
					align-items: center;
					gap: 16px;
					margin-bottom: 12px;
					flex-wrap: wrap;

					.history-date { font-weight: 600; color: #374151; font-size: 15px; }
					.history-emotion {
						display: flex;
						align-items: center;
						gap: 8px;
						.emotion-tag {
							background: #fef3c7;
							color: #92400e;
							padding: 2px 10px;
							border-radius: 12px;
							font-size: 13px;
						}
						.mood-score { color: #6b7280; font-size: 13px; }
					}
					.analysis-status { margin-left: auto; }
				}

				.history-content {
					.content-preview {
						color: #4b5563;
						font-size: 14px;
						line-height: 1.6;
						margin-bottom: 10px;
					}
					.ai-analysis {
						background: #f8fdf5;
						border-radius: 8px;
						padding: 12px;
						.analysis-label { font-size: 13px; font-weight: 600; color: #065f46; margin-bottom: 6px; }
						.analysis-text { font-size: 13px; color: #374151; line-height: 1.6; }
					}
				}

				.history-meta {
					margin-top: 10px;
					display: flex;
					gap: 16px;
					span { font-size: 12px; color: #9ca3af; }
				}
			}
		}
	}
}
</style>
