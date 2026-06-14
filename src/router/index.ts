import AuthLayout from '@/layouts/AuthLayout.vue'
import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

const backendRoutes: RouteRecordRaw[] = [
	{
		path: '/auth',
		component: AuthLayout,
		children: [
			{
				path: 'login',
				component: () => import('@/views/login.vue'),
				meta: { title: '登录' },
			},
			{
				path: 'register',
				component: () => import('@/views/register.vue'),
				meta: { title: '注册' },
			},
		],
	},
	{
		path: '/back',
		name: 'back',
		redirect: '/back/dashboard',
		component: () => import('@/layouts/admin/BackendLayout.vue'),
		children: [
			{
				path: 'dashboard',
				name: 'dashboard',
				component: () => import('@/views/admin/dashboard.vue'),
				meta: { title: '数据分析', icon: 'PieChart' },
			},
			{
				path: 'knowledge',
				name: 'knowledge',
				component: () => import('@/views/admin/knowledge.vue'),
				meta: { title: '知识文章', icon: 'Document' },
			},
			{
				path: 'consultations',
				name: 'consultations',
				component: () => import('@/views/admin/consultations.vue'),
				meta: { title: '咨询记录', icon: 'Message' },
			},
			{
				path: 'emotional',
				name: 'emotional',
				component: () => import('@/views/admin/emotional.vue'),
				meta: { title: '情感分析', icon: 'User' },
			},
		],
	},
]

const frontendRoutes: RouteRecordRaw[] = [
	{
		path: '/',
		component: () => import('@/layouts/user/FrontendLayout.vue'),
		children: [
			{ path: '', component: () => import('@/views/user/home.vue') },
			{
				path: 'consultation',
				component: () => import('@/views/user/consultation.vue'),
			},
			{
				path: 'emotion-diary',
				component: () => import('@/views/user/emotion-diary.vue'),
			},
			{
				path: 'knowledge',
				component: () => import('@/views/user/knowledge.vue'),
			},
			{
				path: 'knowledge/article/:id',
				component: () => import('@/views/user/articleDetail.vue'),
				props: true,
			},
		],
	},
]

const router = createRouter({
	history: createWebHistory(),
	routes: [...backendRoutes, ...frontendRoutes],
})

router.beforeEach((to, _, next) => {
	const token = localStorage.getItem('token')
	if (token) {
		const userInfo = JSON.parse(localStorage.getItem('userInfo') || '{}')
		if (userInfo.userType == 2) {
			if (to.path.startsWith('/back')) {
				next()
			} else {
				next('/back/dashboard')
			}
		} else if (userInfo.userType == 1) {
			if (to.path.startsWith('/back') || to.path.startsWith('/auth')) {
				next('/')
			} else {
				next()
			}
		}
	} else {
		if (to.path.startsWith('/back')) {
			next('/auth/login')
		} else {
			next()
		}
	}
})

export default router
