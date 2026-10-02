/**
 * SPDX-FileCopyrightText: 2026 Felix Kurth
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Builds the reader-core `ReaderSource` from the `open.source` message. All URLs are same-origin
 * https://appassets.androidplatform.net/api/... and answered by the Kotlin request proxy, which
 * adds authentication. Mirrors src/services/bookSource.ts of the web app.
 */
import type { ReaderSource } from '../../third_party/nextcloud_ebook_reader/packages/reader-core/src/types.ts'
import type { BookSourceMsg } from './protocol.ts'

import { BridgeError } from './errors.ts'

const DEFAULT_BOOK_URL = '/api/book'
const DEFAULT_ENTRIES_URL = '/api/archive/entries'
const DEFAULT_ITEM_URL = '/api/item?id='
const DEFAULT_PAGES_URL = '/api/comic/pages'
const DEFAULT_PAGE_URL = '/api/comic/page/{index}?w={width}'

export interface SourceDeps {
	fetchFn: typeof fetch
	/** Comic page width in device pixels */
	pageWidth: () => number
}

/**
 * Fetches a proxy URL and maps failures to bridge errors: the proxy answers 504 +
 * `X-Reader-Error: network` for network problems and 401 + `unauthorized` for rejected credentials.
 *
 * @param fetchFn
 * @param url
 */
export async function fetchChecked(fetchFn: typeof fetch, url: string): Promise<Response> {
	let res: Response
	try {
		res = await fetchFn(url, { credentials: 'omit' })
	} catch (e) {
		throw new BridgeError('network', String((e as Error)?.message ?? e))
	}
	if (res.ok) {
		return res
	}
	const tag = res.headers.get('X-Reader-Error')
	if (tag === 'unauthorized' || res.status === 401) {
		throw new BridgeError('unauthorized', `HTTP ${res.status}`)
	}
	if (tag === 'network' || res.status === 504) {
		throw new BridgeError('network', `HTTP ${res.status}`)
	}
	throw new BridgeError('open-failed', `HTTP ${res.status} for ${url}`)
}

/**
 * Width in device pixels for comic pages: enough for the viewport (a page is roughly 2:3).
 *
 * @param innerWidth
 * @param innerHeight
 * @param dpr
 */
export function comicPageWidth(innerWidth: number, innerHeight: number, dpr: number): number {
	return Math.round(Math.min(innerWidth, innerHeight * 0.75) * Math.min(dpr || 1, 2))
}

/**
 * Replaces the `{index}` / `{width}` placeholders.
 *
 * @param template
 * @param index
 * @param width
 */
export function fillPageUrl(template: string, index: number, width: number): string {
	return template.replace(/\{index\}/g, String(index)).replace(/\{width\}/g, String(Math.max(0, Math.round(width))))
}

/**
 * Builds a `File` from the whole-file URL.
 *
 * @param deps
 * @param url
 * @param fileName
 */
async function fileSource(deps: SourceDeps, url: string, fileName: string): Promise<File> {
	const res = await fetchChecked(deps.fetchFn, url)
	const blob = await res.blob()
	return new File([blob], fileName, { type: blob.type })
}

/**
 * Whether a failure should be retried with the whole file (anything but auth/network problems).
 *
 * @param e
 */
function canFallBack(e: unknown): boolean {
	return !(e instanceof BridgeError && (e.code === 'network' || e.code === 'unauthorized'))
}

/**
 * @param source `open.source` of the host message
 * @param deps
 */
export async function buildSource(source: BookSourceMsg, deps: SourceDeps): Promise<ReaderSource> {
	switch (source.type) {
	case 'file':
		return fileSource(deps, source.url ?? DEFAULT_BOOK_URL, source.fileName)
	case 'remote-zip': {
		const entriesUrl = source.entriesUrl ?? DEFAULT_ENTRIES_URL
		const itemUrl = source.itemUrl ?? DEFAULT_ITEM_URL
		try {
			const { etag, entries } = await (await fetchChecked(deps.fetchFn, entriesUrl)).json() as { etag?: string, entries: { name: string, size: number }[] }
			if (entries?.length > 0) {
				return {
					kind: 'remote-zip',
					name: source.name,
					entries,
					loadEntry: async (name) => {
						const url = itemUrl + encodeURIComponent(name) + (etag ? `&v=${encodeURIComponent(etag)}` : '')
						return await (await fetchChecked(deps.fetchFn, url)).blob()
					},
				}
			}
		} catch (e) {
			if (!canFallBack(e)) {
				throw e
			}
		}
		return fileSource(deps, DEFAULT_BOOK_URL, source.name)
	}
	case 'remote-comic': {
		const pagesUrl = source.pagesUrl ?? DEFAULT_PAGES_URL
		const pageUrl = source.pageUrl ?? DEFAULT_PAGE_URL
		try {
			const { etag, pages } = await (await fetchChecked(deps.fetchFn, pagesUrl)).json() as { etag?: string, pages: { name: string, size: number }[] }
			if (pages?.length > 0) {
				const width = deps.pageWidth()
				return {
					kind: 'remote-comic',
					name: source.name,
					pages,
					loadPage: async (index) => {
						const url = fillPageUrl(pageUrl, index, width) + (etag ? `&v=${encodeURIComponent(etag)}` : '')
						return await (await fetchChecked(deps.fetchFn, url)).blob()
					},
				}
			}
		} catch (e) {
			if (!canFallBack(e)) {
				throw e
			}
		}
		return fileSource(deps, DEFAULT_BOOK_URL, source.name)
	}
	default:
		throw new BridgeError('open-failed', 'Unknown source type')
	}
}
