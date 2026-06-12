package com.aubreymoore.crb_damage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class DetectionLogger(private val context: Context) {
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * Public directory for photos so they are visible in the Gallery.
     */
    private fun getPublicPhotoDir(): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "CRB-detections"
        )
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getCsvHeader(): String {
        return "timestamp,latitude,longitude,label,confidence,photo_path,is_high_res\n"
    }

    private fun appendToCsv(content: String) {
        try {
            // This saves to: Android/data/com.aubreymoore.crb_capture/files/Documents/
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            val file = File(dir, "crb_detections_ledger.csv")
            val isNew = !file.exists()

            // Open the file in 'append' mode
            val fos = FileOutputStream(file, true)
            if (isNew) {
                fos.write(getCsvHeader().toByteArray())
            }
            fos.write(content.toByteArray())
            fos.flush()
            fos.close()

            android.util.Log.d("DetectionLogger", "CSV Updated: ${file.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e("DetectionLogger", "Failed to write CSV: ${e.message}")
        }
    }



    fun logDetections(
        boundingBoxes: List<BoundingBox>,
        bitmap: Bitmap,
        latitude: Double?,
        longitude: Double?
    ) {
        if (boundingBoxes.isEmpty()) return

        val timestamp = timeFormat.format(Date())
        val photoFile = savePhoto(bitmap, timestamp, latitude, longitude)
        
        val sb = StringBuilder()
        for (box in boundingBoxes) {
            sb.append("$timestamp,")
            sb.append("${latitude ?: 0.0},")
            sb.append("${longitude ?: 0.0},")
            sb.append("${box.clsName},")
            sb.append("${"%.3f".format(Locale.US, box.cnf)},")
            sb.append("${photoFile?.absolutePath ?: ""},")
            sb.append("false\n")
        }
        appendToCsv(sb.toString())
    }

fun logHighResDetection(
    photoFile: File,
    boundingBoxes: List<BoundingBox>,
    latitude: Double?,
    longitude: Double?,
    timestamp: String
) {
    // Embed GPS EXIF into the existing high-res photo
    if (latitude != null && longitude != null) {
        try {
            val exif = ExifInterface(photoFile.absolutePath)
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, convertToExifLatLon(latitude))
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (latitude >= 0) "N" else "S")
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, convertToExifLatLon(longitude))
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (longitude >= 0) "E" else "W")
            exif.saveAttributes()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    val sb = StringBuilder()
    for (box in boundingBoxes) {
        sb.append("$timestamp,")
        sb.append("${latitude ?: 0.0},")   // Forces 0.0 if GPS is null
        sb.append("${longitude ?: 0.0},")  // Forces 0.0 if GPS is null
        sb.append("${box.clsName},")
        sb.append("${"%.3f".format(Locale.US, box.cnf)},")
        sb.append("${photoFile.absolutePath},")
        sb.append("true\n")
    }
    appendToCsv(sb.toString())
}

    private fun savePhoto(bitmap: Bitmap, timestamp: String, latitude: Double?, longitude: Double?): File? {
        return try {
            val safeTimestamp = timestamp.replace(":", "-").replace(" ", "_")
            val photoFile = File(getPublicPhotoDir(), "photo_$safeTimestamp.jpg")

            FileOutputStream(photoFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }

            if (latitude != null && longitude != null) {
                try {
                    val exif = ExifInterface(photoFile.absolutePath)
                    exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, convertToExifLatLon(latitude))
                    exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (latitude >= 0) "N" else "S")
                    exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, convertToExifLatLon(longitude))
                    exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (longitude >= 0) "E" else "W")
                    exif.saveAttributes()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
            photoFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun convertToExifLatLon(coordinate: Double): String {
        val absCoord = kotlin.math.abs(coordinate)
        val degrees = absCoord.toInt()
        val minutes = ((absCoord - degrees) * 60).toInt()
        val seconds = (absCoord - degrees - minutes / 60.0) * 3600.0
        return "$degrees/1,$minutes/1,${(seconds * 1000).toInt()}/1000"
    }
}
