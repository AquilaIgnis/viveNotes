package com.vivenotes.fileimport

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vivenotes.fileimport.docx.WordCrop
import com.vivenotes.fileimport.docx.croppedPicture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** A picture a document crops comes in as what the page shows of it, not the whole of it. */
@RunWith(AndroidJUnit4::class)
class PictureCropTest {
    /** Red on the left half, blue on the right. */
    private fun halves(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawRect(0f, 0f, width / 2f, height.toFloat(), Paint().apply { color = Color.RED })
            drawRect(width / 2f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = Color.BLUE })
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private fun decoded(bytes: ByteArray?): Bitmap = BitmapFactory.decodeByteArray(requireNotNull(bytes), 0, bytes.size)

    @Test
    fun whatIsCutAwayIsGoneAndTheRestIsKept() {
        val cropped = decoded(croppedPicture(halves(400, 100), WordCrop(0f, 0f, 0.5f, 0f)))

        assertEquals(200, cropped.width)
        assertEquals(100, cropped.height)
        assertEquals(Color.RED, cropped.getPixel(10, 50))
        assertEquals(Color.RED, cropped.getPixel(190, 50))
    }

    /** Sharper than the attachment store keeps would only be thrown away on the way in. */
    @Test
    fun aBigPictureIsCutToNoMoreThanTheStoreKeeps() {
        val cropped = decoded(croppedPicture(halves(4200, 2100), WordCrop(0f, 0f, 0.5f, 0f)))

        assertEquals(FileImporter.PICTURE_LONGEST_PX, maxOf(cropped.width, cropped.height))
        assertEquals(cropped.width, cropped.height)
        assertEquals(Color.RED, cropped.getPixel(cropped.width - 20, cropped.height / 2))
    }

    /** A negative side is padding, which has nothing in it to keep or cut. */
    @Test
    fun paddingIsNotCropping() {
        val cropped = decoded(croppedPicture(halves(400, 100), WordCrop(-0.25f, 0f, 0.5f, 0f)))

        assertEquals(200, cropped.width)
    }

    @Test
    fun aCropThatLeavesNothingOrBytesThatAreNoPictureGiveNothing() {
        assertNull(croppedPicture(halves(100, 100), WordCrop(0.6f, 0f, 0.5f, 0f)))
        assertNull(croppedPicture(byteArrayOf(1, 2, 3), WordCrop(0.1f, 0f, 0f, 0f)))
    }
}
