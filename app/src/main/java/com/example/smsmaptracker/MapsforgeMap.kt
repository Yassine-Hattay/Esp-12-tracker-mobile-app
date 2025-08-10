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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.mapsforge.core.graphics.Color
import org.mapsforge.core.graphics.Paint
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.android.view.MapView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// Helper function to calculate distance between two LatLong points (approx meters)
fun distanceBetween(p1: LatLong, p2: LatLong): Double {
    val earthRadius = 6371000.0 // meters
    val dLat = Math.toRadians(p2.latitude - p1.latitude)
    val dLon = Math.toRadians(p2.longitude - p1.longitude)
    val lat1 = Math.toRadians(p1.latitude)
    val lat2 = Math.toRadians(p2.latitude)

    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.sin(dLon / 2) * Math.sin(dLon / 2) * Math.cos(lat1) * Math.cos(lat2)
    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    return earthRadius * c
}
// Helper function as before
fun toEpochMillis(
    year: Int, month: Int, day: Int,
    hour: Int = 0, minute: Int = 0
): Long {
    val cal = Calendar.getInstance().apply {
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month - 1)
        set(Calendar.DAY_OF_MONTH, day)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
}

data class SmsCoordinate(val latLong: LatLong, val dateMillis: Long)

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
    val polylineRef = remember { mutableStateOf<Polyline?>(null) }

    val smsCoordinates = remember { mutableStateListOf<SmsCoordinate>() }

    LaunchedEffect(startDateEpochMillis, endDateEpochMillis) {
        try {
            Log.d(TAG, "Querying SMS inbox with date filter [$startDateEpochMillis .. $endDateEpochMillis]")

            val selectionBuilder = StringBuilder("address LIKE ?")
            val selectionArgs = mutableListOf("%+21655895524%") // Your filter phone number here

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

    // When smsCoordinates change, add markers and fetch + display route
    LaunchedEffect(smsCoordinates) {
        val mapView = mapViewRef.value ?: return@LaunchedEffect

        // Clear old polyline if exists
        polylineRef.value?.let {
            mapView.layerManager.layers.remove(it)
            polylineRef.value = null
        }

        // Add markers with date labels
        smsCoordinates.forEachIndexed { index, smsCoord ->
            Log.d(TAG, "Adding marker #$index at ${smsCoord.latLong} with date ${smsCoord.dateMillis}")
            markerManager.addMarkerWithDate(mapView, smsCoord.latLong, smsCoord.dateMillis)
        }

        // Update zoom marker on first coordinate or fallback
        val center = smsCoordinates.firstOrNull()?.latLong ?: mapCenter
        markerManager.updateMarkerForZoom(mapView, 16, center)

        mapView.repaint()

        if (smsCoordinates.size >= 2) {
            try {
                val routePoints = withContext(Dispatchers.IO) {
                    fetchGraphhopperRoute(smsCoordinates.map { it.latLong })
                }

                if (routePoints.isNotEmpty()) {

                    val mapView = mapViewRef.value ?: return@LaunchedEffect

                    // Remove old polyline if exists
                    polylineRef.value?.let {
                        mapView.layerManager.layers.remove(it)
                        polylineRef.value = null
                    }

                    // Create paint for the full route polyline
                    val linePaint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                        color = android.graphics.Color.BLUE
                        strokeWidth = 6f
                        setStyle(org.mapsforge.core.graphics.Style.STROKE)
                    }

                    // Create a polyline and add all route points in order
                    val routePolyline = Polyline(linePaint, AndroidGraphicFactory.INSTANCE)
                    routePoints.forEach { latLong ->
                        routePolyline.addPoint(latLong)
                    }

                    // Add the polyline to the map
                    mapView.layerManager.layers.add(routePolyline)
                    polylineRef.value = routePolyline

                    mapView.post {
                        mapView.repaint()
                    }

                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching or displaying route", e)
            }
        }

    }
}

fun fetchGraphhopperRoute(points: List<LatLong>): List<LatLong> {
    val client = OkHttpClient()
    if (points.size < 2) return emptyList()

    val url = buildString {
        append("http://127.0.0.1:8989/route?")
        points.forEach { p -> append("point=${p.latitude},${p.longitude}&") }
        append("profile=car&locale=en&points_encoded=false")
    }

    return try {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.e("MapsforgeMap", "Route request failed with code: ${response.code}")
            return emptyList()
        }

        val json = JSONObject(response.body!!.string())
        val path = json.getJSONArray("paths").getJSONObject(0)
        val coords = path.getJSONObject("points").getJSONArray("coordinates")

        val routePoints = mutableListOf<LatLong>()
        for (i in 0 until coords.length()) {
            val coord = coords.getJSONArray(i)
            val lon = coord.getDouble(0)
            val lat = coord.getDouble(1)
            routePoints.add(LatLong(lat, lon))
        }

        Log.d("MapsforgeMap", "Routing succeeded with ${routePoints.size} points.")

        routePoints
    } catch (e: Exception) {
        Log.e("MapsforgeMap", "Failed to fetch route", e)
        emptyList()
    }
}
