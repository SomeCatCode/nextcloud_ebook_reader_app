/**
 * SPDX-FileCopyrightText: 2026 Felix Kurth
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */
import type { ZoomTransform } from './gestures.ts'

import { describe, expect, it } from 'vitest'
import { clampTransform, DOUBLE_TAP_MS, DOUBLE_TAP_SCALE, FIT, GestureController, MAX_SCALE, tapZone, toCss, zoomAt } from './gestures.ts'

const SIZE = { width: 400, height: 800 }

/**
 *
 */
function setup() {
	const events: string[] = []
	const transforms: [ZoomTransform, boolean][] = []
	let timers: { fn: () => void, at: number, id: number }[] = []
	let now = 0
	let nextId = 1
	const g = new GestureController({
		tap: (x, y) => events.push(`tap ${x},${y}`),
		swipe: (dx) => events.push(`swipe ${dx}`),
		scroll: (dy) => events.push(`scroll ${dy}`),
		transform: (t, animate) => transforms.push([t, animate]),
	}, {
		size: () => SIZE,
		setTimer: (fn, ms) => {
			const id = nextId++
			timers.push({ fn, at: now + ms, id })
			return id
		},
		clearTimer: (h) => {
			timers = timers.filter((t) => t.id !== h)
		},
	})
	const advance = (ms: number): void => {
		now += ms
		const due = timers.filter((t) => t.at <= now)
		timers = timers.filter((t) => t.at > now)
		due.forEach((t) => t.fn())
	}
	const tap = (x: number, y: number, id = 1): void => {
		g.down(id, x, y, now)
		advance(50)
		g.up(id, x, y, now)
	}
	/** two-finger pinch from distance d0 to d1 around (cx, cy) */
	const pinch = (cx: number, cy: number, d0: number, d1: number): void => {
		g.down(1, cx - d0 / 2, cy, now)
		g.down(2, cx + d0 / 2, cy, now)
		g.move(1, cx - d1 / 2, cy)
		g.move(2, cx + d1 / 2, cy)
		g.up(1, cx - d1 / 2, cy, now)
		g.up(2, cx + d1 / 2, cy, now)
	}
	return { g, events, transforms, advance, tap, pinch }
}

describe('zoom math', () => {
	it('keeps the focus point in place and the page covering the viewport', () => {
		const t = zoomAt(FIT, 2, 100, 200, SIZE)
		expect(t).toEqual({ scale: 2, x: -100, y: -200 })
		// content under (100, 200) is still under it
		expect((100 - t.x) / t.scale).toBe(100)
		expect(clampTransform({ scale: 2, x: 50, y: -2000 }, SIZE)).toEqual({ scale: 2, x: 0, y: -800 })
		expect(clampTransform({ scale: 9, x: 0, y: 0 }, SIZE).scale).toBe(MAX_SCALE)
		expect(clampTransform({ scale: 0.5, x: -10, y: 0 }, SIZE)).toEqual({ scale: 1, x: 0, y: 0 })
	})

	it('renders no transform at fit', () => {
		expect(toCss(FIT)).toBe('')
		expect(toCss({ scale: 2, x: -10, y: -20 })).toBe('translate(-10px, -20px) scale(2)')
	})

	it('maps taps to zones', () => {
		expect(tapZone(50, 400)).toBe('left')
		expect(tapZone(200, 400)).toBe('center')
		expect(tapZone(350, 400)).toBe('right')
	})
})

describe('GestureController', () => {
	it('reports a single tap after the double-tap window', () => {
		const { events, tap, advance } = setup()
		tap(30, 400)
		expect(events).toEqual([])
		advance(DOUBLE_TAP_MS)
		expect(events).toEqual(['tap 30,400'])
	})

	it('double tap zooms in at the tap point and out again, without taps', () => {
		const { g, events, transforms, tap, advance } = setup()
		tap(100, 200)
		tap(102, 201)
		expect(g.transform.scale).toBe(DOUBLE_TAP_SCALE)
		expect(transforms.at(-1)?.[1]).toBe(true)
		// the tapped content stays under the finger
		expect((102 - g.transform.x) / g.transform.scale).toBeCloseTo(102)
		advance(1000)
		tap(300, 300)
		tap(300, 300)
		expect(g.zoomed).toBe(false)
		advance(1000)
		expect(events).toEqual([])
	})

	it('two taps far apart are two taps', () => {
		const { g, events, tap, advance } = setup()
		tap(30, 400)
		tap(370, 400)
		advance(DOUBLE_TAP_MS)
		expect(events).toEqual(['tap 30,400', 'tap 370,400'])
		expect(g.zoomed).toBe(false)
	})

	it('pinch zooms around the fingers, clamped to the maximum', () => {
		const { g, pinch } = setup()
		pinch(200, 400, 100, 300)
		expect(g.transform.scale).toBeCloseTo(3)
		// the midpoint keeps showing the same content
		expect((200 - g.transform.x) / g.transform.scale).toBeCloseTo(200)
		expect((400 - g.transform.y) / g.transform.scale).toBeCloseTo(400)
		pinch(200, 400, 50, 500)
		expect(g.transform.scale).toBe(MAX_SCALE)
	})

	it('a pinch ending near fit snaps back; no taps or swipes from pinches', () => {
		const { g, events, transforms, pinch, advance } = setup()
		pinch(200, 400, 100, 104)
		expect(g.zoomed).toBe(false)
		expect(transforms.at(-1)).toEqual([FIT, true])
		pinch(200, 400, 100, 200)
		expect(g.transform.scale).toBe(2)
		pinch(200, 400, 200, 100)
		expect(g.zoomed).toBe(false)
		advance(1000)
		expect(events).toEqual([])
	})

	it('one finger pans while zoomed (clamped) instead of swiping', () => {
		const { g, events, pinch } = setup()
		pinch(200, 400, 100, 200) // scale 2 → x = -200, y = -400
		const before = g.transform
		g.down(1, 300, 400, 0)
		g.move(1, 250, 380)
		g.move(1, 100, 380)
		g.up(1, 100, 380, 100)
		expect(g.transform.scale).toBe(2)
		expect(g.transform.x).toBe(Math.max(-400, before.x - 200))
		expect(g.transform.y).toBe(before.y - 20)
		expect(events).toEqual([])
	})

	it('panning past the page edge scrolls the rest (fit-width pages)', () => {
		const { g, events, pinch } = setup()
		pinch(200, 800, 100, 200) // anchored at the bottom: y already at its minimum
		g.down(1, 200, 500, 0)
		g.move(1, 200, 400)
		g.up(1, 200, 400, 100)
		expect(events).toEqual(['scroll -100'])
	})

	it('a fit page swipes horizontally and scrolls vertically', () => {
		const { events, g } = setup()
		g.down(1, 300, 400, 0)
		g.move(1, 200, 410)
		g.up(1, 150, 410, 100)
		expect(events).toEqual(['scroll 10', 'swipe -150'])
		events.length = 0
		g.down(1, 200, 600, 0)
		g.move(1, 210, 300)
		g.up(1, 210, 300, 100)
		expect(events).toEqual(['scroll -300'])
	})

	it('one finger left after a pinch keeps panning', () => {
		const { g } = setup()
		g.down(1, 150, 400, 0)
		g.down(2, 250, 400, 0)
		g.move(1, 100, 400)
		g.move(2, 300, 400) // scale 2
		g.up(2, 300, 400, 10)
		const x = g.transform.x
		g.move(1, 130, 400)
		expect(g.transform.x).toBe(x + 30)
		g.up(1, 130, 400, 20)
		expect(g.transform.scale).toBe(2)
	})

	it('reset returns to fit without animation', () => {
		const { g, transforms, pinch } = setup()
		pinch(200, 400, 100, 300)
		g.reset()
		expect(g.zoomed).toBe(false)
		expect(transforms.at(-1)).toEqual([FIT, false])
		const count = transforms.length
		g.reset()
		expect(transforms.length).toBe(count)
	})

	it('a long press is no tap', () => {
		const { g, events, advance } = setup()
		g.down(1, 200, 400, 0)
		g.up(1, 200, 400, 1000)
		advance(1000)
		expect(events).toEqual([])
	})
})
