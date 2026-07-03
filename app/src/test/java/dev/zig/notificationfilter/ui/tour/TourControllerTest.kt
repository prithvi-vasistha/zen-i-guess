package dev.zig.notificationfilter.ui.tour

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TourControllerTest {

    private fun steps(count: Int): List<TourStep> =
        (0 until count).map { TourStep(tab = 0, targetKey = null, title = "t$it", body = "b$it") }

    private fun controller(count: Int, onFinish: () -> Unit = {}): TourController =
        TourController(steps = steps(count), registry = CoachMarkRegistry(), onFinish = onFinish)

    @Test
    fun `starts on the first step`() {
        val c = controller(3)
        assertEquals(0, c.currentIndex)
        assertTrue(c.isFirst)
        assertFalse(c.isLast)
    }

    @Test
    fun `next advances until the last step`() {
        val c = controller(3)
        c.next()
        assertEquals(1, c.currentIndex)
        c.next()
        assertEquals(2, c.currentIndex)
        assertTrue(c.isLast)
    }

    @Test
    fun `next on the last step finishes instead of advancing`() {
        var finished = false
        val c = controller(2) { finished = true }
        c.next() // -> last
        c.next() // finishes
        assertTrue(finished)
        assertEquals(1, c.currentIndex) // index does not overrun
    }

    @Test
    fun `back never goes below the first step`() {
        val c = controller(3)
        c.back()
        assertEquals(0, c.currentIndex)
        c.next()
        c.back()
        assertEquals(0, c.currentIndex)
    }

    @Test
    fun `skip finishes from any step`() {
        var finished = false
        val c = controller(4) { finished = true }
        c.next()
        c.skip()
        assertTrue(finished)
    }
}
