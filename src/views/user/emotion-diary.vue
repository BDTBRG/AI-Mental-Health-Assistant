<template>
	<div class="emotionDiary-container">
		<div class="header-section">
			<div class="header-content">
				<el-image
					:src="iconUrl"
					style="width: 60px; height: 60px"></el-image>
				<h1>情绪日记</h1>
			</div>
		</div>
		<div class="content">
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
						:class="{
							selected:
								emotion.name === diaryForm.dominantEmotion,
						}">
						<el-image
							:src="emotion.url"
							style="width: 50px; height: 50px" />
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
							placeholder="今天什么事情触发了您的情绪？"
							:rows="3"
							max-length="1000"
							show-word-limit />
					</div>
					<div class="form-group">
						<div class="form-label">今日感想</div>
						<el-input
							v-model="diaryForm.diaryContent"
							type="textarea"
							placeholder="写下您今天的感受、想法或者任何想记录的内容..."
							:rows="5"
							max-length="2000"
							show-word-limit />
					</div>
					<div class="life-indicators">
						<div class="indicator-group">
							<div class="form-label">睡眠质量</div>
							<el-select
								v-model="diaryForm.sleepQuality"
								placeholder="请选择">
								<el-option label="很差" :value="1"></el-option>
								<el-option label="较差" :value="2"></el-option>
								<el-option label="一般" :value="3"></el-option>
								<el-option label="良好" :value="4"></el-option>
								<el-option label="优秀" :value="5"></el-option>
							</el-select>
						</div>
						<div class="indicator-group">
							<div class="form-label">压力水平</div>
							<el-select
								v-model="diaryForm.stressLevel"
								placeholder="请选择">
								<el-option label="很低" :value="1"></el-option>
								<el-option label="较低" :value="2"></el-option>
								<el-option label="中等" :value="3"></el-option>
								<el-option label="较高" :value="4"></el-option>
								<el-option label="很高" :value="5"></el-option>
							</el-select>
						</div>
					</div>
					<div class="action-buttons">
						<el-button type="primary" @click="resetDiaryForm"
							>重置</el-button
						>
						<el-button type="primary" @click="submitDiaryEntry"
							>提交记录</el-button
						>
					</div>
				</div>
			</div>
		</div>
	</div>
</template>

<script setup lang="ts">
import { reactive, onMounted } from 'vue'
import type { EmotionDiaryQuery } from '@/types/user'
import { dayjs, ElMessage } from 'element-plus'
import { submitEmotionDiary } from '@/api/user'

const iconUrl = new URL('@/assets/like.png', import.meta.url).href

const emotionStatus = [
	'绝望崩溃',
	'消沉抑郁',
	'焦虑烦躁',
	'低落不悦',
	'平静淡然',
	'轻松惬意',
	'愉悦舒心',
	'欢欣满足',
	'兴奋欣喜',
	'极致幸福',
]

const emotionOptions = [
	{
		name: '开心',
		url: new URL('@/assets/开心.png', import.meta.url).href,
	},
	{
		name: '平静',
		url: new URL('@/assets/平静.png', import.meta.url).href,
	},
	{
		name: '焦虑',
		url: new URL('@/assets/焦虑.png', import.meta.url).href,
	},
	{
		name: '悲伤',
		url: new URL('@/assets/悲伤.png', import.meta.url).href,
	},
	{
		name: '兴奋',
		url: new URL('@/assets/兴奋.png', import.meta.url).href,
	},
	{
		name: '疲惫',
		url: new URL('@/assets/疲惫.png', import.meta.url).href,
	},
	{
		name: '惊讶',
		url: new URL('@/assets/惊讶.png', import.meta.url).href,
	},
	{
		name: '困惑',
		url: new URL('@/assets/困惑.png', import.meta.url).href,
	},
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
}

const submitDiaryEntry = async () => {
	if (!diaryForm.moodScore) {
		ElMessage.error('请选择情绪评分')
		return
	}
	if (!diaryForm.dominantEmotion) {
		ElMessage.error('请选择主要情绪')
		return
	}
	if (!diaryForm.emotionTriggers) {
		ElMessage.error('请填写情绪触发因素')
		return
	}
	if (!diaryForm.diaryContent) {
		ElMessage.error('请填写今日感想')
		return
	}
	if (!diaryForm.sleepQuality) {
		ElMessage.error('请选择睡眠质量')
		return
	}
	if (!diaryForm.stressLevel) {
		ElMessage.error('请选择压力水平')
		return
	}
	try {
		await submitEmotionDiary(diaryForm)
		ElMessage.success('情绪日记提交成功')
		resetDiaryForm()
	} catch (error) {
		ElMessage.error('提交失败，请稍后再试')
	}
}

onMounted(() => {})
</script>

<style scoped lang="scss">
.emotionDiary-container {
	background: linear-gradient(135deg, #fafbfc 0%, #f7f9fc 50%, #f2f6fa 100%);
	.header-section {
		background: linear-gradient(135deg, #7ed321 0%, #f5a623 100%);
		color: white;
		padding: 48px;
		.header-content {
			display: flex;
			align-items: center;
			gap: 12px;
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
				font-size: 25px;
				font-weight: 600;
				color: #374151;
			}
			.section {
				margin-bottom: 20px;
				p {
					font-size: 15px;
					color: #6b7280;
					margin-bottom: 15px;
				}
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
					.emotion-name {
						margin-top: 10px;
						padding: 0 75px;
						color: #374151;
					}
					&.selected {
						border-color: #7ed321;
						background: #f0fdf4;
						transform: translateY(-3px);
					}
				}
			}
			.detail-form {
				.form-label {
					margin: 10px 0;
					color: #374151;
				}
				.life-indicators {
					display: flex;
					gap: 20px;
					.indicator-group {
						flex: 1;
					}
				}
				.action-buttons {
					margin-top: 40px;
				}
			}
		}
	}
}
</style>
