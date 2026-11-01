/* LibrePods contributors, GPL-3.0-or-later. */
package me.kavishdevar.librepods.bluetooth

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.UUID

/** Bounded OPACK values used by AudioArbitration. Never interprets binary tags as text. */
internal object Opack {
    private const val MAX_BYTES = 998
    private const val MAX_DEPTH = 24

    fun encode(value: Any?): ByteArray = ByteArrayOutputStream().apply {
        writeValue(value, 0)
        require(size() <= MAX_BYTES) { "OPACK message exceeds relay limit" }
    }.toByteArray()

    fun decode(data: ByteArray): Any? {
        require(data.size <= MAX_BYTES) { "OPACK message exceeds relay limit" }
        val reader = Reader(data)
        val value = reader.readValue(0)
        require(reader.remaining == 0) { "Trailing OPACK data" }
        return value
    }

    private fun ByteArrayOutputStream.writeLE(value: Long, width: Int) {
        repeat(width) { write((value ushr (it * 8)).toInt() and 0xff) }
    }

    private fun ByteArrayOutputStream.writeValue(value: Any?, depth: Int) {
        require(depth <= MAX_DEPTH) { "OPACK nesting limit exceeded" }
        when (value) {
            null -> write(4)
            is Boolean -> write(if (value) 1 else 2)
            is Byte, is Short, is Int, is Long -> {
                val number = (value as Number).toLong()
                when {
                    number in 0..39 -> write(number.toInt() + 8)
                    number in 0..0xff -> { write(0x30); writeLE(number, 1) }
                    number in 0..0xffff -> { write(0x31); writeLE(number, 2) }
                    number in 0..0xffffffffL -> { write(0x32); writeLE(number, 4) }
                    else -> { write(0x33); writeLE(number, 8) }
                }
            }
            is Float -> { write(0x35); writeLE(value.toRawBits().toLong(), 4) }
            is Double -> { write(0x36); writeLE(value.toRawBits(), 8) }
            is UUID -> {
                write(5)
                write(ByteBuffer.allocate(16).putLong(value.mostSignificantBits)
                    .putLong(value.leastSignificantBits).array())
            }
            is String -> {
                val bytes = value.toByteArray(Charsets.UTF_8)
                require(bytes.size <= MAX_BYTES)
                when {
                    bytes.size <= 32 -> write(0x40 + bytes.size)
                    bytes.size <= 0xff -> { write(0x61); writeLE(bytes.size.toLong(), 1) }
                    else -> { write(0x62); writeLE(bytes.size.toLong(), 2) }
                }
                write(bytes)
            }
            is ByteArray -> {
                require(value.size <= MAX_BYTES)
                when {
                    value.size <= 32 -> write(0x70 + value.size)
                    value.size <= 0xff -> { write(0x91); writeLE(value.size.toLong(), 1) }
                    else -> { write(0x92); writeLE(value.size.toLong(), 2) }
                }
                write(value)
            }
            is Map<*, *> -> {
                write(0xe0 + minOf(value.size, 15))
                value.forEach { (key, entry) ->
                    require(key is String) { "Routing dictionary keys must be text" }
                    writeValue(key, depth + 1)
                    writeValue(entry, depth + 1)
                }
                if (value.size >= 15) write(3)
            }
            is List<*> -> {
                write(0xd0 + minOf(value.size, 15))
                value.forEach { writeValue(it, depth + 1) }
                if (value.size >= 15) write(3)
            }
            else -> throw IllegalArgumentException("Unsupported OPACK value")
        }
        require(size() <= MAX_BYTES) { "OPACK message exceeds relay limit" }
    }

    private class Reader(private val data: ByteArray) {
        private var position = 0
        private val references = mutableListOf<Any>()
        val remaining get() = data.size - position

        private fun take(length: Int): ByteArray {
            require(length >= 0 && length <= remaining) { "Truncated OPACK value" }
            return data.copyOfRange(position, position + length).also { position += length }
        }

        private fun integer(width: Int): Long {
            var result = 0L
            take(width).forEachIndexed { index, byte ->
                result = result or ((byte.toLong() and 0xff) shl (index * 8))
            }
            return result
        }

        private fun length(width: Int): Int {
            val value = integer(width)
            require(value in 0..remaining.toLong()) { "Invalid OPACK length" }
            return value.toInt()
        }

        fun readValue(depth: Int): Any? {
            require(depth <= MAX_DEPTH) { "OPACK nesting limit exceeded" }
            val tag = integer(1).toInt()
            var cache = true
            val value: Any? = when (tag) {
                1 -> { cache = false; true }
                2 -> { cache = false; false }
                4 -> { cache = false; null }
                5 -> ByteBuffer.wrap(take(16)).let { UUID(it.long, it.long) }
                // Absolute-time values are opaque to routing policy, but occupy the object table.
                6 -> Timestamp(integer(8))
                in 8..0x2f -> { cache = false; (tag - 8).toLong() }
                in 0x30..0x33 -> integer(1 shl (tag - 0x30))
                0x35 -> Float.fromBits(integer(4).toInt())
                0x36 -> Double.fromBits(integer(8))
                in 0x40..0x64 -> {
                    val size = if (tag <= 0x60) tag - 0x40 else length(tag - 0x60)
                    try {
                        take(size).decodeToString(throwOnInvalidSequence = true)
                    } catch (error: CharacterCodingException) {
                        throw IllegalArgumentException("Invalid OPACK UTF-8", error)
                    }
                }
                in 0x70..0x94 -> {
                    val size = if (tag <= 0x90) tag - 0x70 else length(1 shl (tag - 0x91))
                    take(size)
                }
                in 0xa0..0xc4 -> {
                    val index = if (tag <= 0xc0) tag - 0xa0 else integer(tag - 0xc0).also {
                        require(it in 0..Int.MAX_VALUE.toLong())
                    }.toInt()
                    require(index in references.indices) { "Invalid OPACK object reference" }
                    return references[index]
                }
                in 0xd0..0xef -> {
                    cache = false
                    val count = tag and 15
                    val dictionary = tag >= 0xe0
                    val map = linkedMapOf<String, Any?>()
                    val list = mutableListOf<Any?>()
                    var entries = 0
                    while (count == 15 || entries < count) {
                        require(remaining > 0) { "Unterminated OPACK container" }
                        if (count == 15 && data[position] == 3.toByte()) { position++; break }
                        val key = readValue(depth + 1)
                        if (dictionary) {
                            require(key is String && !map.containsKey(key)) { "Invalid routing dictionary key" }
                            map[key] = readValue(depth + 1)
                        } else list.add(key)
                        entries++
                    }
                    if (dictionary) map else list
                }
                else -> throw IllegalArgumentException("Unsupported OPACK tag 0x${tag.toString(16)}")
            }
            if (cache && value != null && references.none {
                    if (it is ByteArray && value is ByteArray) it.contentEquals(value)
                    else it.javaClass == value.javaClass && it == value
                }) references.add(value)
            return value
        }
    }

    private data class Timestamp(val ticks: Long)
}
