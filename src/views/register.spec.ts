import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Register from './register.vue'
import { register } from '@/api/user'

const mocks = vi.hoisted(() => ({
	register: vi.fn(),
	routerPush: vi.fn(),
	formValidate: vi.fn(),
}))

vi.mock('@/api/user', () => ({ register: mocks.register }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.routerPush }) }))

const FormStub = defineComponent({
	setup(_, { expose, slots }) {
		expose({
			validate: (callback?: (valid: boolean, fields: Record<string, unknown>) => void) => {
				mocks.formValidate()
				callback?.(false, { username: new Error('required') })
				return Promise.resolve(false)
			},
		})
		return () => h('form', slots.default?.())
	},
})

const ButtonStub = defineComponent({
	emits: ['click'],
	setup(_, { emit, slots }) {
		return () => h('button', { onClick: () => emit('click') }, slots.default?.())
	},
})

describe('register form submission', () => {
	beforeEach(() => {
		mocks.register.mockReset()
		mocks.routerPush.mockReset()
		mocks.formValidate.mockReset()
	})

	it('does not call the registration API when form validation fails', async () => {
		const wrapper = mount(Register, {
			global: {
				stubs: {
					'el-form': FormStub,
					'el-form-item': { template: '<div><slot /></div>' },
					'el-input': { template: '<input />' },
					'el-button': ButtonStub,
				},
			},
		})

		await wrapper.get('button').trigger('click')
		await flushPromises()

		expect(mocks.formValidate).toHaveBeenCalledOnce()
		expect(register).not.toHaveBeenCalled()
	})

	it('does not start validation twice for rapid repeated clicks', async () => {
		const wrapper = mount(Register, {
			global: {
				stubs: {
					'el-form': FormStub,
					'el-form-item': { template: '<div><slot /></div>' },
					'el-input': { template: '<div />' },
					'el-button': ButtonStub,
				},
			},
		})
		const button = wrapper.get('button').element

		button.click()
		button.click()
		await flushPromises()

		expect(mocks.formValidate).toHaveBeenCalledOnce()
		expect(register).not.toHaveBeenCalled()
	})
})
