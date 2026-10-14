export interface AuthRouteMeta {
	requiresAuth?: boolean
	requiresAdmin?: boolean
}

export interface AuthRouteSnapshot {
	path: string
	fullPath: string
	meta: AuthRouteMeta
}

export interface AuthStorage {
	getItem(key: string): string | null
	removeItem(key: string): void
}

export type AuthNavigationResult = true | string | {
	path: string
	query?: Record<string, string>
}

const isPathWithin = (path: string, basePath: string) =>
	path === basePath || path.startsWith(`${basePath}/`)

const isValidRole = (value: unknown): value is 1 | 2 => value === 1 || value === 2

const loginTarget = (fullPath: string) => ({
	path: '/auth/login',
	query: { redirect: fullPath },
})

function clearSession(storage: AuthStorage) {
	storage.removeItem('token')
	storage.removeItem('userInfo')
}

function getRole(storage: AuthStorage): 1 | 2 | null {
	const token = storage.getItem('token')
	if (!token) return null

	try {
		const userInfo: unknown = JSON.parse(storage.getItem('userInfo') || '')
		if (userInfo && typeof userInfo === 'object' && 'userType' in userInfo) {
			const role = userInfo.userType
			if (isValidRole(role)) return role
		}
	} catch {
		// Invalid local session data must not leave navigation unresolved.
	}

	clearSession(storage)
	return null
}

export function resolveAuthNavigation(
	target: AuthRouteSnapshot,
	storage: AuthStorage,
): AuthNavigationResult {
	const requiresAuth = Boolean(target.meta.requiresAuth || target.meta.requiresAdmin)
	const role = getRole(storage)

	if (role === null) {
		return requiresAuth ? loginTarget(target.fullPath) : true
	}

	if (role === 2) {
		if (isPathWithin(target.path, '/back')) return true
		return '/back/dashboard'
	}

	if (target.meta.requiresAdmin || isPathWithin(target.path, '/back')) return '/'
	if (isPathWithin(target.path, '/auth')) return '/'
	return true
}

export function getPostLoginDestination(redirect: unknown, userType: unknown): string {
	const fallback = userType === 2 ? '/back/dashboard' : '/'
	if (!isValidRole(userType) || typeof redirect !== 'string') return fallback
	if (!redirect.startsWith('/') || redirect.startsWith('//') || redirect.startsWith('/\\')) return fallback

	const path = redirect.split(/[?#]/, 1)[0]
	if (isPathWithin(path, '/auth')) return fallback
	if (userType === 2) return isPathWithin(path, '/back') ? redirect : fallback
	return isPathWithin(path, '/back') ? fallback : redirect
}
