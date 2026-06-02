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

    /**
     * Appends content to a single master CSV ledger file located in the public Documents folder.
     * Uses MediaStore for Android 10+ and File API for older versions.
     */
    private fun appendToCsv(content: String) {
        val fileName = "crb_detections_ledger.csv"
        val relativePath = "${Environment.DIRECTORY_DOCUMENTS}/CRB-detections"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = MediaStore.Files.getContentUri("external")

            // Find if file already exists
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
            val selectionArgs = arrayOf(fileName, "$relativePath/")
            
            var uri: Uri? = null
            resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    uri = ContentUris.withAppendedId(collection, id)
                }
            }

            // Create if it doesn't exist
            if (uri == null) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                }
                uri = resolver.insert(collection, values)
                // Write header for new file
                uri?.let {
                    resolver.openOutputStream(it, "wt")?.use { out ->
                        out.write(getCsvHeader().toByteArray())
                    }
                }
            }

            // Append the content
            uri?.let {
                try {
                    resolver.openOutputStream(it, "wa")?.use { out ->
                        out.write(content.toByteArray())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else {
            // Fallback for Android 9 and below
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "CRB-detections")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            val isNew = !file.exists()
            try {
                FileOutputStream(file, true).use { out ->
                    if (isNew) out.write(getCsvHeader().toByteArray())
                    out.write(content.toByteArray())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
            sb.append("${latitude ?: ""},")
            sb.append("${longitude ?: ""},")
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
            sb.append("${latitude ?: ""},")
            sb.append("${longitude ?: ""},")
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
