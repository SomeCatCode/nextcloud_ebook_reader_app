/**
 * SPDX-FileCopyrightText: 2026 Felix Kurth
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import type { ReaderHandle, ReaderOptions } from '../../third_party/nextcloud_ebook_reader/packages/reader-core/src/types.ts'
import type { ReaderToHostMsg } from './protocol.ts'

import { describe, expect, it, vi } from 'vitest'
import { createHost } from './host.ts'

type Listener = (p: never) => void

/**
 * @param opts
 * @param opts.info
 * @param opts.failOpen
 */
function fakeReader(opts: { info?: object, failOpen?: Error } = {}) {
	const listeners = new Map<string, Listener[]>()
	const calls: string[] = []
	const handle = {
		open: vi.fn(async () => {
			if (opts.failOpen) {
				throw opts.failOpen
			}
			emit('relocate', { locator: { href: 'c1.xhtml', locations: { progression: 0.1 } }, percentage: 0.01 })
		}),
		goTo: vi.fn(async () => true),
		next: vi.fn(async () => { calls.push('next') }),
		prev: vi.fn(async () => { calls.push('prev') }),
		setTheme: vi.fn(),
		setTypography: vi.fn(),
		setLayout: vi.fn(async () => {}),
		getToc: vi.fn(() => [{ label: 'One', href: 'c1.xhtml', subitems: [{ label: 'Sub', href: 'c1.xhtml#a' }] }]),
		getInfo: vi.fn(() => ({ title: 'T', authors: ['A'], language: 'en', isComic: false, fixedLayout: false, rtl: false, pageCount: 3, ...opts.info })),
		on: vi.fn((name: string, cb: Listener) => {
			listeners.set(name, [...(listeners.get(name) ?? []), cb])
			return () => {}
		}),
		destroy: vi.fn(),
	}
	/**
	 * @param name
	 * @param payload
	 */
	function emit(name: string, payload: unknown): void {
		listeners.get(name)?.forEach((cb) => cb(payload as never))
	}
	return { handle: handle as unknown as ReaderHandle & typeof handle, emit, calls }
}

/**
 * @param fr
 */
function setup(fr = fakeReader()) {
	const sent: ReaderToHostMsg[] = []
	const created: ReaderOptions[] = []
	const eink: [boolean, boolean][] = []
	const comicMode: boolean[] = []
	const zoom = { zoomed: false, resets: 0 }
	const fetchFn = vi.fn(async () => new Response('PK', { status: 200 })) as unknown as typeof fetch
	const host = createHost({
		createReader: (o) => { created.push(o); return fr.handle },
		send: (m) => sent.push(m),
		fetchFn,
		pageWidth: () => 600,
		onEink: (e, d) => eink.push([e, d]),
		zoom: {
			isZoomed: () => zoom.zoomed,
			reset: () => {
				zoom.resets++
				zoom.zoomed = false
			},
		},
		onComicMode: (e) => comicMode.push(e),
	})
	return { host, sent, created, eink, fr, zoom, comicMode }
}

const OPEN = {
	type: 'open',
	book: { accountId: 'a', fileId: 7, format: 'epub', title: 'Fallback' },
	source: { type: 'file', url: 'https://appassets.androidplatform.net/api/book', fileName: 'x.epub' },
	initialLocator: { href: 'c1.xhtml', locations: { progression: 0.5 } },
	settings: { theme: 'sepia', fontSize: 120 },
}

const flush = (): Promise<void> => new Promise((r) => setTimeout(r, 0))

describe('open', () => {
	it('opens, then sends opened, toc and the buffered relocate in order', async () => {
		const { host, sent, created, fr } = setup()
		host.receive(OPEN)
		await flush()
		expect(created[0].theme).toBe('sepia')
		expect(created[0].typography?.fontSize).toBe(120)
		expect(fr.handle.open).toHaveBeenCalledWith(expect.any(File), 'epub', OPEN.initialLocator)
		expect(sent.map((m) => m.type)).toEqual(['opened', 'toc', 'relocate'])
		expect(sent[0]).toMatchObject({ info: { title: 'T', authors: ['A'], pageCount: 3, rtl: false } })
		expect(sent[1]).toMatchObject({ items: [{ label: 'One', subitems: [{ label: 'Sub', subitems: [] }] }] })
	})

	it('accepts a JSON string message', async () => {
		const { host, sent } = setup()
		host.receive(JSON.stringify(OPEN))
		await flush()
		expect(sent[0].type).toBe('opened')
	})

	it('reports open failures once with a mapped code', async () => {
		const err = Object.assign(new Error('nope'), { name: 'ReaderError', code: 'unsupported' })
		const { host, sent } = setup(fakeReader({ failOpen: err }))
		host.receive(OPEN)
		await flush()
		expect(sent).toEqual([{ type: 'error', code: 'unsupported-format', message: 'nope' }])
	})

	it('reports network errors of the source fetch', async () => {
		const { host, sent } = setup()
		const f = vi.fn(async () => new Response(null, { status: 504, headers: { 'X-Reader-Error': 'network' } }))
		const h = createHost({ createReader: () => { throw new Error('unreachable') }, send: (m) => sent.push(m), fetchFn: f as unknown as typeof fetch, pageWidth: () => 1 })
		h.receive(OPEN)
		await flush()
		expect(sent).toEqual([{ type: 'error', code: 'network', message: 'HTTP 504' }])
		void host
	})

	it('forwards external links and relocates after opening', async () => {
		const { host, sent, fr } = setup()
		host.receive(OPEN)
		await flush()
		sent.length = 0
		fr.emit('external-link', { url: 'https://example.org' })
		fr.emit('relocate', { locator: { href: 'c2.xhtml' }, percentage: 0.5, label: 'Two', page: { current: 2, total: 4 } })
		expect(sent).toEqual([
			{ type: 'externalLink', url: 'https://example.org' },
			{ type: 'relocate', locator: { href: 'c2.xhtml' }, percentage: 0.5, label: 'Two', page: { current: 2, total: 4 } },
		])
	})
})

describe('navigation and settings', () => {
	it('handles goTo, next, prev', async () => {
		const { host, fr } = setup()
		host.receive(OPEN)
		await flush()
		host.receive({ type: 'goTo', href: 'c3.xhtml' })
		host.receive({ type: 'goTo', locator: { href: 'c2.xhtml' } })
		host.receive({ type: 'next' })
		host.receive({ type: 'prev' })
		expect(fr.handle.goTo).toHaveBeenNthCalledWith(1, 'c3.xhtml')
		expect(fr.handle.goTo).toHaveBeenNthCalledWith(2, { href: 'c2.xhtml' })
		expect(fr.calls).toEqual(['next', 'prev'])
	})

	it('applies live settings and e-ink mode', async () => {
		const { host, fr, eink } = setup()
		host.receive(OPEN)
		await flush()
		host.receive({ type: 'setSettings', settings: { theme: 'sepia', einkMode: true, flow: 'scrolled', fontSize: 140 } })
		expect(fr.handle.setTheme).toHaveBeenLastCalledWith('light')
		expect(fr.handle.setTypography).toHaveBeenLastCalledWith(expect.objectContaining({ fontSize: 140 }))
		expect(fr.handle.setLayout).toHaveBeenLastCalledWith(expect.objectContaining({ flow: 'scrolled' }))
		expect(eink.at(-1)).toEqual([true, false])
		host.receive({ type: 'setSettings', settings: { theme: 'dark' } })
		expect(fr.handle.setTheme).toHaveBeenLastCalledWith('dark')
		expect(eink.at(-1)).toEqual([true, true])
	})

	it('destroy tears the reader down', async () => {
		const { host, fr } = setup()
		host.receive(OPEN)
		await flush()
		host.receive({ type: 'destroy' })
		expect(fr.handle.destroy).toHaveBeenCalled()
		host.receive({ type: 'next' })
		expect(fr.calls).toEqual([])
	})
})

describe('taps and keys', () => {
	it('turns pages on side taps and reports the center', async () => {
		const { host, sent, fr } = setup()
		host.receive(OPEN)
		await flush()
		sent.length = 0
		fr.emit('tap', { zone: 'right' })
		fr.emit('tap', { zone: 'left' })
		fr.emit('tap', { zone: 'center' })
		expect(fr.calls).toEqual(['next', 'prev'])
		expect(sent).toEqual([{ type: 'tap', zone: 'center' }])
	})

	it('mirrors left/right for rtl books and swipes only for fixed layout', async () => {
		const { host, fr } = setup(fakeReader({ info: { isComic: true, rtl: true } }))
		host.receive(OPEN)
		await flush()
		fr.emit('tap', { zone: 'right' })
		host.handleKey('ArrowLeft')
		host.swipe(-100)
		expect(fr.calls).toEqual(['prev', 'next', 'prev'])
		const text = setup()
		text.host.receive(OPEN)
		await flush()
		text.host.swipe(-100)
		expect(text.fr.calls).toEqual([])
	})

	it('every tap is the center in scrolled text mode', async () => {
		const { host, sent, fr } = setup()
		host.receive({ ...OPEN, settings: { flow: 'scrolled' } })
		await flush()
		sent.length = 0
		fr.emit('tap', { zone: 'left' })
		expect(sent).toEqual([{ type: 'tap', zone: 'center' }])
		expect(fr.calls).toEqual([])
	})

	it('maps keys', async () => {
		const { host, fr } = setup()
		host.receive(OPEN)
		await flush()
		for (const k of ['ArrowRight', 'PageUp', ' ', 'ArrowUp', 'x']) {
			host.handleKey(k)
		}
		expect(fr.calls).toEqual(['next', 'prev', 'next', 'prev'])
	})
})

describe('comic zoom', () => {
	const COMIC = { ...OPEN, book: { ...OPEN.book, format: 'cbz' } }

	it('enables the gesture layer for comics only and disables it on destroy', async () => {
		const comic = setup(fakeReader({ info: { isComic: true, fixedLayout: true } }))
		comic.host.receive(COMIC)
		await flush()
		expect(comic.comicMode.at(-1)).toBe(true)
		comic.host.receive({ type: 'destroy' })
		expect(comic.comicMode.at(-1)).toBe(false)
		const text = setup()
		text.host.receive(OPEN)
		await flush()
		expect(text.comicMode.at(-1)).toBe(false)
	})

	it('while zoomed, taps only toggle the bars and swipes do not turn', async () => {
		const { host, sent, fr, zoom } = setup(fakeReader({ info: { isComic: true, fixedLayout: true } }))
		host.receive(COMIC)
		await flush()
		sent.length = 0
		zoom.zoomed = true
		host.tap('left')
		host.tap('right')
		host.swipe(-100)
		expect(fr.calls).toEqual([])
		expect(sent).toEqual([{ type: 'tap', zone: 'center' }, { type: 'tap', zone: 'center' }])
		expect(zoom.zoomed).toBe(true)
	})

	it('every page turn or jump resets the zoom first', async () => {
		const { host, fr, zoom } = setup(fakeReader({ info: { isComic: true, fixedLayout: true } }))
		host.receive(COMIC)
		await flush()
		for (const turn of [
			() => host.receive({ type: 'next' }),
			() => host.receive({ type: 'prev' }),
			() => host.handleKey('ArrowRight'),
			() => host.handleKey('PageUp'),
			() => host.receive({ type: 'goTo', href: 'p3' }),
			() => host.receive({ type: 'setSettings', settings: { comicZoom: 'fit-width' } }),
		]) {
			zoom.zoomed = true
			turn()
			expect(zoom.zoomed).toBe(false)
		}
		expect(fr.calls).toEqual(['next', 'prev', 'next', 'prev'])
		expect(fr.handle.setLayout).toHaveBeenLastCalledWith(expect.objectContaining({ comicZoom: 'fit-width' }))
	})

	it('a tap zone turns the page at fit zoom', async () => {
		const { host, fr } = setup(fakeReader({ info: { isComic: true, fixedLayout: true } }))
		host.receive(COMIC)
		await flush()
		host.tap('right')
		host.tap('left')
		expect(fr.calls).toEqual(['next', 'prev'])
	})
})
