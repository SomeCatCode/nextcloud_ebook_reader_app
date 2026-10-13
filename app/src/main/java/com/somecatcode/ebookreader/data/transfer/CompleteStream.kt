package com.somecatcode.ebookreader.data.transfer

import com.somecatcode.ebookreader.data.local.BookFile
import java.io.IOException
import java.io.InputStream

/**
 * Reads exactly [BookFile.length] bytes of [file]. When the stream ends early or fails (e.g. a
 * dropped WebDAV connection), it opens the file again at the current offset. It gives up after
 * [maxRetries] attempts in a row without new bytes and then throws, so a copy is never shorter
 * than the original.
 */
internal class CompleteStream(private val file: BookFile, private val maxRetries: Int = 5) : InputStream() {
    private val total = file.length
    private var position = 0L
    private var current: InputStream? = null
    private var failures = 0

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (position >= total) return -1
        while (true) {
            var cause: IOException? = null
            val n = try {
                val stream = current ?: file.open(position, total - position).also { current = it }
                stream.read(b, off, minOf(len.toLong(), total - position).toInt())
            } catch (e: IOException) {
                cause = e
                -1
            }
            if (n > 0) {
                position += n
                failures = 0
                return n
            }
            // early end or error: start over at the current offset
            closeCurrent()
            if (++failures > maxRetries) {
                throw IOException("Incomplete copy: $position of $total bytes", cause)
            }
        }
    }

    override fun close() = closeCurrent()

    private fun closeCurrent() {
        runCatching { current?.close() }
        current = null
    }
}
