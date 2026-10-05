/**
 * SPDX-FileCopyrightText: 2026 Felix Kurth
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Touch gestures of comic pages: pinch-to-zoom, panning, double-tap zoom, taps and swipes.
 *
 * The zoom is a CSS transform of the reader container (`translate(x, y) scale(s)`, origin top left),
 * so it is purely visual: reader-core, the page layout and the saved locator never see it. The
 * controller is DOM free (pointer positions in, callbacks out) so it can be unit tested; main.ts
 * feeds it the pointer events of a transparent overlay above the reader.
 */

export interface ZoomTransform {
	scale: number
	x: number
	y: number
}

export interface Size {
	width: number
	height: number
}

export const FIT: ZoomTransform = Object.freeze({ scale: 1, x: 0, y: 0 })
export const MAX_SCALE = 5
export const DOUBLE_TAP_SCALE = 2.5
/** Minimal horizontal movement of a page-turning swipe (CSS px). */
export const SWIPE_MIN = 60
/** Time a single tap waits for a second one (ms); also the double-tap window. */
export const DOUBLE_TAP_MS = 250
/** Movement up to which a touch still counts as a tap (CSS px). */
const TAP_SLOP = 10
/** Maximal distance between the two taps of a double tap (CSS px). */
const DOUBLE_TAP_SLOP = 40
/** Longer presses are no taps. */
const LONG_PRESS_MS = 600
/** A pinch ending below this scale snaps back to fit. */
const SNAP_TO_FIT = 1.05

/**
 * @param t
 */
export function isZoomed(t: ZoomTransform): boolean {
	return t.scale > 1.001
}

/**
 * Limits the scale to 1..max and the translation so that the page always covers the viewport.
 *
 * @param t
 * @param size
 * @param max
 */
export function clampTransform(t: ZoomTransform, size: Size, max = MAX_SCALE): ZoomTransform {
	const scale = Math.min(Math.max(t.scale, 1), max)
	const clampAxis = (v: number, len: number): number => Math.min(0, Math.max(len * (1 - scale), v))
	return { scale, x: clampAxis(t.x, size.width), y: clampAxis(t.y, size.height) }
}

/**
 * Zooms to `scale` keeping the content under the focus point (viewport coordinates) in place.
 *
 * @param t
 * @param scale
 * @param fx
 * @param fy
 * @param size
 */
export function zoomAt(t: ZoomTransform, scale: number, fx: number, fy: number, size: Size): ZoomTransform {
	const lx = (fx - t.x) / t.scale
	const ly = (fy - t.y) / t.scale
	return clampTransform({ scale, x: fx - lx * scale, y: fy - ly * scale }, size)
}

/** Width share of the left/right tap zones (same as reader-core). */
const SIDE_ZONE = 0.3

/**
 * @param x viewport x of the tap
 * @param width viewport width
 */
export function tapZone(x: number, width: number): 'left' | 'center' | 'right' {
	const f = width > 0 ? x / width : 0.5
	return f < SIDE_ZONE ? 'left' : (f > 1 - SIDE_ZONE ? 'right' : 'center')
}

/**
 * CSS `transform` value; empty at fit so the page renders exactly as without zoom.
 *
 * @param t
 */
export function toCss(t: ZoomTransform): string {
	return isZoomed(t) ? `translate(${t.x}px, ${t.y}px) scale(${t.scale})` : ''
}

export interface GestureSink {
	/** Single tap (after the double-tap window), viewport coordinates. */
	tap: (x: number, y: number) => void
	/** Horizontal swipe at fit zoom: `dx < 0` = finger moved left. */
	swipe: (dx: number) => void
	/** Vertical drag (CSS px of finger movement) the zoom could not absorb, e.g. for fit-width pages. */
	scroll: (dy: number) => void
	/** New zoom transform; `animate` for discrete jumps (double tap, snap back). */
	transform: (t: ZoomTransform, animate: boolean) => void
}

export interface GestureEnv {
	size: () => Size
	setTimer: (fn: () => void, ms: number) => unknown
	clearTimer: (handle: unknown) => void
}

interface Point {
	x: number
	y: number
}

type Mode = 'idle' | 'press' | 'drag' | 'pinch'

/**
 * @param a
 * @param b
 */
function dist(a: Point, b: Point): number {
	return Math.hypot(a.x - b.x, a.y - b.y)
}

export class GestureController {
	#t: ZoomTransform = FIT
	#pointers = new Map<number, Point>()
	#mode: Mode = 'idle'
	/** first pointer of the current gesture */
	#start: Point & { time: number, zoomed: boolean } = { x: 0, y: 0, time: 0, zoomed: false }
	#last: Point = { x: 0, y: 0 }
	/** a second pointer was down during this gesture */
	#multi = false
	#pinch: { t0: ZoomTransform, d0: number, m0: Point } | null = null
	/** tap waiting for a possible second tap */
	#pending: (Point & { timer: unknown }) | null = null
	/** the pending tap's timer was stopped because a second touch started close to it */
	#held = false

	private readonly sink: GestureSink
	private readonly env: GestureEnv

	constructor(sink: GestureSink, env: GestureEnv) {
		this.sink = sink
		this.env = env
	}

	get transform(): ZoomTransform {
		return this.#t
	}

	get zoomed(): boolean {
		return isZoomed(this.#t)
	}

	/** Back to fit (page turn, resize, new book). */
	reset(animate = false): void {
		if (this.#t === FIT) {
			return
		}
		this.#t = FIT
		this.#pinch = null
		if (this.#mode === 'pinch') {
			this.#mode = 'idle'
		}
		this.sink.transform(FIT, animate)
	}

	down(id: number, x: number, y: number, time: number): void {
		if (this.#pointers.size >= 2) {
			return
		}
		this.#pointers.set(id, { x, y })
		if (this.#pointers.size === 1) {
			this.#mode = 'press'
			this.#multi = false
			this.#start = { x, y, time, zoomed: this.zoomed }
			this.#last = { x, y }
			if (this.#pending) {
				if (dist(this.#pending, { x, y }) <= DOUBLE_TAP_SLOP) {
					this.env.clearTimer(this.#pending.timer)
					this.#held = true
				} else {
					this.#flushPending()
				}
			}
			return
		}
		// second finger: pinch. A tap held for a double tap is dropped (no accidental page turn).
		this.#dropPending()
		this.#multi = true
		this.#mode = 'pinch'
		const [a, b] = [...this.#pointers.values()] as [Point, Point]
		this.#pinch = { t0: this.#t, d0: Math.max(dist(a, b), 1), m0: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 } }
	}

	move(id: number, x: number, y: number): void {
		const p = this.#pointers.get(id)
		if (!p) {
			return
		}
		p.x = x
		p.y = y
		if (this.#mode === 'press') {
			if (dist(this.#start, p) <= TAP_SLOP) {
				return
			}
			// the touch became a drag: a held first tap was a real tap after all
			if (this.#held) {
				this.#flushPending()
			}
			this.#mode = 'drag'
		}
		if (this.#mode === 'drag') {
			const dx = x - this.#last.x
			const dy = y - this.#last.y
			this.#last = { x, y }
			this.#pan(dx, dy)
		} else if (this.#mode === 'pinch' && this.#pinch && this.#pointers.size === 2) {
			const [a, b] = [...this.#pointers.values()] as [Point, Point]
			const { t0, d0, m0 } = this.#pinch
			const scale = Math.min(Math.max(t0.scale * dist(a, b) / d0, 1), MAX_SCALE)
			const m = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }
			// the content point under the starting midpoint follows the current midpoint
			const lx = (m0.x - t0.x) / t0.scale
			const ly = (m0.y - t0.y) / t0.scale
			this.#set(clampTransform({ scale, x: m.x - lx * scale, y: m.y - ly * scale }, this.env.size()), false)
		}
	}

	up(id: number, x: number, y: number, time: number): void {
		if (!this.#pointers.has(id)) {
			return
		}
		this.#pointers.delete(id)
		if (this.#mode === 'pinch') {
			const rest = [...this.#pointers.values()][0]
			if (rest) {
				// one finger stays: continue as a pan from where it is
				this.#mode = 'drag'
				this.#pinch = null
				this.#last = { ...rest }
			} else {
				this.#endPinch()
			}
			return
		}
		if (this.#pointers.size > 0) {
			return
		}
		const mode = this.#mode
		this.#mode = 'idle'
		if (this.#multi) {
			this.#endPinch()
			return
		}
		if (mode === 'press' && time - this.#start.time < LONG_PRESS_MS) {
			if (this.#held && this.#pending) {
				this.#dropPending()
				this.#doubleTap(x, y)
			} else {
				this.#pending = { x, y, timer: this.env.setTimer(() => this.#flushPending(), DOUBLE_TAP_MS) }
			}
			return
		}
		if (this.#held) {
			this.#flushPending()
		}
		if (mode === 'drag' && !this.#start.zoomed && !this.zoomed) {
			const dx = x - this.#start.x
			const dy = y - this.#start.y
			if (Math.abs(dx) > SWIPE_MIN && Math.abs(dx) > Math.abs(dy) * 2) {
				this.sink.swipe(dx)
			}
		}
	}

	cancel(id: number): void {
		this.#pointers.delete(id)
		if (this.#pointers.size === 0) {
			this.#mode = 'idle'
			this.#pinch = null
			if (this.#held) {
				this.#dropPending()
			}
		}
	}

	#endPinch(): void {
		this.#pinch = null
		this.#mode = 'idle'
		if (this.#t.scale < SNAP_TO_FIT) {
			this.reset(true)
		}
	}

	#pan(dx: number, dy: number): void {
		if (!this.zoomed) {
			this.sink.scroll(dy)
			return
		}
		const wanted = { ...this.#t, x: this.#t.x + dx, y: this.#t.y + dy }
		const next = clampTransform(wanted, this.env.size())
		this.#set(next, false)
		const rest = wanted.y - next.y
		if (Math.abs(rest) > 0.5) {
			this.sink.scroll(rest)
		}
	}

	#doubleTap(x: number, y: number): void {
		if (this.zoomed) {
			this.reset(true)
		} else {
			this.#set(zoomAt(this.#t, DOUBLE_TAP_SCALE, x, y, this.env.size()), true)
		}
	}

	#set(t: ZoomTransform, animate: boolean): void {
		if (t.scale === this.#t.scale && t.x === this.#t.x && t.y === this.#t.y) {
			return
		}
		this.#t = isZoomed(t) ? t : FIT
		this.sink.transform(this.#t, animate)
	}

	#flushPending(): void {
		const p = this.#pending
		this.#dropPending()
		if (p) {
			this.sink.tap(p.x, p.y)
		}
	}

	#dropPending(): void {
		if (this.#pending) {
			this.env.clearTimer(this.#pending.timer)
		}
		this.#pending = null
		this.#held = false
	}
}
