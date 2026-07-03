package dev.zig.notificationfilter.data.local.db

import androidx.room.TypeConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Serialises the [NotificationReviewEntity.embedding] vector to a compact SQLite BLOB.
 *
 * SQLite has no native vector type, so each float is packed little-endian into a
 * ByteBuffer (4 bytes/float — a 100-d vector is 400 bytes, lossless). This is an order
 * of magnitude smaller and faster to parse than a JSON/CSV TEXT representation.
 *
 * The byte order is fixed (LITTLE_ENDIAN) rather than native so a database copied
 * between devices of different endianness still deserialises correctly.
 */
class FloatArrayConverter {

    @TypeConverter
    fun fromFloatArray(value: FloatArray?): ByteArray? {
        if (value == null) return null
        val buffer = ByteBuffer.allocate(value.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        value.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    @TypeConverter
    fun toFloatArray(bytes: ByteArray?): FloatArray? {
        if (bytes == null) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.float }
    }
}
