import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import FrontendLayout from '@/layouts/user/FrontendLayout.vue'
import AdminNavbar from '@/layouts/admin/Navbar.vue'

const mocks = vi.hoisted(() => ({
	logout: vi.fn(),
	routerReplace: vi.fn(),
	confirm: vi.fn(),
	warning: vi.fn(),
	toggleCollapse: vi.fn(),
}))

vi.mock('@/api/admin', () => ({ logout: mocks.logout }))
vi.mock('vue-router', () => ({
	useRoute: () => ({ meta: { title: '测试页' } }),
	useRouter: () => ({ replace: mocks.routerReplace }),
}))
vi.mock('element-plus', async importOriginal => {
	const actual = await importOriginal<typeof import('element-plus')>()
	return {
		...actual,
		ElMessage: { ...actual.ElMessage, warning: mocks.warning },
		ElMessageBox: { ...actual.ElMessageBox, confirm: mocks.confirm },
	}
})
vi.mock('@/stores/appStore', () => ({
	useAdminStore: () => ({ toggleCollapse: mocks.toggleCollapse }),
}))

const ButtonStub = defineComponent({
	emits: ['click'],
	setup(_, { attrs, emit, slots }) {
		return () => h('button', {
			...attrs,
			onClick: (event: MouseEvent) => emit('click', event),
		}, slots.default?.())
	},
})

const LinkStub = defineComponent({
	setup(_, { attrs, slots }) {
		return () => h('a', attrs, slots.default?.())
	},
})

const DropdownStub = defineComponent({
	emits: ['command'],
	setup(_, { emit, slots }) {
		return () => h('div', [
			h('button', {
				'data-testid': 'dropdown-logout',
				onClick: () => emit('command', 'layout'),
			}),
			slots.default?.(),
			slots.dropdown?.(),
		])
	},
})

function createStorage() {
	const values = new Map<string, string>([
		['token', 'active-token'],
		['userInfo', '{"userType":1}'],
	])
	return {
		getItem: (key: string) => values.get(key) ?? null,
		removeItem: (key: string) => values.delete(key),
		values,
	}
}

describe('logout entry points', () => {
	let storage: ReturnType<typeof createStorage>

	beforeEach(() => {
		storage = createStorage()
		vi.stubGlobal('localStorage', storage)
		mocks.logout.mockReset().mockRejectedValue(new Error('network unavailable'))
		mocks.routerReplace.mockReset().mockResolvedValue(undefined)
		mocks.confirm.mockReset().mockResolvedValue('confirm')
		mocks.warning.mockReset()
		mocks.toggleCollapse.mockReset()
	})

	afterEach(() => vi.unstubAllGlobals())

	it('clears local credentials and keeps a warning visible on the user side', async () => {
		const wrapper = mount(FrontendLayout, {
			global: {
				stubs: {
					'el-button': ButtonStub,
					'el-image': true,
					'router-link': LinkStub,
					'router-view': true,
				},
			},
		})
		await flushPromises()

		await wrapper.get('.logout-btn').trigger('click')
		await flushPromises()

		expect(mocks.logout).toHaveBeenCalledOnce()
		expect(storage.values.has('token')).toBe(false)
		expect(storage.values.has('userInfo')).toBe(false)
		expect(mocks.warning).toHaveBeenCalledOnce()
		expect(mocks.routerReplace).toHaveBeenCalledWith('/auth/login')
		wrapper.unmount()
	})

	it('does the same after the admin confirms logout while the server is unavailable', async () => {
		const wrapper = mount(AdminNavbar, {
			global: {
				stubs: {
					'el-button': ButtonStub,
					'el-dropdown': DropdownStub,
					'el-dropdown-item': true,
					'el-dropdown-menu': true,
					'el-icon': true,
					'el-image': true,
					ArrowDown: true,
					Expand: true,
				},
			},
		})

		await wrapper.get('[data-testid="dropdown-logout"]').trigger('click')
		await flushPromises()

		expect(mocks.confirm).toHaveBeenCalledOnce()
		expect(mocks.logout).toHaveBeenCalledOnce()
		expect(storage.values.has('token')).toBe(false)
		expect(storage.values.has('userInfo')).toBe(false)
		expect(mocks.warning).toHaveBeenCalledOnce()
		expect(mocks.routerReplace).toHaveBeenCalledWith('/auth/login')
		wrapper.unmount()
	})
})
