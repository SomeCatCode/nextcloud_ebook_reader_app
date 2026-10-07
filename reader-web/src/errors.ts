/**
 * SPDX-FileCopyrightText: 2026 Felix Kurth
 * SPDX-License-Identifier: LicenseRef-Proprietary
 */
import type { ErrorCode } from './protocol.ts'

/** An error carrying a bridge error code. */
export class BridgeError extends Error {
	public readonly code: ErrorCode

	constructor(code: ErrorCode, message: string) {
		super(message)
		this.name = 'BridgeError'
		this.code = code
	}
}

/**
 * Maps any thrown value to a bridge error. reader-core's `ReaderError` is recognised by name so
 * that this module needs no runtime import of reader-core.
 *
 * @param e
 */
export function toBridgeError(e: unknown): { code: ErrorCode, message: string } {
	if (e instanceof BridgeError) {
		return { code: e.code, message: e.message }
	}
	const err = e as { name?: string, code?: string, message?: string } | null
	const message = String(err?.message ?? e)
	if (err?.name === 'ReaderError') {
		if (err.code === 'unsupported') {
			return { code: 'unsupported-format', message }
		}
		return { code: 'open-failed', message: err.code === 'drm' ? `drm: ${message}` : message }
	}
	return { code: 'reader', message }
}
