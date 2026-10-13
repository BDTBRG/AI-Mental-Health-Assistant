<template>
	<div>
		<PageHead title="人工复核工作台" />
		<div class="queue-toolbar">
			<el-select v-model="pagination.reviewStatus" placeholder="选择复核状态" @change="handleStatusChange">
				<el-option label="待处理" value="PENDING" />
				<el-option label="处理中" value="IN_PROGRESS" />
				<el-option label="已完成" value="RESOLVED" />
			</el-select>
			<span>队列仅展示用户已申请且仍授权复核的会话。</span>
		</div>
		<el-table :data="tableData" style="width: 100%">
			<el-table-column label="用户" width="100">
				<template #default="scope">
					{{ scope.row.userNickname }}
				</template>
			</el-table-column>
			<el-table-column label="情绪日志">
				<template #default="scope">
					<div class="session-title">
						{{ scope.row.sessionTitle }}
						<el-tag v-if="scope.row.reviewConsent" size="small" type="success">用户已授权</el-tag>
						<el-tag size="small" :type="reviewTagType(scope.row.reviewStatus)">{{ reviewStatusLabel(scope.row.reviewStatus) }}</el-tag>
					</div>
					<div class="session-preview">
						{{ scope.row.lastMessageContent }}
					</div>
				</template>
			</el-table-column>
			<el-table-column prop="messageCount" label="消息数" width="100" />
			<el-table-column prop="lastMessageTime" label="时间" width="100" />
			<el-table-column label="操作" width="250">
				<template #default="scope">
					<el-button text type="primary" @click="viewSessionDetail(scope.row)">详情</el-button>
					<el-button v-if="scope.row.reviewStatus === 'PENDING'" text type="warning" @click="changeReviewState(scope.row, 'IN_PROGRESS')">领取</el-button>
					<el-button v-if="scope.row.reviewStatus === 'IN_PROGRESS' && scope.row.reviewAssigneeId === currentUserId" text type="success" @click="changeReviewState(scope.row, 'RESOLVED')">完成</el-button>
					<el-button v-if="scope.row.reviewStatus === 'IN_PROGRESS' && scope.row.reviewAssigneeId === currentUserId" text @click="changeReviewState(scope.row, 'PENDING')">释放</el-button>
				</template>
			</el-table-column>
		</el-table>
		<el-pagination
			style="margin-top: 25px"
			v-model:current-page="pagination.currentPage"
			v-model:page-size="pagination.size"
			:total="Number(pagination.total)"
			layout="prev, pager, next, total, sizes, jumper"
			@change="handleChange">
		</el-pagination>
		<el-dialog
			v-model="showDetailDialog"
			title="咨询会话详情"
			width="70%"
			:close-on-click-model="false">
			<div class="session-detail">
				<div class="detail-header">
					<div class="detail-row">
						<div class="detail-label">用户：</div>
						<div class="detail-value">
							{{ sessionDetail?.userNickname }}
						</div>
					</div>
					<div class="detail-row">
						<div class="detail-label">开始时间：</div>
						<div class="detail-value">
							{{ sessionDetail?.startedAt }}
						</div>
					</div>
					<div class="detail-row">
						<div class="detail-label">消息数：</div>
						<div class="detail-value">
							{{ sessionDetail?.messageCount }}
						</div>
					</div>
				</div>
				<div class="messages-container">
					<div class="messages-header">
						<h4>对话记录</h4>
					</div>
					<div class="messages-list" v-loading="loadingMessages">
						<div
							v-for="message in sessionMessages"
							:key="message.id"
							class="message-item"
							:class="
								message.senderType === 1 ?
									'user-message'
								:	'ai-message'
							">
							<div class="message-header">
								<span>{{
									message.senderType === 1 ? '用户' : 'AI助手'
								}}</span>
								<span class="time">{{
									message.createdAt
								}}</span>
							</div>
							<div class="message-content">
								{{ message.content }}
							</div>
						</div>
					</div>
				</div>
			</div>
			<template #footer>
				<el-button @click="showDetailDialog = false">关闭</el-button>
			</template>
		</el-dialog>
	</div>
</template>

<script setup lang="ts">
import PageHead from '@/components/PageHead.vue'
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
	getConsultationSessionPage,
	getConsultationSessionDetail,
	updateConsultationReviewState,
} from '@/api/admin'
import type {
	ConsultationSessionItem,
	ConsultationSearchParams,
	ConsultationMessage,
} from '@/types/admin'

const tableData = ref<ConsultationSessionItem[]>([])
const currentUserId = Number(JSON.parse(localStorage.getItem('userInfo') || '{}').id)

const pagination = reactive<ConsultationSearchParams>({
	currentPage: '1',
	size: '10',
	total: '0',
	reviewStatus: 'PENDING',
})

onMounted(async () => {
	await handleSearch()
})

const sessionDetail = ref<ConsultationSessionItem>()
const sessionMessages = ref<ConsultationMessage[]>([])
const loadingMessages = ref<boolean>(false)

const viewSessionDetail = async (session: ConsultationSessionItem) => {
	loadingMessages.value = true
	const response = await getConsultationSessionDetail(session.id)
	sessionDetail.value = session
	sessionMessages.value = response
	loadingMessages.value = false
	showDetailDialog.value = true
}

const handleSearch = async () => {
	try {
		const response = await getConsultationSessionPage(pagination)
		tableData.value = response.records as ConsultationSessionItem[]
		pagination.total = response.total.toString()
	} catch (error) {
		console.error('获取咨询记录失败:', error)
	}
}

const handleChange = (page: number) => {
	pagination.currentPage = page.toString()
	handleSearch()
}

const handleStatusChange = () => {
	pagination.currentPage = '1'
	handleSearch()
}

const reviewStatusLabel = (status?: string) => ({
	NONE: '未申请', PENDING: '待处理', IN_PROGRESS: '处理中', RESOLVED: '已完成', CANCELLED: '已撤回',
}[status ?? 'NONE'] ?? '状态未知')

const reviewTagType = (status?: string) => ({
	PENDING: 'warning', IN_PROGRESS: 'primary', RESOLVED: 'success', CANCELLED: 'info',
}[status ?? 'NONE'] ?? 'info')

const changeReviewState = async (
	session: ConsultationSessionItem,
	targetStatus: 'IN_PROGRESS' | 'RESOLVED' | 'PENDING',
) => {
	try {
		await updateConsultationReviewState(
			session.id,
			targetStatus,
			session.reviewVersion ?? 0,
		)
		pagination.reviewStatus = targetStatus
		ElMessage.success(targetStatus === 'IN_PROGRESS' ? '已领取复核事项' : targetStatus === 'RESOLVED' ? '复核已完成' : '已释放复核事项')
		await handleSearch()
	} catch {
		ElMessage.error('状态已变化或操作失败，请刷新队列后重试')
		await handleSearch()
	}
}

const showDetailDialog = ref<boolean>(false)
</script>

<style lang="scss" scoped>
.session-title {
	font-weight: 500;
	color: #333;
	margin-bottom: 4px;
}

.queue-toolbar {
	display: flex;
	align-items: center;
	gap: 12px;
	margin: 0 0 16px;
	color: #6b7280;
	font-size: 13px;
}

.session-preview {
	font-size: 13px;
	color: #666;
	margin-bottom: 4px;
	display: -webkit-box;
	-webkit-line-clamp: 2;
	line-clamp: 2;
	-webkit-box-orient: vertical;
	overflow: hidden;
}

.session-detail {
	max-height: 70vh;
	overflow-y: auto;

	.detail-header {
		margin-bottom: 20px;
		padding: 16px;
		background: #f8f9fa;
		border-radius: 8px;
		border: 1px solid #e9ecef;
	}

	.detail-row {
		display: flex;
		align-items: center;
		margin-bottom: 8px;

		:last-child {
			margin-bottom: 0;
		}

		.detail-label {
			font-weight: 500;
			color: #495057;
			min-width: 80px;
			margin-right: 8px;
		}

		.detail-value {
			color: #333;
		}
	}
}

.messages-container {
	margin-top: 20px;

	.messages-header {
		margin-bottom: 16px;

		h4 {
			margin: 0;
			color: #333;
			font-size: 16px;
			font-weight: 500;
		}
	}

	.messages-list {
		max-height: 400px;
		overflow-y: auto;
		border: 1px solid #e9ecef;
		border-radius: 8px;
		padding: 16px;
		background: #fff;

		.message-item {
			margin-bottom: 12px;
			padding: 12px;
			border-radius: 8px;
			background: #f8f9fa;
			border: 1px solid #e9ecef;

			:last-child {
				margin-bottom: 0;
			}

			&.user-message {
				background: #e8f4fd;
			}

			&.ai-message {
				background: #f0f9f0;
			}
		}

		.message-header {
			display: flex;
			justify-content: space-between;
			align-items: center;
			margin-bottom: 8px;

			.sender {
				font-weight: 500;
				color: #333;
				display: flex;
				align-items: center;
				gap: 4px;
			}

			.time {
				font-size: 12px;
				color: #999;
			}

			.message-content {
				color: #333;
				line-height: 1.6;
				white-space: pre-wrap;
				margin-top: 8px;
				font-size: 14px;
			}
		}
	}
}
</style>
