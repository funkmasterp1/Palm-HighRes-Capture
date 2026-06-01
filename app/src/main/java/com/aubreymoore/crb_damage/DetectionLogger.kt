package com.aubreymoore.crb_damage

import android.content.Context
import android.graphics.Bitmap
import android.media.ExifInterface
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class DetectionLogger(private val context: Context) {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun getOutputDir(): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "CRB-detections"
        )
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getCsvFile(): File {
        val today = dateFormat.format(Date())
        return File(getOutputDir(), "crb_detections_$today.csv")
    }

    fun logDetections(
        boundingBoxes: List<BoundingBox>,
        bitmap: Bitmap,
        latitude: Double?,
        longitude: Double?
    ) {
        if (boundingBoxes.isEmpty()) return

        val timestamp = timeFormat.format(Date())
        val csvFile = getCsvFile()
        val isNew = !csvFile.exists()
        val photoFile = savePhoto(bitmap, timestamp, latitude, longitude)
        try {
            val writer = FileWriter(csvFile, true)
            if (isNew) {
                writer.append("timestamp,latitude,longitude,label,confidence,photo_path\n")
            }
            for (box in boundingBoxes) {
                writer.append("$timestamp,")
                writer.append("${latitude ?: ""},")
                writer.append("${longitude ?: ""},")
                writer.append("${box.clsName},")
                writer.append("${"%.3f".format(box.cnf)},")
                writer.append("${photoFile?.absolutePath ?: ""}\n")
            }
            writer.flush()
            writer.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // NEW: Log high-res photo from ImageCapture (for SAM3 analysis)
    fun logHighResDetection(
        photoFile: File,
        boundingBoxes: List<BoundingBox>,
        latitude: Double?,
        longitude: Double?,
        timestamp: String
    ) {
        val csvFile = getCsvFile()
        val isNew = !csvFile.exists()

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
        // Log to CSV with is_high_res flag
        try {
            val writer = FileWriter(csvFile, true)
            if (isNew) {
                writer.append("timestamp,latitude,longitude,label,confidence,photo_path,is_high_res\n")
            }

            for (box in boundingBoxes) {
                writer.append("$timestamp,")
                writer.append("${latitude ?: ""},")
                writer.append("${longitude ?: ""},")
                writer.append("${box.clsName},")
                writer.append("${"%.3f".format(box.cnf)},")
                writer.append("${photoFile.absolutePath},")
                writer.append("true\n")
            }
            writer.flush()
            writer.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun savePhoto(bitmap: Bitmap, timestamp: String, latitude: Double?, longitude: Double?): File? {
        return try {
            val safeTimestamp = timestamp.replace(":", "-").replace(" ", "_")
            val photoFile = File(getOutputDir(), "photo_$safeTimestamp.jpg")
            val out = FileOutputStream(photoFile)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            out.flush()
            out.close()

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
        val seconds = (((absCoord - degrees) * 60 - minutes) * 60).toInt()
        return "$degrees/1,$minutes/1,$seconds/1"
    }
}
