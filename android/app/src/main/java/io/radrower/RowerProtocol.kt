package io.radrower

import java.util.UUID
import kotlin.math.roundToInt

internal object RowerProtocol {
    val service: UUID = UUID.fromString("00001533-1412-efde-1523-785feabcd123")
    val write: UUID = UUID.fromString("00001534-1412-efde-1523-785feabcd123")
    val notify: UUID = UUID.fromString("00001535-1412-efde-1523-785feabcd123")

    private fun message(vararg frames: String): List<ByteArray> = frames.map { hex ->
        require(hex.length % 2 == 0)
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    val setup = listOf(
        message("fe020802", "ff08020402040204818700000000000000000000"),
        message("fe020802", "ff08020402041404809800000000000000000000"),
        message("fe020802", "ff0802040204140488a000000000000000000000"),
        message("fe020a02", "ff0a0204020602068200008a0000000000000000"),
        message("fe020a02", "ff0a0204020602068400008c0000000000000000"),
        message("fe020802", "ff08020402040204959b00000000000000000000"),
        message("fe022c04", "0012020402281428900701f2f4f4f2fe08001e2a",
            "01123c4c5a669088a6c2e404224e98b0ce1a2c7c", "ff088ad620980200000000000000000000000000"),
        message("fe021703", "0012020402131413020c00000000000000000000",
            "ff0500800100b600000000000000000000000000"),
        message("fe021903", "0012020402151415020e00000000000000000000",
            "ff070000001001004a0000000000000000000000"),
        message("fe021903", "001202040215141502000f001000c01c4c0000e0",
            "ff070000001000086a0000000000000000000000"),
    )
    val poll = listOf(
        message("fe021703", "001202040213141302000d1c9e31000040400080",
            "ff0500000081a200000000000000000000000000"),
        message("fe021903", "001202040215141502000f800040000000000000",
            "ff0700000000e011eb0000000000000000000000"),
    )
    val start = listOf(
        message("fe021703", "0012020402131413020c00000000000000000000",
            "ff0500800000b500000000000000000000000000"),
        message("fe020d02", "ff0d020402091409020200100200330000000000"),
    )
    val pause = listOf(message("fe020d02", "ff0d020402091409020200100300340000000000"))
    val end = listOf(message("fe020d02", "ff0d020402091409020200100400350000000000"))
    val idle = listOf(
        message("fe021703", "0012020402131413020c00000000000000000000",
            "ff0500800100b600000000000000000000000000"),
        message("fe020d02", "ff0d020402091409020200100100320000000000"),
    )

    private val resistanceLevels = mapOf(
        0x01 to 1, 0x03 to 2, 0x04 to 3, 0x06 to 4, 0x07 to 5, 0x08 to 5,
        0x09 to 6, 0x0b to 7, 0x0c to 8, 0x0d to 8, 0x0e to 9, 0x10 to 10,
        0x11 to 11, 0x13 to 12, 0x14 to 13, 0x15 to 13, 0x16 to 14, 0x18 to 15,
        0x19 to 16, 0x1a to 16, 0x1b to 17, 0x1d to 18, 0x1e to 19, 0x20 to 20,
        0x22 to 21, 0x23 to 22, 0x25 to 23, 0x26 to 24, 0x27 to 24,
    )

    private fun ByteArray.u8(index: Int) = this[index].toInt() and 0xff
    private fun ByteArray.u16(index: Int) = u8(index) or (u8(index + 1) shl 8)
    private fun ByteArray.u32(index: Int) = u16(index).toLong() or (u16(index + 2).toLong() shl 16)
    private fun ByteArray.startsWith(vararg prefix: Int) =
        size >= prefix.size && prefix.indices.all { u8(it) == prefix[it] }

    private fun paceFields(pace: Int): Map<String, Any?> = mapOf(
        "pace" to pace.takeIf { it != 0 },
        "speed" to if (pace != 0) 1800.0 / pace else 0.0,
    )

    fun decodeMessage(body: ByteArray): Map<String, Any?>? {
        if (body.size >= 4 && body.startsWith(1, 4, 2) &&
            body.u8(3) in listOf(0x2e, 0x30, 0x31) && body.size == body.u8(3) + 4
        ) {
            if ((10..17).all { body.u8(it) == 0xff }) {
                return listOf("watts", "distance", "resistance", "sessionState", "elapsed",
                    "calories", "averageWatts", "paused", "caloriesPerHour").associateWith { null }
            }
            val watts = body.u16(10)
            val raw = body.u8(9)
            val values = mutableMapOf<String, Any?>(
                "watts" to watts,
                "distance" to body.u16(12),
                "resistance" to if (raw == 0) null else resistanceLevels[raw] ?: "? 0x%02x".format(raw),
            )
            if (body.u8(3) == 0x2e) {
                values.putAll(mapOf(
                    "sessionState" to body.u8(25),
                    "elapsed" to body.u16(21),
                    "calories" to body.u32(34) / 100000.0,
                    "averageWatts" to body.u8(40),
                    "paused" to body.u8(45),
                    "caloriesPerHour" to if (watts != 0) (3.2438 * watts + 174.66).roundToInt() else null,
                ))
            }
            return values
        }
        if (body.size == 33 && body.startsWith(1, 4, 2, 0x1d, 0x14, 0x1d, 2, 2)) {
            return paceFields(body.u16(27))
        }
        return null
    }

    fun decodeMotionFrame(frame: ByteArray): Map<String, Any?>? {
        if (frame.size != 20 || !frame.startsWith(0xff, 0x11) ||
            (2..9).any { frame.u8(it) != 0 }
        ) return null
        return mapOf(
            "strokes" to frame.u16(10),
            "cadence" to frame.u8(12),
            "averagePace" to frame.u16(15).takeIf { it != 0 },
        ) + paceFields(frame.u16(13))
    }

    class MessageAssembler {
        private val body = mutableListOf<Byte>()
        private var length = 0
        private var frames = 0
        private var received = 0

        fun reset() {
            body.clear()
            length = 0
            frames = 0
            received = 0
        }

        fun feed(frame: ByteArray): Result? {
            if (frame.size != 20) {
                reset()
                return null
            }
            if (frame.u8(0) == 0xfe && frame.u8(1) == 0x02) {
                reset()
                length = frame.u8(2)
                frames = frame.u8(3)
                received = if (frames >= 2) 1 else 0
                return null
            }
            if (received == 0) return null
            if (frame.u8(0) == received - 1 && frame.u8(1) == 0x12 &&
                received < frames - 1 && body.size + 18 <= length
            ) {
                body.addAll(frame.slice(2 until 20))
                received++
                return null
            }
            val count = frame.u8(1)
            if (frame.u8(0) == 0xff && received == frames - 1 &&
                count in 1..18 && count == length - body.size
            ) {
                body.addAll(frame.slice(2 until 2 + count))
                val complete = body.toByteArray()
                reset()
                val checksum = if (complete.size >= 2) {
                    complete.slice(4 until complete.lastIndex).sumOf { it.toInt() and 0xff } and 0xff
                } else 0
                return Result(complete, complete.size >= 2 && checksum == complete.u8(complete.lastIndex))
            }
            reset()
            return null
        }
    }

    data class Result(val body: ByteArray, val checksumOk: Boolean)
}
