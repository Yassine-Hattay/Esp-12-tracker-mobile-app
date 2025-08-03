// SmsUtils.kt

package com.example.smsmaptracker

import android.content.Context
import android.net.Uri
import org.mapsforge.core.model.LatLong
import android.util.Log

fun getLastCoordinatesFromSMS(context: Context, phoneNumber: String): List<LatLong> {
    val coordinates = mutableListOf<LatLong>()
    val uri = Uri.parse("content://sms/inbox")
    val projection = arrayOf("address", "body", "date")
    val selection = "address = ?"
    val selectionArgs = arrayOf(phoneNumber)

    val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, "date DESC")

    cursor?.use {
        while (it.moveToNext()) {
            val body = it.getString(it.getColumnIndexOrThrow("body"))

            val regex = Regex("""(-?\d+\.\d+),\s*(-?\d+\.\d+)""")
            val match = regex.find(body)
            if (match != null) {
                val lat = match.groupValues[1].toDouble()
                val lon = match.groupValues[2].toDouble()
                val coord = LatLong(lat, lon)
                coordinates.add(coord)
                Log.d("SMS_COORD", "Found coordinate: lat=$lat, lon=$lon")
            }

            if (coordinates.size >= 10) break
        }
    }

    Log.d("SMS_COORD", "Total coordinates found: ${coordinates.size}")
    return coordinates
}


