/**
 * SPDX-FileCopyrightText: 2026 Felix Kurth
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * JS side of the Android bridge (docs/CONTRACTS.md section 6): turns host messages into reader-core
 * calls and reader events into bridge messages. DOM free so it can be unit tested with a fake reader.
 */
import type {
	ReaderHandle,
	ReaderLayout,
	ReaderLocator,
	ReaderOptions,
	ReaderThemeName,
	ReaderTypography,
} from '../../third_party/nextcloud_ebook_reader/packages/reader-core/src/types.ts'
import type { HostToReaderMsg, ReaderSettings, ReaderToHostMsg, TocItemMsg } from './protocol.ts'

import { toBridgeError } from './errors.ts'
import { DEFAULT_SETTINGS } from './protocol.ts'
import { buildSource } from './source.ts'

export interface HostDeps {
	createReader: (options: ReaderOptions) => ReaderHandle
	send: (message: ReaderToHostMsg) => void
	fetchFn: typeof fetch
	/** Comic page width in device pixels */
	pageWidth: () => number
	loadLibarchive?: ReaderOptions['loadLibarchive']
	/** E-ink mode changed: `dark` = black background with white text */
	onEink?: (enabled: boolean, dark: boolean) => void
}

export interface ReaderHostApi {
	receive: (message: unknown) => void
	/** Keyboard navigation (also used for the reader's own key events). */
	handleKey: (key: string) => void
	/** Swipe gesture of fixed-layout pages: `dx < 0` = finger moved left. */
	swipe: (dx: number) => void
}

/**
 * @param s
 */
function toTheme(s: ReaderSettings): ReaderThemeName {
	// E-ink: pure black on white (or white on black for the dark theme), no sepia/auto
	return s.einkMode ? (s.theme === 'dark' ? 'dark' : 'light') : s.theme
}

/**
 * @param s
 */
function toTypography(s: ReaderSettings): ReaderTypography {
	return { fontSize: s.fontSize, fontFamily: s.fontFamily, lineHeight: s.lineHeight }
}

/**
 * @param s
 */
function toLayout(s: ReaderSettings): ReaderLayout {
	return {
		flow: s.flow,
		maxColumns: s.maxColumns,
		margin: s.margin,
		comicSpread: s.comicSpread,
		comicRtl: s.comicRtl,
		comicZoom: s.comicZoom,
	}
}

/**
 * @param items
 */
function mapToc(items: { label: string, href: string, subitems?: unknown[] }[]): TocItemMsg[] {
	return items.map((i) => ({
		label: i.label,
		href: i.href,
		subitems: i.subitems?.length ? mapToc(i.subitems as typeof items) : [],
	}))
}

/**
 * @param deps
 */
export function createHost(deps: HostDeps): ReaderHostApi {
	let reader: ReaderHandle | null = null
	let settings: ReaderSettings = { ...DEFAULT_SETTINGS }
	let token = 0
	let opening = false
	let pendingRelocate: ReaderToHostMsg | null = null
	let unsubs: (() => void)[] = []

	/**
	 *
	 */
	function teardown(): void {
		token++
		opening = false
		pendingRelocate = null
		unsubs.forEach((u) => u())
		unsubs = []
		try {
			reader?.destroy()
		} catch {
			// ignore
		}
		reader = null
	}

	/**
	 *
	 */
	function isRtl(): boolean {
		return reader?.getInfo()?.rtl ?? false
	}

	/**
	 *
	 */
	function isScrolledText(): boolean {
		return settings.flow === 'scrolled' && !reader?.getInfo()?.isComic
	}

	/**
	 *
	 */
	function goLeft(): void {
		void (isRtl() ? reader?.next() : reader?.prev())
	}

	/**
	 *
	 */
	function goRight(): void {
		void (isRtl() ? reader?.prev() : reader?.next())
	}

	/**
	 * @param zone
	 */
	function onTap(zone: 'left' | 'center' | 'right'): void {
		if (zone === 'center' || isScrolledText()) {
			deps.send({ type: 'tap', zone: 'center' })
		} else if (zone === 'left') {
			goLeft()
		} else {
			goRight()
		}
	}

	/**
	 * @param key
	 */
	function handleKey(key: string): void {
		switch (key) {
		case 'ArrowLeft':
			goLeft()
			break
		case 'ArrowRight':
			goRight()
			break
		case 'PageDown':
		case ' ':
			void reader?.next()
			break
		case 'ArrowDown':
			if (!isScrolledText()) {
				void reader?.next()
			}
			break
		case 'PageUp':
			void reader?.prev()
			break
		case 'ArrowUp':
			if (!isScrolledText()) {
				void reader?.prev()
			}
			break
		default:
			break
		}
	}

	/**
	 * @param dx
	 */
	function swipe(dx: number): void {
		const info = reader?.getInfo()
		// text sections: the paginator handles swipes natively
		if (!info || !(info.isComic || info.fixedLayout)) {
			return
		}
		if (dx < 0) {
			goRight()
		} else if (dx > 0) {
			goLeft()
		}
	}

	/**
	 * @param patch
	 * @param initial true while building the reader (nothing to apply yet)
	 */
	function applySettings(patch: Partial<ReaderSettings>, initial = false): void {
		settings = { ...settings, ...patch }
		deps.onEink?.(settings.einkMode, toTheme(settings) === 'dark')
		if (initial || !reader) {
			return
		}
		reader.setTheme(toTheme(settings))
		reader.setTypography(toTypography(settings))
		reader.setLayout(toLayout(settings)).catch((e) => deps.send({ type: 'error', ...toBridgeError(e) }))
	}

	/**
	 * @param msg
	 */
	async function open(msg: Extract<HostToReaderMsg, { type: 'open' }>): Promise<void> {
		teardown()
		const mine = token
		opening = true
		applySettings({ ...DEFAULT_SETTINGS, ...msg.settings }, true)
		try {
			const source = await buildSource(msg.source, { fetchFn: deps.fetchFn, pageWidth: deps.pageWidth })
			if (mine !== token) {
				return
			}
			const r = deps.createReader({
				theme: toTheme(settings),
				typography: toTypography(settings),
				layout: toLayout(settings),
				loadLibarchive: deps.loadLibarchive,
			})
			reader = r
			unsubs.push(
				r.on('relocate', ({ locator, percentage, label, page }) => {
					const m: ReaderToHostMsg = { type: 'relocate', locator, percentage, label, page }
					if (opening) {
						pendingRelocate = m
					} else {
						deps.send(m)
					}
				}),
				r.on('tap', ({ zone }) => onTap(zone)),
				r.on('key', ({ key }) => handleKey(key)),
				// foliate's own window.open is always cancelled by reader-core: the URL goes to Kotlin
				r.on('external-link', ({ url }) => deps.send({ type: 'externalLink', url })),
				// errors while opening are reported once, through the rejected open()
				r.on('error', (err) => {
					if (!opening) {
						deps.send({ type: 'error', ...toBridgeError(err) })
					}
				}),
			)
			await r.open(source, msg.book.format as never, (msg.initialLocator ?? null) as ReaderLocator | null)
			if (mine !== token) {
				return
			}
			const info = r.getInfo()
			deps.send({
				type: 'opened',
				info: {
					title: info?.title || msg.book.title || undefined,
					authors: info?.authors ?? [],
					language: info?.language,
					isComic: info?.isComic ?? false,
					fixedLayout: info?.fixedLayout ?? false,
					rtl: info?.rtl ?? false,
					pageCount: info?.pageCount ?? 0,
				},
			})
			deps.send({ type: 'toc', items: mapToc(r.getToc()) })
			opening = false
			if (pendingRelocate) {
				deps.send(pendingRelocate)
				pendingRelocate = null
			}
		} catch (e) {
			if (mine !== token) {
				return
			}
			opening = false
			deps.send({ type: 'error', ...toBridgeError(e) })
		}
	}

	/**
	 * @param raw
	 */
	function receive(raw: unknown): void {
		let msg: HostToReaderMsg
		try {
			msg = (typeof raw === 'string' ? JSON.parse(raw) : raw) as HostToReaderMsg
		} catch (e) {
			deps.send({ type: 'error', ...toBridgeError(e) })
			return
		}
		try {
			switch (msg?.type) {
			case 'open':
				void open(msg)
				break
			case 'goTo': {
				const target = msg.locator ?? msg.href
				if (target && reader) {
					reader.goTo(target).catch((e) => deps.send({ type: 'error', ...toBridgeError(e) }))
				}
				break
			}
			case 'next':
				void reader?.next()
				break
			case 'prev':
				void reader?.prev()
				break
			case 'setSettings':
				applySettings(msg.settings ?? {})
				break
			case 'destroy':
				teardown()
				break
			default:
				break
			}
		} catch (e) {
			deps.send({ type: 'error', ...toBridgeError(e) })
		}
	}

	return { receive, handleKey, swipe }
}
