import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import Home from './home.vue'

const mocks = vi.hoisted(() => ({ routerPush: vi.fn() }))

vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.routerPush }) }))

const ButtonStub = defineComponent({
	emits: ['click'],
	setup(_, { emit, slots }) {
		return () => h('button', { onClick: () => emit('click') }, slots.default?.())
	},
})

describe('home quick actions', () => {
	it('opens consultation and diary from the primary actions', async () => {
		mocks.routerPush.mockReset()
		const wrapper = mount(Home, {
			global: { stubs: { 'el-button': ButtonStub, 'el-image': true } },
		})
		const actions = wrapper.findAll('.hero-actions button')

		await actions[0].trigger('click')
		expect(mocks.routerPush).toHaveBeenLastCalledWith('/consultation')

		await actions[1].trigger('click')
		expect(mocks.routerPush).toHaveBeenLastCalledWith('/emotion-diary')
	})
})
