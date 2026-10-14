import { describe, expect, it, vi } from 'vitest'
import { finishLogout } from './logout'

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

describe('finishLogout', () => {
	it('clears local credentials even when server logout is unavailable', async () => {
		const storage = createStorage()
		const requestLogout = vi.fn().mockRejectedValue(new Error('network unavailable'))

		const serverRevoked = await finishLogout(requestLogout, storage)

		expect(serverRevoked).toBe(false)
		expect(requestLogout).toHaveBeenCalledOnce()
		expect(storage.values.has('token')).toBe(false)
		expect(storage.values.has('userInfo')).toBe(false)
	})

	it('reports server revocation only after a successful logout response', async () => {
		const storage = createStorage()
		const requestLogout = vi.fn().mockResolvedValue(undefined)

		const serverRevoked = await finishLogout(requestLogout, storage)

		expect(serverRevoked).toBe(true)
		expect(storage.values.has('token')).toBe(false)
		expect(storage.values.has('userInfo')).toBe(false)
	})
})
