/**
 * SPDX-FileCopyrightText: 2026 Felix Kurth
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Entry of the reader bundle: full-screen reader page for the Android WebView.
 * Protocol: docs/CONTRACTS.md section 6 (implemented in host.ts).
 */
import type { ReaderToHostMsg } from './protocol.ts'

import { createReader } from '../../third_party/nextcloud_ebook_reader/packages/reader-core/index.ts'
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
const SWIPE_MIN = 60

const container = document.getElementById('reader') as HTMLElement
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
})

/**
 * Swipe handling for fixed-layout pages (comics); host.swipe ignores text books.
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
		// a pinch-zoomed page scrolls instead of turning
		if (Math.abs(dx) > SWIPE_MIN && Math.abs(dx) > Math.abs(dy) * 2 && (window.visualViewport?.scale ?? 1) <= 1.01) {
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
