package dev.zig.notificationfilter.service

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationImageDetectorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val picture = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    private val uri = Uri.parse("content://zig-image-tests/photo")

    @Test
    fun bigPictureWithCaptionIsAnImageNotification() {
        val notification = builder().setContentTitle("Promotion").setContentText("Today's offer")
            .setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture)).build()
        assertTrue(NotificationImageDetector.hasImage(notification))
    }

    @Test
    fun bigPictureWithoutAnyTextIsStillDetected() {
        val notification = builder().setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture)).build()
        assertTrue(NotificationImageDetector.hasImage(notification))
    }

    @Test
    fun iconBasedBigPictureIsDetectedOnAndroid12AndLater() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val notification = Notification.Builder(context, "image-tests")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(Notification.BigPictureStyle().bigPicture(Icon.createWithBitmap(picture)))
            .build()
        assertTrue(NotificationImageDetector.hasImage(notification))
    }

    @Test
    fun largeAppIconDoesNotCountAsAnImageAttachment() {
        val notification = builder().setContentText("Plain text").setLargeIcon(picture).build()
        assertFalse(NotificationImageDetector.hasImage(notification))
    }

    @Test
    fun contactAvatarDoesNotCountAsAnImageAttachment() {
        val sender = Person.Builder().setName("Contact")
            .setIcon(IconCompat.createWithBitmap(picture)).build()
        val notification = messaging(NotificationCompat.MessagingStyle.Message("Hello", 100L, sender))
        assertFalse(NotificationImageDetector.hasImage(notification))
    }

    @Test
    fun newestMessagingImageAttachmentIsDetected() {
        assertTrue(NotificationImageDetector.hasImage(messaging(image(100L))))
    }

    @Test
    fun imageAndCaptionAtTheSameTimestampAreDetectedTogether() {
        val notification = messaging(image(100L), text("Caption", 100L))
        assertTrue(NotificationImageDetector.hasImage(notification))
    }

    @Test
    fun olderPhotoDoesNotBlockANewerTextMessage() {
        val notification = messaging(image(100L), text("New text", 200L))
        assertFalse(NotificationImageDetector.hasImage(notification))
    }

    @Test
    fun latestTimestampWinsEvenIfTheMessageArrayIsOutOfOrder() {
        val notification = messaging(text("New text", 200L), image(100L))
        assertFalse(NotificationImageDetector.hasImage(notification))
    }

    @Test
    fun historicImagesDoNotCountAsCurrentAttachments() {
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Me").build())
            .addHistoricMessage(image(100L)).addMessage(text("New text", 200L))
        assertFalse(NotificationImageDetector.hasImage(builder().setStyle(style).build()))
    }

    @Test
    fun videoAttachmentsAreNotImages() {
        val video = text("Video", 100L).setData("video/mp4", uri)
        assertFalse(NotificationImageDetector.hasImage(messaging(video)))
    }

    @Test
    fun imageMimeTypeWithoutAnAttachmentUriIsNotAnImagePayload() {
        val message = text("Fallback text", 100L).setData("image/jpeg", null)
        assertFalse(NotificationImageDetector.hasImage(messaging(message)))
    }

    private fun builder() = NotificationCompat.Builder(context, "image-tests")
        .setSmallIcon(android.R.drawable.ic_dialog_info)

    private fun text(body: String, timestamp: Long) = NotificationCompat.MessagingStyle.Message(
        body, timestamp, Person.Builder().setName("Contact").build(),
    )

    private fun image(timestamp: Long) = text("Photo", timestamp).setData("image/jpeg", uri)

    private fun messaging(vararg messages: NotificationCompat.MessagingStyle.Message): Notification {
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Me").build())
        messages.forEach { style.addMessage(it) }
        return builder().setStyle(style).build()
    }
}
