package com.example.smsmaptracker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
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
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.core.graphics.Paint
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.overlay.Polygon
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import androidx.core.content.ContextCompat

// Data classes and utility functions...

fun Double.format(digits: Int) = "%.${digits}f".format(this)

data class SmsCoordinate(val latLong: LatLong, val dateMillis: Long)

fun toEpochMillis(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long {
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

fun createTriangleAround(center: LatLong, sizeMeters: Double = 20.0): List<LatLong> {
    val latOffset = sizeMeters / 111000.0
    val lonOffset = sizeMeters / (111000.0 * Math.cos(Math.toRadians(center.latitude)))

    val p1 = LatLong(center.latitude + latOffset, center.longitude)
    val p2 = LatLong(center.latitude - latOffset / 2, center.longitude - lonOffset)
    val p3 = LatLong(center.latitude - latOffset / 2, center.longitude + lonOffset)
    return listOf(p1, p2, p3, p1) // close polygon
}
@SuppressLint("MissingPermission") // permission checked manually
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

    drawRouteTrigger: Boolean = false,
) {
    val TAG = "MapsforgeMap"
    val context = LocalContext.current

    // Current Location state
    var currentLocation by remember { mutableStateOf<LatLong?>(null) }

    // Location manager
    val locationManager = remember {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    // Permission launcher for Location
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) {
                startLocationUpdates(locationManager) { loc ->
                    currentLocation = LatLong(loc.latitude, loc.longitude)
                }
            } else {
                Toast.makeText(context, "Location permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    )

    // On Compose startup: check permission and request if needed
    LaunchedEffect(Unit) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates(locationManager) { loc ->
                currentLocation = LatLong(loc.latitude, loc.longitude)
            }
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

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
    val currentLocationPolygonRef = remember { mutableStateOf<Polygon?>(null) }
    val lastUserCenter = remember { mutableStateOf<LatLong?>(null) }
    val lastUserZoom = remember { mutableStateOf<Byte?>(null) }

    val smsCoordinates = remember { mutableStateListOf<SmsCoordinate>() }
    var showMarkerSelectDialog by remember { mutableStateOf(false) }
    val selectedCoordinates = remember { mutableStateListOf<SmsCoordinate>() }

    // Load SMS coordinates with date filter
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
                android.provider.Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf("address", "body", "date"),
                selectionBuilder.toString(),
                selectionArgs.toTypedArray(),
                "date DESC"
            )

            if (cursor == null) {
                Log.e(TAG, "Cursor is null — no SMS permissions or content resolver failed.")
                return@LaunchedEffect
            }

            val coords = mutableListOf<SmsCoordinate>()
            cursor.use {
                while (it.moveToNext()) {
                    val body = it.getString(it.getColumnIndexOrThrow("body"))
                    val dateMillis = it.getLong(it.getColumnIndexOrThrow("date"))
                    val regex = Regex("""(-?\d{1,3}(?:\.\d+)?),\s*(-?\d{1,3}(?:\.\d+)?)""")
                    val match = regex.find(body)
                    if (match != null) {
                        val lat = match.groupValues[1].toDouble()
                        val lon = match.groupValues[2].toDouble()
                        coords.add(SmsCoordinate(LatLong(lat, lon), dateMillis))
                    }
                }
            }

            if (coords.isNotEmpty()) {
                if (mapCenter == null) mapCenter = coords.first().latLong
                smsCoordinates.clear()
                smsCoordinates.addAll(coords)
                selectedCoordinates.clear()
                selectedCoordinates.addAll(coords)
            } else {
                Log.w(TAG, "No valid coordinates found in SMS within date filter.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading SMS", e)
        }
    }

    // Load map and theme files from cache or assets
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
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Required map files missing.")
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

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                mapViewProvider.createMapView(mapCenter ?: LatLong(36.7753026, 10.1120584)) { mapView ->
                    mapViewRef.value = mapView
                    mapView.setZoomLevel(8.toByte())
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        FloatingActionButton(
            onClick = { showMarkerSelectDialog = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
        ) {
            Icon(Icons.Filled.Place, contentDescription = "Select Markers")
        }
    }

    // Update SMS markers on map
    LaunchedEffect(smsCoordinates) {
        val mapView = mapViewRef.value ?: return@LaunchedEffect
        markerManager.clearAllMarkers(mapView)
        smsCoordinates.forEachIndexed { _, coord ->
            markerManager.addMarkerWithDate(mapView, coord.latLong, coord.dateMillis)
        }
    }

    // Draw route polyline
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

    // Draw red triangle polygon for current GPS location
    LaunchedEffect(currentLocation) {
        val mapView = mapViewRef.value ?: return@LaunchedEffect
        currentLocationPolygonRef.value?.let {
            mapView.layerManager.layers.remove(it)
            currentLocationPolygonRef.value = null
        }

        currentLocation?.let { loc ->
            val fillPaint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                color = android.graphics.Color.RED
            }
            val strokePaint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                color = android.graphics.Color.RED
                strokeWidth = 4f
            }

            val trianglePoints = createTriangleAround(loc, sizeMeters = 2.0)
            val polygon = Polygon(fillPaint, strokePaint, AndroidGraphicFactory.INSTANCE)
            trianglePoints.forEach { polygon.addPoint(it) }

            mapView.layerManager.layers.add(polygon)
            currentLocationPolygonRef.value = polygon
            mapView.post { mapView.repaint() }
        }
    }

    // Show marker selection dialog
    if (showMarkerSelectDialog) {
        AlertDialog(
            onDismissRequest = { showMarkerSelectDialog = false },
            title = { Text("Select Markers to Route") },
            text = {
                // Replace this inside your AlertDialog text LazyColumn
                LazyColumn {
                    items(smsCoordinates) { coord ->
                        val isSelected = selectedCoordinates.contains(coord)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    if (checked) selectedCoordinates.add(coord)
                                    else selectedCoordinates.remove(coord)
                                }
                            )
                            Text(
                                text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(coord.dateMillis)),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }

            },
            confirmButton = {
                Button(onClick = { showMarkerSelectDialog = false }) {
                    Text("OK")
                }
            }
        )
    }
}

// Helper function to start GPS location updates with callback
@SuppressLint("MissingPermission")
fun startLocationUpdates(locationManager: LocationManager, onLocationChanged: (Location) -> Unit) {
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onLocationChanged(location)
        }

        override fun onProviderDisabled(provider: String) {}
        override fun onProviderEnabled(provider: String) {}
    }
    locationManager.requestLocationUpdates(
        LocationManager.GPS_PROVIDER,
        1000L,
        0f,
        listener,
        Looper.getMainLooper()
    )
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