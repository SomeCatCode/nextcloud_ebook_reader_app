/**
 * SPDX-FileCopyrightText: 2026 Felix Kurth
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * Read aloud: foliate's TTS splits the open section into blocks (paragraphs) and every block into
 * sentences, as SSML with `<mark name>` before each sentence and the language of the text
 * (`xml:lang` from the book's `lang` attributes). Here a block becomes plain text segments for the
 * Android TextToSpeech engine; when Android starts a segment the page highlights that sentence and
 * turns to it. Blocks continue across sections until the end of the book.
 */

export interface TtsSegment {
	/** foliate mark of the sentence (null for text before the first mark) */
	mark: string | null
	text: string
}

export interface TtsBlock {
	/** BCP 47 language of the text, if the book says so */
	lang?: string
	segments: TtsSegment[]
}

/** What the host needs; all methods return the next block to speak, or null at the end of the book. */
export interface TtsApi {
	/** From the start of the visible page. */
	start: () => Promise<TtsBlock | null>
	next: () => Promise<TtsBlock | null>
	prev: () => Promise<TtsBlock | null>
	/** The current block again, from the last spoken sentence. */
	resume: () => Promise<TtsBlock | null>
	/** Android started speaking the segment with this mark. */
	mark: (name: string) => void
	stop: () => void
}

const ENTITIES: Record<string, string> = { amp: '&', lt: '<', gt: '>', quot: '"', apos: '\'' }

/**
 * @param text XML text with entities
 */
function decode(text: string): string {
	return text.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (all, e: string) => {
		if (e[0] === '#') {
			const code = e[1] === 'x' || e[1] === 'X' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10)
			return Number.isFinite(code) ? String.fromCodePoint(code) : all
		}
		return ENTITIES[e.toLowerCase()] ?? all
	})
}

/**
 * Turns one SSML block of foliate's TTS (serialized XML) into segments: a new segment starts at every
 * `<mark name>`, `<break/>` is a space, every other tag only contributes its text. Empty segments
 * are dropped; null when nothing is left to say.
 *
 * @param ssml
 */
export function ssmlToBlock(ssml: string | null | undefined): TtsBlock | null {
	if (!ssml) {
		return null
	}
	const lang = /<speak\b[^>]*\bxml:lang="([^"]+)"/.exec(ssml)?.[1]
	const segments: TtsSegment[] = []
	let current: TtsSegment = { mark: null, text: '' }
	const flush = (): void => {
		const text = current.text.replace(/\s+/g, ' ').trim()
		if (text) {
			segments.push({ mark: current.mark, text })
		}
	}
	const token = /<[^>]*>|[^<]+/g
	let m: RegExpExecArray | null
	while ((m = token.exec(ssml)) !== null) {
		const part = m[0]
		if (part[0] !== '<') {
			current.text += decode(part)
			continue
		}
		const mark = /^<(?:[\w-]+:)?mark\b[^>]*\bname="([^"]*)"/.exec(part)
		if (mark) {
			flush()
			current = { mark: decode(mark[1] ?? ''), text: '' }
		} else if (/^<(?:[\w-]+:)?break\b/.test(part)) {
			current.text += ' '
		}
	}
	flush()
	return segments.length ? { lang: lang || undefined, segments } : null
}

/** The parts of foliate's `<foliate-view>` used here. */
export interface FoliateTtsView {
	initTTS: (granularity?: string, highlight?: (range: Range) => void) => Promise<void>
	tts: {
		start: () => string | undefined
		next: (paused?: boolean) => string | undefined
		prev: (paused?: boolean) => string | undefined
		resume: () => string | undefined
		from: (range: Range) => string | undefined
		setMark: (mark: string) => void
	} | null
	lastLocation?: { range?: Range } | null
	book?: { sections?: { linear?: string }[] }
	renderer?: {
		getContents: () => { index: number, doc: Document }[]
		goTo: (target: { index: number, anchor?: number | (() => number) }) => Promise<void>
		scrollToAnchor?: (anchor: Range, select?: boolean) => Promise<void>
	}
}

const HIGHLIGHT = 'ebr-tts'

/**
 * @param getView the open book's view, null while none is open
 * @param einkOn black underline instead of a colored background
 */
export function createTts(getView: () => FoliateTtsView | null, einkOn: () => boolean): TtsApi {
	const painted = new Set<Document>()

	const clear = (): void => {
		for (const doc of painted) {
			try {
				(doc.defaultView as (Window & { CSS?: { highlights?: Map<string, unknown> } }) | null)?.CSS?.highlights?.delete(HIGHLIGHT)
			} catch {
				// section document already gone
			}
		}
		painted.clear()
	}

	const paint = (range: Range): void => {
		const doc = range.startContainer.ownerDocument
		const win = doc?.defaultView as (Window & { CSS?: { highlights?: Map<string, unknown> }, Highlight?: new (r: Range) => unknown }) | null
		if (!doc || !win?.CSS?.highlights || !win.Highlight) {
			return // no CSS Custom Highlight API: the page still turns to the sentence
		}
		let style = doc.getElementById(HIGHLIGHT)
		if (!style) {
			style = doc.createElement('style')
			style.id = HIGHLIGHT
			doc.head?.append(style)
		}
		style.textContent = einkOn()
			? `::highlight(${HIGHLIGHT}) { text-decoration: underline 2px; }`
			: `::highlight(${HIGHLIGHT}) { background-color: rgba(255, 196, 0, 0.4); }`
		clear()
		win.CSS.highlights.set(HIGHLIGHT, new win.Highlight(range))
		painted.add(doc)
	}

	/** The view with TTS for the shown section (foliate re-creates it when the section changed). */
	const ready = async (): Promise<FoliateTtsView | null> => {
		const view = getView()
		if (!view) {
			return null
		}
		await view.initTTS('sentence', (range) => {
			paint(range)
			void view.renderer?.scrollToAnchor?.(range, true)
		})
		return view.tts ? view : null
	}

	/** Next readable section after the shown one; false at the end of the book. */
	const nextSection = async (view: FoliateTtsView): Promise<boolean> => {
		const sections = view.book?.sections ?? []
		const index = view.renderer?.getContents()[0]?.index ?? -1
		for (let i = index + 1; i < sections.length; i++) {
			if (sections[i]?.linear !== 'no') {
				await view.renderer?.goTo({ index: i })
				return true
			}
		}
		return false
	}

	/** First block with text from the next sections on. */
	const fromNextSections = async (view: FoliateTtsView): Promise<TtsBlock | null> => {
		while (await nextSection(view)) {
			const fresh = await ready()
			const block = ssmlToBlock(fresh?.tts?.start())
			if (block) {
				return block
			}
		}
		return null
	}

	return {
		async start() {
			const view = await ready()
			if (!view?.tts) {
				return null
			}
			let ssml: string | undefined
			const visible = view.lastLocation?.range
			try {
				ssml = visible ? view.tts.from(visible) : view.tts.start()
			} catch {
				ssml = view.tts.start()
			}
			return ssmlToBlock(ssml) ?? await this.next()
		},
		async next() {
			const view = await ready()
			if (!view?.tts) {
				return null
			}
			// empty blocks (images only) are skipped
			for (let ssml = view.tts.next(); ssml !== undefined; ssml = view.tts.next()) {
				const block = ssmlToBlock(ssml)
				if (block) {
					return block
				}
			}
			return fromNextSections(view)
		},
		async prev() {
			const view = await ready()
			if (!view?.tts) {
				return null
			}
			for (let ssml = view.tts.prev(); ssml !== undefined; ssml = view.tts.prev()) {
				const block = ssmlToBlock(ssml)
				if (block) {
					return block
				}
			}
			// already at the first paragraph of the section: read it again
			return ssmlToBlock(view.tts.start())
		},
		async resume() {
			const view = await ready()
			return ssmlToBlock(view?.tts?.resume()) ?? (view ? this.next() : null)
		},
		mark(name) {
			try {
				getView()?.tts?.setMark(name)
			} catch {
				// the section changed meanwhile
			}
		},
		stop() {
			clear()
		},
	}
}
