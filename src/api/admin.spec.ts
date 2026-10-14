import { beforeEach, describe, expect, it, vi } from 'vitest'
import request from '@/utils/request'
import { getEmotionlogPage } from './admin'

vi.mock('@/utils/request', () => ({
	default: { get: vi.fn() },
}))

	describe('getEmotionlogPage', () => {
	beforeEach(() => {
		vi.mocked(request.get).mockClear().mockResolvedValue({
			records: [],
			current: 1,
			page: 1,
			size: 10,
			total: 0,
		})
	})

	it('serializes pagination and diary filters using the backend query names', async () => {
		await getEmotionlogPage({
			currentPage: '3',
			size: '8',
			userId: '42',
			modelScoreRange: '4-6',
			dominantEmotion: '焦虑',
		})

		expect(request.get).toHaveBeenCalledWith('/emotion-diary/admin/page', {
			params: {
				currentPage: 3,
				size: 8,
				userId: '42',
				minMoodScore: 4,
				maxMoodScore: 6,
				dominantEmotion: '焦虑',
			},
		})
	})

	it('omits blank user and score filters while preserving endpoint pagination defaults', async () => {
		await getEmotionlogPage({
			currentPage: '',
			size: '',
			userId: ' ',
			modelScoreRange: '',
		})

		expect(request.get).toHaveBeenCalledWith('/emotion-diary/admin/page', {
			params: { currentPage: 1, size: 10 },
		})
	})

	it.each([
		{ currentPage: '0', size: '10' },
		{ currentPage: '-1', size: '10' },
		{ currentPage: '1.5', size: '10' },
		{ currentPage: '2147483648', size: '10' },
		{ currentPage: '1', size: '0' },
		{ currentPage: '1', size: '-1' },
		{ currentPage: '1', size: '101' },
		{ currentPage: '1', size: '999999999999999999999' },
	])('rejects pagination values outside the backend contract: %j', async params => {
		await expect(getEmotionlogPage(params)).rejects.toThrow(/分页/)
		expect(request.get).not.toHaveBeenCalled()
	})

	it('serializes valid pagination boundaries', async () => {
		await getEmotionlogPage({ currentPage: '2147483647', size: '100' })

		expect(request.get).toHaveBeenCalledWith('/emotion-diary/admin/page', {
			params: { currentPage: 2147483647, size: 100 },
		})
	})

	it.each(['oops', '0-11', '7-4'])(
		'rejects an invalid mood score range before requesting: %s',
		async modelScoreRange => {
			const error = await getEmotionlogPage({ modelScoreRange }).catch(reason => reason)

			expect(error).toBeInstanceOf(RangeError)
			expect(error.message).toMatch(/评分范围/)
			expect(request.get).not.toHaveBeenCalled()
		},
	)

	it('serializes the full valid mood score range', async () => {
		await getEmotionlogPage({ modelScoreRange: '1-10' })

		expect(request.get).toHaveBeenCalledWith('/emotion-diary/admin/page', {
			params: {
				currentPage: 1,
				size: 10,
				minMoodScore: 1,
				maxMoodScore: 10,
			},
		})
	})
})
