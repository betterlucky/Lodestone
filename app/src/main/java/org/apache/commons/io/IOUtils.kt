package org.apache.commons.io

import java.io.InputStream
import java.io.OutputStream

object IOUtils {
    @JvmStatic
    fun copy(input: InputStream, output: OutputStream): Int {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
            total += read
        }
        return if (total > Int.MAX_VALUE) -1 else total.toInt()
    }
}
