package com.somecatcode.ebookreader.data.transfer

import com.somecatcode.ebookreader.data.local.BookFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class CompleteStreamTest {
    private val data = ByteArray(10_000) { (it % 251).toByte() }

    /** Serves [data]; each opened stream ends (or fails) after [chunk] bytes. */
    private inner class Flaky(private val chunk: Int, private val fail: Boolean = false, private val stopAt: Long = Long.MAX_VALUE) : BookFile {
        val offsets = mutableListOf<Long>()
        override val name = "x.cbz"
        override val length = data.size.toLong()
        override fun open(offset: Long, count: Long): InputStream {
            offsets += offset
            val end = minOf(offset + chunk, data.size.toLong(), stopAt).toInt()
            val slice = ByteArrayInputStream(data, offset.toInt(), (end - offset.toInt()).coerceAtLeast(0))
            return object : InputStream() {
                override fun read(): Int = slice.read().let { if (it == -1 && fail) throw IOException("reset") else it }
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    slice.read(b, off, len).let { if (it == -1 && fail) throw IOException("reset") else it }
            }
        }
    }

    @Test
    fun resumesAfterEarlyEndAndDeliversEveryByte() {
        val file = Flaky(chunk = 3_000)
        val copied = CompleteStream(file).use { it.readBytes() }
        assertArrayEquals(data, copied)
        assertEquals(listOf(0L, 3_000L, 6_000L, 9_000L), file.offsets)
    }

    @Test
    fun resumesAfterConnectionErrors() {
        val copied = CompleteStream(Flaky(chunk = 4_096, fail = true)).use { it.readBytes() }
        assertArrayEquals(data, copied)
    }

    @Test
    fun throwsWhenTheSourceStopsForGood() {
        val e = assertThrows(IOException::class.java) {
            CompleteStream(Flaky(chunk = 3_000, stopAt = 5_000), maxRetries = 3).use { it.readBytes() }
        }
        assertEquals("Incomplete copy: 5000 of 10000 bytes", e.message)
    }
}
