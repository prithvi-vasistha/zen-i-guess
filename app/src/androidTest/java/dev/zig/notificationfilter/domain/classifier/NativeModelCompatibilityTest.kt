package dev.zig.notificationfilter.domain.classifier

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zig.notificationfilter.domain.embedding.MediaPipeTextEmbedder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a 16 KB emulator to exercise the packaged JNI libraries and real models. */
@RunWith(AndroidJUnit4::class)
class NativeModelCompatibilityTest {
    @Test
    fun classifierAndEmbeddingModelsRunInTheSameProcess() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val classifier = LocalModelEngine(context)
        val text = "Your delivery will arrive today"

        val first = classifier.evaluate(NotificationCategory.SHOPPING, "com.example.shop", text)
        assertTrue(first.confidence.isFinite())
        assertTrue(first.confidence in 0f..1f)

        val embedding = MediaPipeTextEmbedder(context).embed(text)
        assertNotNull("MediaPipe must load its native library and model", embedding)
        val vector = checkNotNull(embedding)
        assertTrue(vector.isNotEmpty())
        assertTrue(vector.all { it.isFinite() })
        assertEquals(1f, vector.sumOf { (it * it).toDouble() }.toFloat(), 0.01f)

        // Loading MediaPipe's bundled runtime must not break the standalone classifier.
        val second = classifier.evaluate(NotificationCategory.SHOPPING, "com.example.shop", text)
        assertEquals(first.confidence, second.confidence, 0.000001f)
    }
}
