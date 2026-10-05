/**
 * SPDX-FileCopyrightText: 2026 Felix Kurth
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * Entry of the reader bundle: full-screen reader page for the Android WebView.
 * Protocol: docs/CONTRACTS.md section 6 (implemented in host.ts).
 */
import type { ReaderToHostMsg } from './protocol.ts'

import { createReader } from '../../third_party/nextcloud_ebook_reader/packages/reader-core/index.ts'
import { GestureController, SWIPE_MIN, tapZone, toCss } from './gestures.ts'
import { createHost } from './host.ts'
import { PROTOCOL_VERSION } from './protocol.ts'
import { comicPageWidth } from './source.ts'

interface NativeBridge {
	/** Implemented in Kotlin (@JavascriptInterface): JS -> Kotlin, one JSON message per call */
	postMessage(json: string): void
}

declare global {
	interface Window {
		AndroidBridge?: NativeBridge
		/** Kotlin -> JS: called via evaluateJavascript with a message object */
		EbookReaderHost: { receive(message: unknown): void, version: number }
	}
}

const EINK_ID = 'ebr-eink'

const container = document.getElementById('reader') as HTMLElement
const overlay = document.getElementById('gestures') as HTMLElement
const sectionDocs = new Set<Document>()
let einkOn = false
let einkDark = false

/**
 * Sends a message to Kotlin.
 *
 * @param message
 */
function send(message: ReaderToHostMsg): void {
	window.AndroidBridge?.postMessage(JSON.stringify(message))
}

/**
 * @param dark
 */
function einkCss(dark: boolean): string {
	const [bg, fg] = dark ? ['#000', '#fff'] : ['#fff', '#000']
	return `
html, body { background: ${bg} !important; color: ${fg} !important; }
* { color: ${fg} !important; border-color: ${fg} !important; text-shadow: none !important; box-shadow: none !important; transition: none !important; animation: none !important; scroll-behavior: auto !important; }
a:link, a:visited { color: ${fg} !important; text-decoration: underline !important; }
`
}

/**
 * @param doc
 */
function applyEinkToDoc(doc: Document): void {
	if (!doc.defaultView) {
		sectionDocs.delete(doc)
		return
	}
	let style = doc.getElementById(EINK_ID)
	if (!einkOn) {
		style?.remove()
		return
	}
	if (!style) {
		style = doc.createElement('style')
		style.id = EINK_ID
		doc.head?.append(style)
	}
	style.textContent = einkCss(einkDark)
}

/**
 * @param enabled
 * @param dark
 */
function applyEink(enabled: boolean, dark: boolean): void {
	einkOn = enabled
	einkDark = dark
	document.documentElement.classList.toggle('eink', enabled)
	if (enabled) {
		container.style.background = dark ? '#000' : '#fff'
	}
	sectionDocs.forEach(applyEinkToDoc)
	if (enabled) {
		// reader-core always sets the animated attribute on the paginator
		const view = container.querySelector('foliate-view') as (HTMLElement & { renderer?: HTMLElement }) | null
		view?.renderer?.removeAttribute('animated')
	}
}

/**
 * Scrolls fit-width comic pages that are taller than the screen (the fixed-layout renderer is the
 * scroll container; the overlay above it keeps native scrolling from reaching it).
 *
 * @param dy CSS px on screen, positive = further down the page
 */
function scrollPage(dy: number): void {
	const view = container.querySelector('foliate-view') as (HTMLElement & { renderer?: HTMLElement }) | null
	view?.renderer?.scrollBy(0, dy / gestures.transform.scale)
}

/**
 * Comic zoom: pinch, pan and double tap on the overlay, applied as a CSS transform of the reader
 * container (purely visual, never part of the locator).
 */
const gestures = new GestureController({
	tap: (x) => host.tap(tapZone(x, overlay.clientWidth || window.innerWidth)),
	swipe: (dx) => host.swipe(dx),
	scroll: (dy) => scrollPage(-dy),
	transform: (t, animate) => {
		container.style.transition = animate && !einkOn ? 'transform 150ms ease-out' : 'none'
		container.style.transform = toCss(t)
	},
}, {
	size: () => ({ width: container.clientWidth, height: container.clientHeight }),
	setTimer: (fn, ms) => window.setTimeout(fn, ms),
	clearTimer: (h) => window.clearTimeout(h as number),
})

const host = createHost({
	createReader: (options) => {
		const handle = createReader(container, options)
		// the paginator exists (and has its animated attribute) after the first relocate
		const off = handle.on('relocate', () => {
			if (einkOn) {
				applyEink(true, einkDark)
			}
			off()
		})
		return handle
	},
	send,
	fetchFn: (input, init) => window.fetch(input, init),
	pageWidth: () => comicPageWidth(window.innerWidth, window.innerHeight, window.devicePixelRatio),
	loadLibarchive: async () => {
		const [worker, wasm] = await Promise.all([
			import('libarchive.js/dist/worker-bundle.js?raw'),
			import('libarchive.js/dist/libarchive.wasm?url'),
		])
		return { workerSource: worker.default, wasmUrl: wasm.default }
	},
	onEink: applyEink,
	zoom: { isZoomed: () => gestures.zoomed, reset: () => gestures.reset() },
	onComicMode: (enabled) => {
		overlay.hidden = !enabled
	},
})

overlay.addEventListener('pointerdown', (e) => {
	if (e.pointerType === 'mouse' && e.button !== 0) {
		return
	}
	try {
		overlay.setPointerCapture(e.pointerId)
	} catch {
		// pointer already gone
	}
	gestures.down(e.pointerId, e.clientX, e.clientY, e.timeStamp)
})
overlay.addEventListener('pointermove', (e) => gestures.move(e.pointerId, e.clientX, e.clientY))
overlay.addEventListener('pointerup', (e) => gestures.up(e.pointerId, e.clientX, e.clientY, e.timeStamp))
overlay.addEventListener('pointercancel', (e) => gestures.cancel(e.pointerId))
// mouse wheel (e.g. Chromebooks): scroll fit-width pages as without the overlay
overlay.addEventListener('wheel', (e) => scrollPage(e.deltaY), { passive: true })
window.addEventListener('resize', () => gestures.reset())

/**
 * Swipe handling for fixed-layout EPUB pages (comics use the gesture overlay); host.swipe ignores text books.
 *
 * @param doc
 */
function bindSwipe(doc: Document): void {
	let startX = 0
	let startY = 0
	let tracking = false
	doc.addEventListener('touchstart', (e) => {
		tracking = e.touches.length === 1
		startX = e.touches[0]?.clientX ?? 0
		startY = e.touches[0]?.clientY ?? 0
	}, { passive: true })
	doc.addEventListener('touchend', (e) => {
		if (!tracking) {
			return
		}
		tracking = false
		const t = e.changedTouches[0]
		const dx = (t?.clientX ?? 0) - startX
		const dy = (t?.clientY ?? 0) - startY
		if (Math.abs(dx) > SWIPE_MIN && Math.abs(dx) > Math.abs(dy) * 2) {
			host.swipe(dx)
		}
	}, { passive: true })
}

// foliate-view dispatches `load` (non-bubbling) with the section document: catch it in the capture phase
container.addEventListener('load', (e) => {
	const doc = (e as CustomEvent<{ doc?: Document }>).detail?.doc
	if (doc) {
		sectionDocs.add(doc)
		applyEinkToDoc(doc)
		bindSwipe(doc)
	}
}, true)

// Never open windows (reader-core also cancels foliate's own attempts)
window.open = () => null
window.addEventListener('keydown', (e) => host.handleKey(e.key))

// Script errors outside of promises (e.g. in custom element callbacks) would otherwise leave Kotlin waiting forever
let reportedErrors = 0
const reportUncaught = (message: string): void => {
	if (reportedErrors++ < 5) {
		send({ type: 'error', code: 'reader', message })
	}
}
window.addEventListener('error', (e) => reportUncaught(String(e.message)))
window.addEventListener('unhandledrejection', (e) => reportUncaught(String((e.reason as Error)?.message ?? e.reason)))

window.EbookReaderHost = {
	version: PROTOCOL_VERSION,
	receive: host.receive,
}

send({ type: 'ready', protocol: PROTOCOL_VERSION })
