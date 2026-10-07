/**
 * SPDX-FileCopyrightText: 2026 Felix Kurth
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */
import type { RemoteComicSource, RemoteZipSource } from '../../third_party/nextcloud_ebook_reader/packages/reader-core/src/types.ts'

import { describe, expect, it, vi } from 'vitest'
import { BridgeError } from './errors.ts'
import { buildSource, comicPageWidth, fetchChecked, fillPageUrl } from './source.ts'

const O = 'https://appassets.androidplatform.net'

/**
 * @param status
 * @param body
 * @param headers
 */
function res(status: number, body: BodyInit | null = null, headers: Record<string, string> = {}): Response {
	return new Response(body, { status, headers })
}

describe('fetchChecked', () => {
	it('maps proxy errors to bridge codes', async () => {
		const f = (r: Response | Error) => (vi.fn(async () => { if (r instanceof Error) { throw r } return r }) as unknown as typeof fetch)
		await expect(fetchChecked(f(res(504, null, { 'X-Reader-Error': 'network' })), '/x')).rejects.toMatchObject({ code: 'network' })
		await expect(fetchChecked(f(res(401, null, { 'X-Reader-Error': 'unauthorized' })), '/x')).rejects.toMatchObject({ code: 'unauthorized' })
		await expect(fetchChecked(f(res(404)), '/x')).rejects.toMatchObject({ code: 'open-failed' })
		await expect(fetchChecked(f(new TypeError('Failed to fetch')), '/x')).rejects.toMatchObject({ code: 'network' })
		expect((await fetchChecked(f(res(200, 'ok')), '/x')).status).toBe(200)
	})
})

describe('helpers', () => {
	it('fills page url placeholders', () => {
		expect(fillPageUrl(`${O}/api/comic/page/{index}?w={width}`, 3, 720.4)).toBe(`${O}/api/comic/page/3?w=720`)
	})
	it('caps dpr at 2 and bounds by height', () => {
		expect(comicPageWidth(1080, 1920, 3)).toBe(2160)
		expect(comicPageWidth(1000, 800, 1)).toBe(600)
	})
})

describe('buildSource', () => {
	it('builds a File from the file source', async () => {
		const fetchFn = vi.fn(async () => res(200, 'PK', { 'Content-Type': 'application/epub+zip' })) as unknown as typeof fetch
		const src = await buildSource({ type: 'file', url: `${O}/api/book`, fileName: 'a.epub' }, { fetchFn, pageWidth: () => 100 })
		expect(src).toBeInstanceOf(File)
		expect((src as File).name).toBe('a.epub')
		expect(fetchFn).toHaveBeenCalledWith(`${O}/api/book`, { credentials: 'omit' })
	})

	it('reads remote-zip entries one by one with encoded names', async () => {
		const fetchFn = vi.fn(async (url: string) => {
			if (url.endsWith('/api/archive/entries')) {
				return res(200, JSON.stringify({ etag: 'e1', entries: [{ name: 'OEBPS/a b.xhtml', size: 5 }] }))
			}
			return res(200, 'x')
		}) as unknown as typeof fetch
		const src = await buildSource({ type: 'remote-zip', name: 'a.epub', entriesUrl: `${O}/api/archive/entries`, itemUrl: `${O}/api/item?id=` }, { fetchFn, pageWidth: () => 100 }) as RemoteZipSource
		expect(src.kind).toBe('remote-zip')
		expect(src.entries).toHaveLength(1)
		await src.loadEntry('OEBPS/a b.xhtml')
		expect(fetchFn).toHaveBeenLastCalledWith(`${O}/api/item?id=OEBPS%2Fa%20b.xhtml&v=e1`, { credentials: 'omit' })
	})

	it('falls back to the whole file when the entry list is empty or missing', async () => {
		const fetchFn = vi.fn(async (url: string) => {
			if (url.endsWith('/api/archive/entries')) {
				return res(404)
			}
			return res(200, 'PK')
		}) as unknown as typeof fetch
		const src = await buildSource({ type: 'remote-zip', name: 'a.epub' }, { fetchFn, pageWidth: () => 100 })
		expect(src).toBeInstanceOf(File)
	})

	it('does not fall back on network or auth errors', async () => {
		const fetchFn = vi.fn(async () => res(401, null, { 'X-Reader-Error': 'unauthorized' })) as unknown as typeof fetch
		await expect(buildSource({ type: 'remote-zip', name: 'a.epub' }, { fetchFn, pageWidth: () => 100 })).rejects.toBeInstanceOf(BridgeError)
		expect(fetchFn).toHaveBeenCalledTimes(1)
	})

	it('builds remote-comic pages with index and width', async () => {
		const fetchFn = vi.fn(async (url: string) => {
			if (url.endsWith('/api/comic/pages')) {
				return res(200, JSON.stringify({ etag: 'e2', pages: [{ name: '1.jpg', size: 1 }, { name: '2.jpg', size: 1 }] }))
			}
			return res(200, 'img')
		}) as unknown as typeof fetch
		const src = await buildSource({ type: 'remote-comic', name: 'c.cbz', pagesUrl: `${O}/api/comic/pages`, pageUrl: `${O}/api/comic/page/{index}?w={width}` }, { fetchFn, pageWidth: () => 540 }) as RemoteComicSource
		expect(src.kind).toBe('remote-comic')
		expect(src.pages).toHaveLength(2)
		await src.loadPage(1)
		expect(fetchFn).toHaveBeenLastCalledWith(`${O}/api/comic/page/1?w=540&v=e2`, { credentials: 'omit' })
	})
})
