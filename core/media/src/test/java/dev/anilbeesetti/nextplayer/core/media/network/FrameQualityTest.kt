package dev.anilbeesetti.nextplayer.core.media.network

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers are taken from the first frames that hold a picture; these tests pin down what counts as
 * a picture so a black fade is never shown as a cover.
 */
@RunWith(RobolectricTestRunner::class)
class FrameQualityTest {

    @Test
    fun blackFrameHasNoPicture() {
        val quality = blackFrame().pictureQuality()

        assertFalse(
            "A black frame must not be used as a cover",
            quality.hasContent,
        )
    }

    @Test
    fun flatFrameHasNoPicture() {
        val quality = frameOf(color = Color.GRAY).pictureQuality()

        assertFalse(
            "A flat frame holds no picture",
            quality.hasContent,
        )
    }

    @Test
    fun colourfulFrameHasPicture() {
        val quality = colourfulFrame().pictureQuality()

        assertTrue(
            "A frame with a picture must be used as a cover",
            quality.hasContent,
        )
    }

    @Test
    fun brightnessMakesADarkFrameScoreLower() {
        val black = blackFrame().pictureQuality().score
        val picture = colourfulFrame().pictureQuality().score

        assertTrue(
            "A picture must score above black, but was $picture vs $black",
            picture > black,
        )
    }

    private fun blackFrame(): Bitmap = Bitmap.createBitmap(
        WIDTH,
        HEIGHT,
        Bitmap.Config.ARGB_8888,
    )

    private fun frameOf(color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(
            WIDTH,
            HEIGHT,
            Bitmap.Config.ARGB_8888,
        )
        for (x in 0 until WIDTH) {
            for (y in 0 until HEIGHT) {
                bitmap.setPixel(
                    x,
                    y,
                    color,
                )
            }
        }
        return bitmap
    }

    private fun colourfulFrame(): Bitmap {
        val bitmap = Bitmap.createBitmap(
            WIDTH,
            HEIGHT,
            Bitmap.Config.ARGB_8888,
        )
        for (x in 0 until WIDTH) {
            for (y in 0 until HEIGHT) {
                val red = 40 + (x * 200 / WIDTH)
                val green = 30 + (y * 220 / HEIGHT)
                val blue = 60 + ((x + y) * 150 / (WIDTH + HEIGHT))
                bitmap.setPixel(
                    x,
                    y,
                    Color.rgb(
                        red,
                        green,
                        blue,
                    ),
                )
            }
        }
        return bitmap
    }

    private companion object {
        const val WIDTH = 64

        const val HEIGHT = 36
    }
}
