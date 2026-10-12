package com.somecatcode.ebookreader.data.local

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * A readable book file on the device: a finished download ([File]) or a file of a local library
 * (`content://` document). The reader request proxy serves it with Range support.
 */
interface BookFile {
    val name: String
    val length: Long

    /** Stream of [count] bytes from [offset]; the caller closes it. */
    fun open(offset: Long, count: Long): InputStream
}

fun File.asBookFile(): BookFile = FileBookFile(this)

private class FileBookFile(private val file: File) : BookFile {
    override val name: String get() = file.name
    override val length: Long get() = file.length()
    override fun open(offset: Long, count: Long): InputStream = FileSliceStream(RandomAccessFile(file, "r").also { it.seek(offset) }, count)
}

/** Reads [remaining] bytes of [raf] from its current position; the file closes with the stream. */
internal class FileSliceStream(private val raf: RandomAccessFile, private var remaining: Long) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = raf.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = raf.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) remaining -= n
        return n
    }

    override fun available(): Int = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()

    override fun close() {
        raf.close()
    }
}
