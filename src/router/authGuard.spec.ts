import { describe, expect, it } from 'vitest'
import { getPostLoginDestination, resolveAuthNavigation } from './authGuard'

function createStorage(token: string | null, userInfo: string | null) {
	const values = new Map<string, string>()
	if (token !== null) values.set('token', token)
	if (userInfo !== null) values.set('userInfo', userInfo)
	return {
		getItem: (key: string) => values.get(key) ?? null,
		removeItem: (key: string) => values.delete(key),
		values,
	}
}

describe('auth guard decisions', () => {
	it('preserves the requested private destination for anonymous users', () => {
		const storage = createStorage(null, null)

		expect(resolveAuthNavigation({
			path: '/consultation',
			fullPath: '/consultation?session=abc',
			meta: { requiresAuth: true },
		}, storage)).toEqual({ path: '/auth/login', query: { redirect: '/consultation?session=abc' } })
	})

	it('clears malformed sessions and fails closed on private routes', () => {
		const storage = createStorage('stale-token', '{broken')

		const result = resolveAuthNavigation({
			path: '/emotion-diary',
			fullPath: '/emotion-diary',
			meta: { requiresAuth: true },
		}, storage)

		expect(result).toEqual({ path: '/auth/login', query: { redirect: '/emotion-diary' } })
		expect(storage.values.has('token')).toBe(false)
		expect(storage.values.has('userInfo')).toBe(false)
	})

	it('keeps ordinary users out of admin pages', () => {
		const storage = createStorage('user-token', JSON.stringify({ userType: 1 }))

		expect(resolveAuthNavigation({
			path: '/back/dashboard',
			fullPath: '/back/dashboard',
			meta: { requiresAuth: true, requiresAdmin: true },
		}, storage)).toBe('/')
	})

	it('keeps admins in the admin area', () => {
		const storage = createStorage('admin-token', JSON.stringify({ userType: 2 }))

		expect(resolveAuthNavigation({
			path: '/consultation',
			fullPath: '/consultation',
			meta: { requiresAuth: true },
		}, storage)).toBe('/back/dashboard')
	})
})

describe('post-login navigation', () => {
	it('returns to a same-role internal destination', () => {
		expect(getPostLoginDestination('/consultation?session=abc', 1)).toBe('/consultation?session=abc')
		expect(getPostLoginDestination('/back/knowledge', 2)).toBe('/back/knowledge')
	})

	it('rejects external, auth, and cross-role destinations', () => {
		expect(getPostLoginDestination('https://example.com', 1)).toBe('/')
		expect(getPostLoginDestination('//example.com', 1)).toBe('/')
		expect(getPostLoginDestination('/back/dashboard', 1)).toBe('/')
		expect(getPostLoginDestination('/consultation', 2)).toBe('/back/dashboard')
		expect(getPostLoginDestination('/auth/register', 2)).toBe('/back/dashboard')
	})
})
