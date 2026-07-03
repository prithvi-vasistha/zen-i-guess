package dev.zig.notificationfilter.data.local.db

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FloatArrayConverterTest {

    private val converter = FloatArrayConverter()

    @Test
    fun `round trips a typical embedding losslessly`() {
        val original = FloatArray(100) { i -> (i - 50) * 0.0137f }

        val restored = converter.toFloatArray(converter.fromFloatArray(original))

        assertArrayEquals(original, restored, 0f)
    }

    @Test
    fun `null maps to null in both directions`() {
        assertNull(converter.fromFloatArray(null))
        assertNull(converter.toFloatArray(null))
    }

    @Test
    fun `empty array round trips to empty array`() {
        val restored = converter.toFloatArray(converter.fromFloatArray(FloatArray(0)))

        assertEquals(0, restored?.size)
    }

    @Test
    fun `preserves special float values`() {
        val original = floatArrayOf(0f, -0f, Float.MIN_VALUE, Float.MAX_VALUE, 1f, -1f)

        val restored = converter.toFloatArray(converter.fromFloatArray(original))

        assertArrayEquals(original, restored, 0f)
    }

    @Test
    fun `blob is four bytes per float`() {
        val blob = converter.fromFloatArray(FloatArray(100))

        assertEquals(400, blob?.size)
    }
}
