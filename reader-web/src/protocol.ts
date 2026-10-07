/**
 * SPDX-FileCopyrightText: 2026 Felix Kurth
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * Bridge protocol v1 (mirror of app/src/main/java/.../reader/ReaderBridge.kt, docs/CONTRACTS.md section 6).
 */
import type { ReaderAnnotation, ReaderLocator } from '../../third_party/nextcloud_ebook_reader/packages/reader-core/src/types.ts'

export const PROTOCOL_VERSION = 1

export interface ReaderSettings {
	theme: 'auto' | 'light' | 'dark' | 'sepia'
	fontSize: number
	fontFamily: string
	lineHeight: number
	margin: number
	flow: 'paginated' | 'scrolled'
	maxColumns: number
	comicSpread: 'single' | 'double'
	comicRtl: boolean
	comicZoom: 'fit-page' | 'fit-width'
	einkMode: boolean
}

export const DEFAULT_SETTINGS: ReaderSettings = {
	theme: 'auto',
	fontSize: 100,
	fontFamily: '',
	lineHeight: 1.6,
	margin: 48,
	flow: 'paginated',
	maxColumns: 2,
	comicSpread: 'single',
	comicRtl: false,
	comicZoom: 'fit-page',
	einkMode: false,
}

export type BookSourceMsg =
	| { type: 'file', url?: string, fileName: string }
	| { type: 'remote-zip', name: string, entriesUrl?: string, itemUrl?: string }
	| { type: 'remote-comic', name: string, pagesUrl?: string, pageUrl?: string }

export interface BookRefMsg {
	accountId: string
	fileId: number
	format: string
	title?: string
}

export type HostToReaderMsg =
	| { type: 'open', book: BookRefMsg, source: BookSourceMsg, initialLocator?: ReaderLocator | null, settings?: Partial<ReaderSettings> }
	| { type: 'goTo', locator?: ReaderLocator | null, href?: string | null }
	| { type: 'next' }
	| { type: 'prev' }
	| { type: 'setSettings', settings: Partial<ReaderSettings> }
	/** Highlights to draw (reflowable books); kept across re-opens until `destroy` */
	| { type: 'setAnnotations', annotations: ReaderAnnotation[] }
	| { type: 'clearSelection' }
	| { type: 'destroy' }

export type ErrorCode = 'open-failed' | 'unsupported-format' | 'network' | 'unauthorized' | 'reader'

export interface TocItemMsg {
	label: string
	href: string
	subitems: TocItemMsg[]
}

/** Rectangle in CSS px relative to the reader page */
export interface RectMsg {
	left: number
	top: number
	right: number
	bottom: number
}

export type ReaderToHostMsg =
	| { type: 'ready', protocol: number }
	| { type: 'opened', info: { title?: string, authors: string[], language?: string, isComic: boolean, fixedLayout: boolean, rtl: boolean, pageCount: number, supportsAnnotations: boolean } }
	| { type: 'relocate', locator: ReaderLocator, percentage: number, label?: string, page?: { current: number, total: number } }
	| { type: 'toc', items: TocItemMsg[] }
	| { type: 'externalLink', url: string }
	| { type: 'tap', zone: 'left' | 'center' | 'right' }
	| { type: 'selection', text: string, cfi: string, locator: ReaderLocator, rect: RectMsg }
	| { type: 'selectionClear' }
	| { type: 'annotationClick', id: string, rect: RectMsg }
	| { type: 'error', code: ErrorCode, message: string }
