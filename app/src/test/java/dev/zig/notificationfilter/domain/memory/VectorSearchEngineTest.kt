package dev.zig.notificationfilter.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorSearchEngineTest {

    private val engine = VectorSearchEngine()

    @Test
    fun `identical vectors have similarity one`() {
        val v = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
        val corpus = listOf(MemoryVector(v.copyOf(), blocked = true))

        val neighbors = engine.search(v, corpus, k = 1)

        assertEquals(1, neighbors.size)
        assertEquals(1f, neighbors.first().similarity, 1e-5f)
    }

    @Test
    fun `orthogonal vectors have similarity zero`() {
        val query = floatArrayOf(1f, 0f)
        val corpus = listOf(MemoryVector(floatArrayOf(0f, 1f), blocked = false))

        val neighbors = engine.search(query, corpus, k = 1)

        assertEquals(0f, neighbors.first().similarity, 1e-5f)
    }

    @Test
    fun `opposite vectors have similarity minus one`() {
        val query = floatArrayOf(1f, 1f)
        val corpus = listOf(MemoryVector(floatArrayOf(-1f, -1f), blocked = false))

        val neighbors = engine.search(query, corpus, k = 1)

        assertEquals(-1f, neighbors.first().similarity, 1e-5f)
    }

    @Test
    fun `results are ranked by descending similarity and capped at k`() {
        val query = floatArrayOf(1f, 0f)
        val corpus = listOf(
            MemoryVector(floatArrayOf(0f, 1f), blocked = false),   // 0.0
            MemoryVector(floatArrayOf(1f, 1f), blocked = true),    // ~0.707
            MemoryVector(floatArrayOf(1f, 0f), blocked = true),    // 1.0
            MemoryVector(floatArrayOf(1f, 0.1f), blocked = false), // ~0.995
        )

        val neighbors = engine.search(query, corpus, k = 2)

        assertEquals(2, neighbors.size)
        assertTrue(neighbors[0].similarity >= neighbors[1].similarity)
        assertEquals(1f, neighbors[0].similarity, 1e-5f)
    }

    @Test
    fun `dimension mismatched corpus entries are ignored`() {
        val query = floatArrayOf(1f, 0f, 0f)
        val corpus = listOf(
            MemoryVector(floatArrayOf(1f, 0f), blocked = true),        // wrong dim → skipped
            MemoryVector(floatArrayOf(1f, 0f, 0f), blocked = false),   // kept
        )

        val neighbors = engine.search(query, corpus, k = 5)

        assertEquals(1, neighbors.size)
        assertEquals(false, neighbors.first().blocked)
    }

    @Test
    fun `empty query or empty corpus yields no neighbors`() {
        assertTrue(engine.search(FloatArray(0), listOf(MemoryVector(floatArrayOf(1f), false)), 3).isEmpty())
        assertTrue(engine.search(floatArrayOf(1f), emptyList(), 3).isEmpty())
    }
}
