package com.example.smsmaptracker

import android.provider.Telephony
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.view.MapView
import java.io.File
import java.util.Calendar

// Helper function to convert date/time parts to epoch millis
fun toEpochMillis(
    year: Int,
    month: Int,
    day: Int,
    hour: Int = 0,
    minute: Int = 0
): Long {
    val cal = Calendar.getInstance().apply {
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month - 1) // Calendar months are zero-based
        set(Calendar.DAY_OF_MONTH, day)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

// Data class holding coordinate + date millis for SMS location
data class SmsCoordinate(
    val latLong: LatLong,
    val dateMillis: Long
)

@Composable
fun MapsforgeMap(
    startYear: Int? = null,
    startMonth: Int? = null,
    startDay: Int? = null,
    startHour: Int = 0,
    startMinute: Int = 0,

    endYear: Int? = null,
    endMonth: Int? = null,
    endDay: Int? = null,
    endHour: Int = 0,
    endMinute: Int = 0,
) {
    val TAG = "MapsforgeMap"
    val context = LocalContext.current

    val startDateEpochMillis = if (startYear != null && startMonth != null && startDay != null) {
        toEpochMillis(startYear, startMonth, startDay, startHour, startMinute)
    } else null

    val endDateEpochMillis = if (endYear != null && endMonth != null && endDay != null) {
        toEpochMillis(endYear, endMonth, endDay, endHour, endMinute)
    } else null

    var mapCenter by remember { mutableStateOf(LatLong(36.7753026, 10.1120584)) }
    val previousZoomLevel = remember { mutableStateOf<Byte?>(null) }
    val mapViewRef = remember { mutableStateOf<MapView?>(null) }

    val smsCoordinates = remember { mutableStateListOf<SmsCoordinate>() }

    LaunchedEffect(startDateEpochMillis, endDateEpochMillis) {
        try {
            Log.d(TAG, "Querying SMS inbox with date filter [$startDateEpochMillis .. $endDateEpochMillis]")

            val selectionBuilder = StringBuilder("address LIKE ?")
            val selectionArgs = mutableListOf("%+21655895524%")

            if (startDateEpochMillis != null && endDateEpochMillis != null) {
                selectionBuilder.append(" AND date BETWEEN ? AND ?")
                selectionArgs.add(startDateEpochMillis.toString())
                selectionArgs.add(endDateEpochMillis.toString())
            } else if (startDateEpochMillis != null) {
                selectionBuilder.append(" AND date >= ?")
                selectionArgs.add(startDateEpochMillis.toString())
            } else if (endDateEpochMillis != null) {
                selectionBuilder.append(" AND date <= ?")
                selectionArgs.add(endDateEpochMillis.toString())
            }

            val cursor = context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf("address", "body", "date"),
                selectionBuilder.toString(),
                selectionArgs.toTypedArray(),
                "date DESC"
            )

            if (cursor == null) {
                Log.e(TAG, "Cursor is null — no SMS permissions or content resolver failed.")
                return@LaunchedEffect
            }

            val coordinates = mutableListOf<SmsCoordinate>()

            cursor.use {
                while (it.moveToNext()) {
                    val body = it.getString(it.getColumnIndexOrThrow("body"))
                    val dateMillis = it.getLong(it.getColumnIndexOrThrow("date"))

                    val regex = Regex("""(-?\d{1,3}(?:\.\d+)?),\s*(-?\d{1,3}(?:\.\d+)?)""")
                    val match = regex.find(body)

                    if (match != null) {
                        val lat = match.groupValues[1].toDouble()
                        val lon = match.groupValues[2].toDouble()
                        val coord = LatLong(lat, lon)
                        Log.d(TAG, "Parsed coordinates: $lat, $lon with date $dateMillis")
                        coordinates.add(SmsCoordinate(coord, dateMillis))
                    }
                }
            }

            if (coordinates.isNotEmpty()) {
                mapCenter = coordinates.first().latLong
                smsCoordinates.clear()
                smsCoordinates.addAll(coordinates)
            } else {
                Log.w(TAG, "No valid coordinates found in SMS messages within the date filter.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading SMS", e)
        }
    }

    val mapFile = remember {
        val file = File(context.cacheDir, "Tunisia_oam.osm.map")
        if (!file.exists()) {
            context.assets.open("Tunisia_oam.osm.map").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        file
    }

    val renderThemeFile = remember {
        val file = File(context.cacheDir, "andromaps_hike.xml")
        if (!file.exists()) {
            context.assets.open("andromaps_hike.xml").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        file
    }

    if (!mapFile.exists() || !renderThemeFile.exists()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("Required files not found in cache.")
        }
        return
    }

    val markerManager = remember { MarkerManager(context) }
    val mapViewProvider = remember {
        MapViewProvider(
            context = context,
            mapFile = mapFile,
            renderThemeFile = renderThemeFile,
            previousZoomLevel = previousZoomLevel,
            markerManager = markerManager
        ).apply {
            this.mapCenter = mapCenter
        }
    }

    AndroidView(
        factory = { ctx ->
            mapViewProvider.createMapView(mapCenter) { mapView ->
                mapViewRef.value = mapView
            }
        },
        modifier = Modifier.fillMaxSize()
    )

    LaunchedEffect(smsCoordinates) {
        val mapView = mapViewRef.value ?: return@LaunchedEffect

        Log.d(TAG, "Updating markers on the map")

        smsCoordinates.forEachIndexed { index, smsCoord ->
            Log.d(TAG, "Adding static marker #$index at ${smsCoord.latLong} with date ${smsCoord.dateMillis}")
            markerManager.addMarkerWithDate(mapView, smsCoord.latLong, smsCoord.dateMillis)
        }

        val center = smsCoordinates.firstOrNull()?.latLong ?: mapCenter
        markerManager.updateMarkerForZoom(mapView, 16, center)

        mapView.repaint()
    }
}
