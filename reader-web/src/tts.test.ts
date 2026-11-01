/**
 * SPDX-FileCopyrightText: 2026 Felix Kurth
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */
import { describe, expect, it } from 'vitest'
import { ssmlToBlock } from './tts.ts'

describe('ssmlToBlock', () => {
	it('splits at marks, keeps the language and decodes entities', () => {
		const ssml = '<speak xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="de-DE">'
			+ '<mark name="0"/>Er sagte: &quot;Hallo&quot; &amp; ging. <mark name="1"/>Dann <emphasis>kam</emphasis><break/>der   Regen &#8211; lang.</speak>'
		expect(ssmlToBlock(ssml)).toEqual({
			lang: 'de-DE',
			segments: [
				{ mark: '0', text: 'Er sagte: "Hallo" & ging.' },
				{ mark: '1', text: 'Dann kam der Regen – lang.' },
			],
		})
	})

	it('keeps text before the first mark and drops empty segments', () => {
		expect(ssmlToBlock('<speak>Vorwort <mark name="0"/>   <mark name="1"/>Text</speak>')).toEqual({
			lang: undefined,
			segments: [{ mark: null, text: 'Vorwort' }, { mark: '1', text: 'Text' }],
		})
	})

	it('has nothing to say for empty blocks', () => {
		expect(ssmlToBlock(undefined)).toBeNull()
		expect(ssmlToBlock('<speak xml:lang="en"><mark name="0"/>  <break/> </speak>')).toBeNull()
	})
})
