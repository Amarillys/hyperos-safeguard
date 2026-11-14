package com.example.thermalguard.monitor

/** Reads only user_activity (20) from Android's PowerManagerServiceDumpProto. */
object PowerActivityParser {
    // AOSP: core/proto/android/server/powermanagerservice.proto, UserActivityProto fields 4, 5, 6.
    fun lastActivityUptimeMs(dump: ByteArray): Long? = try {
        val reader = Reader(dump, 0, dump.size)
        var latest: Long? = null
        while (reader.remaining > 0) {
            val (field, wire) = reader.tag()
            if (field == 20 && wire == 2) {
                val group = reader.message()
                var id = 0L // ProtoOutputStream omits the default display's zero-valued ID.
                var activity: Long? = null
                while (group.remaining > 0) {
                    val (nestedField, nestedWire) = group.tag()
                    if (nestedWire == 0 && nestedField in 4..6) {
                        val value = group.varint()
                        require(value >= 0)
                        if (nestedField == 6) id = value
                        else activity = activity?.let { maxOf(it, value) } ?: value
                    } else group.skip(nestedWire)
                }
                if (id == 0L && activity != null) latest = latest?.let { maxOf(it, activity) } ?: activity
            } else reader.skip(wire)
        }
        latest
    } catch (_: IllegalArgumentException) {
        null
    }

    private class Reader(private val data: ByteArray, private var position: Int, private val end: Int) {
        val remaining: Int get() = end - position

        fun varint(): Long {
            var result = 0L
            for (index in 0..9) {
                require(position < end)
                val byte = data[position++].toInt() and 0xff
                if (index == 9) require(byte <= 1)
                result = result or ((byte and 0x7f).toLong() shl (index * 7))
                if (byte and 0x80 == 0) return result
            }
            throw IllegalArgumentException("Invalid varint")
        }

        fun tag(): Pair<Int, Int> {
            val tag = varint()
            require(tag in 1..0xffffffffL && tag ushr 3 > 0)
            return (tag ushr 3).toInt() to (tag and 7).toInt()
        }

        private fun length(): Int {
            val value = varint()
            require(value in 0..remaining.toLong())
            return value.toInt()
        }

        fun message(): Reader {
            val size = length()
            return Reader(data, position, position + size).also { position += size }
        }

        fun skip(wire: Int) {
            val size = when (wire) {
                0 -> { varint(); return }
                1 -> 8
                2 -> length()
                5 -> 4
                else -> throw IllegalArgumentException("Unsupported wire type")
            }
            require(size <= remaining)
            position += size
        }
    }
}
