package com.example.smsmaptracker

import android.provider.Telephony
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place


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

    drawRouteTrigger: Boolean = false  // NEW parameter to trigger route drawing
) {
    val TAG = "MapsforgeMap"
    val context = LocalContext.current

    val startDateEpochMillis = if (startYear != null && startMonth != null && startDay != null) {
        toEpochMillis(startYear, startMonth, startDay, startHour, startMinute)
    } else null

    val endDateEpochMillis = if (endYear != null && endMonth != null && endDay != null) {
        toEpochMillis(endYear, endMonth, endDay, endHour, endMinute)
    } else null

    var mapCenter by remember { mutableStateOf<LatLong?>(null) }

    val previousZoomLevel = remember { mutableStateOf<Byte?>(null) }
    val mapViewRef = remember { mutableStateOf<MapView?>(null) }
    val polylineRef = remember { mutableStateOf<Polyline?>(null) }

    val smsCoordinates = remember { mutableStateListOf<SmsCoordinate>() }

    val lastUserCenter = remember { mutableStateOf<LatLong?>(null) }
    val lastUserZoom = remember { mutableStateOf<Byte?>(null) }

    // Dialog visible state
    var showMarkerSelectDialog by remember { mutableStateOf(false) }
    // Selected markers for routing
    val selectedCoordinates = remember { mutableStateListOf<SmsCoordinate>() }

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
                if (mapCenter == null) {
                    mapCenter = coordinates.first().latLong
                }
                smsCoordinates.clear()
                smsCoordinates.addAll(coordinates)

                // Initially select all coordinates
                selectedCoordinates.clear()
                selectedCoordinates.addAll(coordinates)
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
            this.mapCenter = mapCenter ?: LatLong(36.7753026, 10.1120584)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                mapViewProvider.createMapView(mapCenter ?: LatLong(36.7753026, 10.1120584)) { mapView ->
                    mapViewRef.value = mapView
                    mapView.setZoomLevel(8.toByte())
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // FloatingActionButton at bottom center
        FloatingActionButton(
            onClick = { showMarkerSelectDialog = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
        ) {
            Icon(Icons.Filled.Place, contentDescription = "Select Markers")
        }
    }

    // Update markers on map when smsCoordinates change
    LaunchedEffect(smsCoordinates) {
        val mapView = mapViewRef.value ?: return@LaunchedEffect
        markerManager.clearAllMarkers(mapView)
        smsCoordinates.forEachIndexed { index, smsCoord ->
            Log.d(TAG, "Adding marker #$index at ${smsCoord.latLong} with date ${smsCoord.dateMillis}")
            markerManager.addMarkerWithDate(mapView, smsCoord.latLong, smsCoord.dateMillis)
        }
    }

    // Draw or update route only when drawRouteTrigger toggled or selectedCoordinates change
    LaunchedEffect(drawRouteTrigger, selectedCoordinates.toList()) {
        val mapView = mapViewRef.value ?: return@LaunchedEffect
        polylineRef.value?.let {
            mapView.layerManager.layers.remove(it)
            polylineRef.value = null
        }

        if (drawRouteTrigger && selectedCoordinates.size >= 2) {
            try {
                val routePoints = withContext<List<LatLong>>(Dispatchers.IO) {
                    fetchGraphhopperRoute(selectedCoordinates.map { it.latLong })
                }

                if (routePoints.isNotEmpty()) {
                    val linePaint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                        color = android.graphics.Color.BLUE
                        strokeWidth = 6f
                        setStyle(org.mapsforge.core.graphics.Style.STROKE)
                    }

                    val routePolyline = Polyline(linePaint, AndroidGraphicFactory.INSTANCE)
                    routePoints.forEach { latLong -> routePolyline.addPoint(latLong) }

                    mapView.layerManager.layers.add(routePolyline)
                    polylineRef.value = routePolyline

                    lastUserCenter.value?.let { center ->
                        lastUserZoom.value?.let { zoom ->
                            try {
                                mapView.setCenter(center)
                                mapView.setZoomLevel(zoom.toByte())
                            } catch (t: Throwable) {
                                Log.w(TAG, "Could not restore center/zoom after route: ${t.message}")
                            }
                        }
                    }

                    mapView.post { mapView.repaint() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching or displaying route", e)
            }
        }
    }

    // Marker selection dialog
    if (showMarkerSelectDialog) {
        AlertDialog(
            onDismissRequest = { showMarkerSelectDialog = false },
            title = { Text("Select Markers to Route") },
            text = {
                if (smsCoordinates.isEmpty()) {
                    Text("No markers available in the selected date range.")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(smsCoordinates) { coord ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                val checked = selectedCoordinates.contains(coord)
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        if (it) selectedCoordinates.add(coord)
                                        else selectedCoordinates.remove(coord)
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Lat: ${coord.latLong.latitude.format(4)}, Lon: ${coord.latLong.longitude.format(4)} - " +
                                            "Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(coord.dateMillis))}"
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showMarkerSelectDialog = false }) {
                    Text("Done")
                }
            }
        )
    }
}

// Helper extension function to format doubles nicely
fun Double.format(digits: Int) = "%.${digits}f".format(this)

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
