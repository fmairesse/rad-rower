package io.radrower

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class RowerProtocolTest {
    private fun telemetry(): ByteArray = ByteArray(50).apply {
        intArrayOf(1, 4, 2, 0x2e, 0x14, 0x2e, 2, 2).forEachIndexed { index, value ->
            this[index] = value.toByte()
        }
        this[9] = 0x26
        this[10] = 0x2c
        this[11] = 1
        this[12] = 0xd2.toByte()
        this[13] = 4
        this[21] = 0x2c
        this[22] = 1
        this[25] = 3
        this[34] = 0xa0.toByte()
        this[35] = 0x86.toByte()
        this[36] = 1
        this[40] = 85
        this[45] = 12
        this[49] = (slice(4 until 49).sumOf { it.toInt() and 0xff } and 0xff).toByte()
    }

    private fun frames(body: ByteArray): List<ByteArray> {
        val result = mutableListOf(ByteArray(20).apply {
            this[0] = 0xfe.toByte()
            this[1] = 2
            this[2] = body.size.toByte()
            this[3] = ((body.size + 17) / 18 + 1).toByte()
        })
        body.asList().chunked(18).forEachIndexed { index, chunk ->
            val last = index == (body.size - 1) / 18
            result += ByteArray(20).apply {
                this[0] = if (last) 0xff.toByte() else index.toByte()
                this[1] = if (last) chunk.size.toByte() else 0x12
                chunk.forEachIndexed { offset, byte -> this[offset + 2] = byte }
            }
        }
        return result
    }

    @Test fun assemblesAndDecodesTelemetry() {
        val assembler = RowerProtocol.MessageAssembler()
        val frames = frames(telemetry())
        frames.dropLast(1).forEach { assertNull(assembler.feed(it)) }
        val result = requireNotNull(assembler.feed(frames.last()))
        assertTrue(result.checksumOk)
        val metrics = requireNotNull(RowerProtocol.decodeMessage(result.body))
        assertEquals(300, metrics["watts"])
        assertEquals(1234, metrics["distance"])
        assertEquals(24, metrics["resistance"])
        assertEquals(3, metrics["sessionState"])
        assertEquals(300, metrics["elapsed"])
        assertEquals(1.0, metrics["calories"])
        assertEquals(85, metrics["averageWatts"])
        assertEquals(12, metrics["paused"])
        assertEquals(1148, metrics["caloriesPerHour"])
    }

    @Test fun rejectsCorruptAndOutOfOrderFramesAndRecovers() {
        val frames = frames(telemetry())
        val assembler = RowerProtocol.MessageAssembler()
        assertNull(assembler.feed(frames[0]))
        assertNull(assembler.feed(frames[2]))
        assertNull(assembler.feed(frames[3]))
        val corrupt = frames.map { it.clone() }
        corrupt[1][12] = (corrupt[1][12].toInt() xor 1).toByte()
        corrupt.dropLast(1).forEach { assertNull(assembler.feed(it)) }
        assertFalse(requireNotNull(assembler.feed(corrupt.last())).checksumOk)
        frames.dropLast(1).forEach { assertNull(assembler.feed(it)) }
        assertTrue(requireNotNull(assembler.feed(frames.last())).checksumOk)
    }

    @Test fun decodesMotionAndUnknownResistance() {
        val motion = ByteArray(20).apply {
            this[0] = 0xff.toByte(); this[1] = 0x11
            this[10] = 37; this[11] = 1; this[12] = 24
            this[13] = 0x13; this[14] = 1
        }
        val values = requireNotNull(RowerProtocol.decodeMotionFrame(motion))
        assertEquals(293, values["strokes"])
        assertEquals(24, values["cadence"])
        assertEquals(275, values["pace"])
        assertEquals(1800.0 / 275, values["speed"])
        assertNull(values["averagePace"])
        val body = telemetry()
        body[9] = 0xff.toByte()
        assertEquals("? 0xff", requireNotNull(RowerProtocol.decodeMessage(body))["resistance"])
        body.fill(0xff.toByte(), 10, 18)
        assertNull(requireNotNull(RowerProtocol.decodeMessage(body))["watts"])
    }

    @Test fun commandsHaveExpectedFrameCounts() {
        assertEquals(25, RowerProtocol.setup.sumOf { it.size })
        assertEquals(listOf(3, 3), RowerProtocol.poll.map { it.size })
        assertEquals(listOf(3, 2), RowerProtocol.start.map { it.size })
        assertEquals(listOf(2), RowerProtocol.pause.map { it.size })
        assertEquals(listOf(2), RowerProtocol.end.map { it.size })
        assertEquals(listOf(3, 2), RowerProtocol.idle.map { it.size })
    }

    @Test fun commandBytesMatchWebReference() {
        val references = listOf(
            RowerProtocol.setup to "260ed343d5734b3a019cd6005aed3cd9aab1f1638afc3fbf95efc471c6ff8fe2",
            RowerProtocol.poll to "16a42cbc2d25a265bb245606e19246c9812595d859499858be75aeb78b0030fe",
            RowerProtocol.start to "1b12a355af174e1d779be4f0a24cb2fb4f32fb93d7ba1bdfdc360efb311aeddd",
            RowerProtocol.pause to "76ba32784c5f20c5438a5efbc988126a3039742034d38a5eca6d4d7871c509b2",
            RowerProtocol.end to "03beafd1041c57d81b546b323d7e9aad167da12e4c0f3a02f8e79f2c60183751",
            RowerProtocol.idle to "37b5387b490dea9f142112d8f20d015e9a597178f30cb35e11d0fe1600a59902",
        )
        references.forEach { (messages, expected) ->
            val digest = MessageDigest.getInstance("SHA-256")
            messages.flatten().forEach { digest.update(it) }
            assertEquals(expected, digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
        }
    }
}
