export async function finishLogout(
	requestLogout: () => Promise<unknown>,
	storage: Pick<Storage, 'removeItem'> = localStorage,
): Promise<boolean> {
	let serverRevoked = false
	try {
		await requestLogout()
		serverRevoked = true
	} catch {
		// Local credentials still need to be removed when the server cannot be reached.
	} finally {
		storage.removeItem('token')
		storage.removeItem('userInfo')
	}
	return serverRevoked
}
