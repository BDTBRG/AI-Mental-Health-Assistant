import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Login from './login.vue'
import { login } from '@/api/admin'

const mocks = vi.hoisted(() => ({
	login: vi.fn(),
	routerReplace: vi.fn(),
	validate: vi.fn(),
	resolveValidation: undefined as ((valid: boolean) => void) | undefined,
}))

vi.mock('@/api/admin', () => ({ login: mocks.login }))
vi.mock('vue-router', () => ({
	useRoute: () => ({ query: { redirect: '/consultation?session=abc' } }),
	useRouter: () => ({ replace: mocks.routerReplace }),
}))

const FormStub = defineComponent({
	setup(_, { expose, slots }) {
		expose({ validate: () => mocks.validate() })
		return () => h('form', slots.default?.())
	},
})

const ButtonStub = defineComponent({
	emits: ['click'],
	setup(_, { emit, slots }) {
		return () => h('button', { onClick: () => emit('click') }, slots.default?.())
	},
})

function mountLogin() {
	return mount(Login, {
		global: {
			stubs: {
				'el-form': FormStub,
				'el-form-item': { template: '<div><slot /></div>' },
				'el-input': { template: '<div />' },
				'el-button': ButtonStub,
				Back: true,
			},
		},
	})
}

describe('login navigation', () => {
	beforeEach(() => {
		vi.stubGlobal('localStorage', {
			clear: vi.fn(),
			getItem: vi.fn(() => null),
			removeItem: vi.fn(),
			setItem: vi.fn(),
		})
		mocks.login.mockReset()
		mocks.routerReplace.mockReset()
		mocks.validate.mockReset()
		mocks.resolveValidation = undefined
	})
	afterEach(() => vi.unstubAllGlobals())

	it('returns to the requested private route after a successful login', async () => {
		mocks.validate.mockResolvedValue(true)
		mocks.login.mockResolvedValue({ token: 'user-token', userInfo: { userType: 1 } })
		const wrapper = mountLogin()

		await wrapper.get('button').trigger('click')
		await flushPromises()

		expect(login).toHaveBeenCalledOnce()
		expect(mocks.routerReplace).toHaveBeenCalledWith('/consultation?session=abc')
	})

	it('prevents duplicate login attempts while validation is pending', async () => {
		mocks.validate.mockImplementation(() => new Promise<boolean>(resolve => {
			mocks.resolveValidation = resolve
		}))
		const wrapper = mountLogin()
		const button = wrapper.get('button').element

		button.click()
		button.click()
		expect(mocks.validate).toHaveBeenCalledOnce()
		mocks.resolveValidation?.(false)
		await flushPromises()

		expect(login).not.toHaveBeenCalled()
	})
})
