package com.nutrilens.core.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.nutrilens.core.common.di.IoDispatcher
import com.nutrilens.core.model.AppError
import com.nutrilens.core.model.Outcome
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A processed image ready to be analysed or stored. */
data class PreparedMealImage(
    val file: File,
    val widthPx: Int,
    val heightPx: Int,
    val byteSize: Int,
)

/**
 * Prepares and stores meal photographs on the device.
 *
 * The pipeline, in order, and why each step is here:
 *
 * 1. **Decode bounds first.** Reading dimensions without allocating the full
 *    bitmap means a huge photo cannot exhaust memory before it is rejected.
 * 2. **Downscale.** A 12 MP camera frame carries no more food information than
 *    a 1440 px one, and shipping the original wastes the user's data.
 * 3. **Apply orientation, then drop EXIF.** Camera frames carry GPS
 *    coordinates and device identifiers. Rotation is baked into the pixels so
 *    nothing downstream needs the metadata, and then none of it is retained.
 * 4. **Compress to JPEG.**
 * 5. **Write inside the app's private storage**, which other apps cannot read.
 */
@Singleton
class MealImageStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private val imageDirectory: File
        get() = File(context.filesDir, IMAGE_DIRECTORY).apply { mkdirs() }

    /** Process a captured file into a stored, sanitised image. */
    suspend fun prepare(source: File): Outcome<PreparedMealImage> =
        withContext(ioDispatcher) {
            if (!source.isFile) {
                return@withContext Outcome.failure(
                    AppError.InvalidImage("The captured file is missing."),
                )
            }
            if (source.length() > MAX_SOURCE_BYTES) {
                return@withContext Outcome.failure(AppError.ImageTooLarge)
            }

            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(source.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    return@withContext Outcome.failure(
                        AppError.InvalidImage("The file is not a readable image."),
                    )
                }
                if (bounds.outWidth < MIN_DIMENSION_PX || bounds.outHeight < MIN_DIMENSION_PX) {
                    return@withContext Outcome.failure(
                        AppError.InvalidImage("The image is too small to analyse."),
                    )
                }

                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
                }
                val decoded = BitmapFactory.decodeFile(source.absolutePath, decodeOptions)
                    ?: return@withContext Outcome.failure(
                        AppError.InvalidImage("The image could not be decoded."),
                    )

                val oriented = applyExifOrientation(source, decoded)
                val scaled = scaleToLongestEdge(oriented)

                val bytes = ByteArrayOutputStream().use { stream ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                    stream.toByteArray()
                }

                val target = File(imageDirectory, "${UUID.randomUUID()}.jpg")
                // Write to a temporary name and rename, so a crash mid-write
                // cannot leave a truncated file that looks like a valid photo.
                val temporary = File(target.absolutePath + ".part")
                temporary.writeBytes(bytes)
                if (!temporary.renameTo(target)) {
                    temporary.delete()
                    return@withContext Outcome.failure(
                        AppError.DeviceError("Could not save the image."),
                    )
                }

                val result = PreparedMealImage(
                    file = target,
                    widthPx = scaled.width,
                    heightPx = scaled.height,
                    byteSize = bytes.size,
                )

                if (scaled !== decoded) scaled.recycle()
                if (oriented !== decoded) oriented.recycle()
                decoded.recycle()

                Outcome.success(result)
            } catch (e: OutOfMemoryError) {
                // Decoding is the one place a low-memory device realistically
                // fails; reporting it beats taking the process down.
                Outcome.failure(AppError.DeviceError("Not enough memory to process the image."))
            } catch (e: IOException) {
                Outcome.failure(AppError.DeviceError("The image could not be saved."))
            }
        }

    suspend fun delete(path: String): Boolean = withContext(ioDispatcher) {
        val file = File(path)
        // Only ever delete inside our own directory, whatever path is passed.
        if (file.parentFile?.absolutePath != imageDirectory.absolutePath) {
            return@withContext false
        }
        file.delete()
    }

    /** Remove every stored image. Used on sign-out and account deletion. */
    suspend fun clear() = withContext(ioDispatcher) {
        imageDirectory.listFiles()?.forEach { it.delete() }
        Unit
    }

    /** A file in the app's cache for CameraX to write a capture into. */
    fun newCaptureFile(): File =
        File(context.cacheDir, "capture-${UUID.randomUUID()}.jpg")

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        var longestEdge = maxOf(width, height)
        // Powers of two only: BitmapFactory rounds anything else down anyway.
        while (longestEdge / 2 >= MAX_EDGE_PX) {
            longestEdge /= 2
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun applyExifOrientation(source: File, bitmap: Bitmap): Bitmap {
        val orientation = try {
            ExifInterface(source.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun scaleToLongestEdge(bitmap: Bitmap): Bitmap {
        val longestEdge = maxOf(bitmap.width, bitmap.height)
        if (longestEdge <= MAX_EDGE_PX) return bitmap
        val scale = MAX_EDGE_PX.toFloat() / longestEdge
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private companion object {
        const val IMAGE_DIRECTORY = "meal-images"
        const val MAX_EDGE_PX = 1440
        const val MIN_DIMENSION_PX = 64
        const val JPEG_QUALITY = 85

        /** Matches the server's upload limit, so a rejection happens locally. */
        const val MAX_SOURCE_BYTES = 12L * 1024 * 1024
    }
}
