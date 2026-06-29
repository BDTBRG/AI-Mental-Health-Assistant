<template>
	<div class="knowledge-container">
		<div class="header-section">
			<div class="header-content">
				<el-image :src="iconUrl" style="width: 60px; height: 60px" />
				<h1>心理健康知识库</h1>
			</div>
		</div>
		<div class="content">
			<div class="recommend-section">
				<div class="section-title">推荐阅读</div>
				<div class="recommend-list">
					<div
						v-for="item in recommendList"
						:key="item.id"
						class="recommend-item"
						@click="goToArticle(item.id)">
						<h4>{{ item.title }}</h4>
						<p class="read-count">
							<el-icon><Histogram /></el-icon>
							阅读量{{ item.readCount }}
						</p>
					</div>
				</div>
			</div>
			<div class="article-list">
				<div
					class="article-item"
					v-for="item in articleList"
					:key="item.id"
					@click="goToArticle(item.id)">
					<el-image
						:src="
							item.coverImage ?
								'http://159.75.169.224:1235' + item.coverImage
							:	'https://file.itndedu.com/psychology_ai.png'
						"
						style="width: 240px; height: 150px">
					</el-image>
					<div class="info">
						<div class="title">
							<h3>{{ item.title }}</h3>
							<el-tag Plain type="primary">{{
								item.categoryName
							}}</el-tag>
						</div>
						<div :style="{ marginTop: '10px' }">
							<div class="flex-box">
								<el-icon><Avatar /></el-icon>
								<span>{{ item.authorName }}</span>
							</div>
							<div class="flex-box">
								<el-icon><List /></el-icon>
								<span>{{
									dayjs(item.updatedAt).format('YYYY-MM-DD')
								}}</span>
							</div>
						</div>
						<div :style="{ marginTop: '20px' }">
							<div class="flex-box">
								<el-icon><Platform /></el-icon>
								<span>观看人数：{{ item.readCount }}</span>
							</div>
						</div>
					</div>
				</div>
			</div>
		</div>
		<div class="pagination-wrapper">
		<el-pagination
			style="margin-top: 25px"
			v-model:current-page="pagination.currentPage"
			v-model:page-size="pagination.pageSize"
				:total="Number(pagination.total)"
				layout="prev, pager, next"
				@change="handleSearch" />
		</div>
	</div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import type { KnowledgeArticleQuery, KnowledgeArticle } from '@/types/user'
import { getKnowledgeArticlesList } from '@/api/user'
import { Avatar } from '@element-plus/icons-vue'
import { dayjs } from 'element-plus'
import { useRouter } from 'vue-router'

const iconUrl = new URL('@/assets/book.png', import.meta.url).href

const router = useRouter()

const pagination = ref({
	currentPage: '1',
	pageSize: '10',
	total: 0,
})

const recommendList = ref<KnowledgeArticle[]>([])

const articleList = ref<KnowledgeArticle[]>([])

const getRecommendArticles = async () => {
	const params: KnowledgeArticleQuery = {
		currentPage: '1',
		pageSize: '5',
		sortDirection: 'desc',
		sortField: 'readCount',
	}
	const data = await getKnowledgeArticlesList(params)
	recommendList.value = data.records
}

const getPageList = async () => {
	const params: KnowledgeArticleQuery = {
		sortDirection: 'desc',
		sortField: 'publishedAt',
		...pagination.value,
	}
	const data = await getKnowledgeArticlesList(params)
	articleList.value = data.records
	pagination.value.total = data.total
}

const handleSearch = (page: string) => {
	pagination.value.currentPage = page
	getPageList()
}

const goToArticle = async (id: number) => {
	router.push(`/knowledge/article/${id}`)
}

onMounted(() => {
	getRecommendArticles()
	getPageList()
})
</script>

<style scoped lang="scss">
.knowledge-container {
	background: linear-gradient(135deg, #fafbfc 0%, #f7f9fc 50%, #f2f6fa 100%);
	.flex-box {
		display: flex;
		align-items: center;
		span {
			margin-left: 10px;
		}
	}
	.header-section {
		background: linear-gradient(135deg, #f59e0b 0%, #8b5cf6 100%);
		color: white;
		padding: 48px;
		.header-content {
			display: flex;
			align-items: center;
			gap: 12px;
		}
	}
	.content {
		display: flex;
		gap: 20px;
		margin: 0 auto;
		width: 1200px;
		padding: 20px;
		.recommend-section {
			width: 280px;
			background: white;
			border-radius: 12px;
			box-shadow: 0 2px 10px rgba(0, 0, 0, 0.08);
			padding: 15px;
			height: 400px;
			.section-title {
				font-size: 12;
				font-weight: 600;
				color: #374151;
				margin-bottom: 10px;
				display: flex;
				align-items: center;
				gap: 5px;
			}
			.recommend-list {
				display: flex;
				flex-direction: column;
				gap: 1rem;
				.recommend-item {
					border-left: 4px solid #f59e0b;
					padding-left: 10px;
					cursor: pointer;
					.read-count {
						margin-top: 15px;
						font-size: 12px;
						color: #6b7280;
						display: flex;
						align-items: center;
						gap: 10px;
					}
				}
			}
		}
		.article-list {
			flex: 1;
			.article-item {
				background: white;
				border-radius: 12px;
				box-shadow: 0 2px 10px rgba(0, 0, 0, 0.08);
				padding: 15px;
				margin-bottom: 20px;
				display: flex;
				.info {
					margin-left: 20px;
					.title {
						display: flex;
						align-items: center;
						gap: 10px;
					}
				}
			}
		}
	}
	.pagination-wrapper {
		display: flex;
		justify-content: center;
		padding-bottom: 30px;
	}
}
</style>
