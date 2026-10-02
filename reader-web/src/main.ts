/**
 * SPDX-FileCopyrightText: 2026 Felix Kurth
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Entry of the reader bundle (phase 0: placeholder).
 * W-READER implements the protocol in docs/CONTRACTS.md section "Reader bridge".
 */
import { createReader } from '../../third_party/nextcloud_ebook_reader/packages/reader-core/index.ts'

interface NativeBridge {
	/** Implemented in Kotlin (@JavascriptInterface): JS -> Kotlin, one JSON message per call */
	postMessage(json: string): void
}

declare global {
	interface Window {
		AndroidBridge?: NativeBridge
		/** Kotlin -> JS: called via evaluateJavascript with a JSON message */
		EbookReaderHost: { receive(message: unknown): void, version: number }
	}
}

const container = document.getElementById('reader') as HTMLElement
const reader = createReader(container, { theme: 'auto' })

/**
 * Sends a message to Kotlin.
 *
 * @param message JSON-serialisable message with a `type`
 */
function send(message: { type: string, [key: string]: unknown }): void {
	window.AndroidBridge?.postMessage(JSON.stringify(message))
}

reader.on('relocate', ({ locator, percentage }) => send({ type: 'relocate', locator, percentage }))
reader.on('external-link', ({ url }) => send({ type: 'externalLink', url }))
reader.on('error', (err) => send({ type: 'error', code: 'reader', message: String(err?.message ?? err) }))

window.EbookReaderHost = {
	version: 1,
	receive(message: unknown): void {
		// Placeholder: the real handling of open/goTo/next/prev/setSettings follows in phase 4.
		void message
	},
}

send({ type: 'ready', protocol: 1 })
