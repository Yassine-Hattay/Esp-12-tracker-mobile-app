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
import androidx.compose.foundation.Canvas
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
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.overlay.Polygon
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Flag
import kotlin.math.cos

// Utility extension for formatting doubles
fun Double.format(digits: Int) = "%.${digits}f".format(this)

// Data class for SMS coordinate
data class SmsCoordinate(val latLong: LatLong, val dateMillis: Long)

// Converts date to epoch millis
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

// Creates a triangle polygon around a center LatLong
fun createTriangleAround(center: LatLong, sizeMeters: Double = 20.0): List<LatLong> {
    val latOffset = sizeMeters / 111000.0
    val lonOffset = sizeMeters / (111000.0 * cos(Math.toRadians(center.latitude)))

    val p1 = LatLong(center.latitude + latOffset, center.longitude)
    val p2 = LatLong(center.latitude - latOffset / 2, center.longitude - lonOffset)
    val p3 = LatLong(center.latitude - latOffset / 2, center.longitude + lonOffset)
    return listOf(p1, p2, p3, p1) // closed polygon
}
@SuppressLint("MissingPermission")
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

    var currentLocation by remember { mutableStateOf<LatLong?>(null) }
    val locationManager = remember {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

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

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
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

    var myLocationSelected by remember { mutableStateOf(false) }
    val myLocationCoordinate = currentLocation?.let { loc -> SmsCoordinate(loc, -1L) }

    LaunchedEffect(currentLocation) {
        if (myLocationSelected && currentLocation != null) {
            selectedCoordinates.removeAll { it.dateMillis == -1L }
            selectedCoordinates.add(myLocationCoordinate!!)
        }
    }

    LaunchedEffect(startDateEpochMillis, endDateEpochMillis) {
        try {
            Log.d(
                TAG,
                "Querying SMS inbox with date filter [$startDateEpochMillis .. $endDateEpochMillis]"
            )

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
                myLocationSelected = false
            } else {
                Log.w(TAG, "No valid coordinates found in SMS within date filter.")
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

    val placedMarkers = remember { mutableStateListOf<SmsCoordinate>() }
    var showAimer by remember { mutableStateOf(false) }

    // New state to track "keep trying zoom"
    var zoomingToLocation by remember { mutableStateOf(false) }
    var lastToastTime by remember { mutableLongStateOf(0L) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { _ ->
                mapViewProvider.createMapView(
                    mapCenter ?: LatLong(
                        36.7753026,
                        10.1120584
                    )
                ) { mapView ->
                    mapViewRef.value = mapView
                    mapView.setZoomLevel(8.toByte())
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (showAimer) {
            Box(
                Modifier.fillMaxSize()
            ) {
                Canvas(
                    modifier = Modifier
                        .size(40.dp)
                        .align(Alignment.Center)
                ) {
                    val strokeWidth = 4f
                    drawLine(
                        color = androidx.compose.ui.graphics.Color.Red,
                        strokeWidth = strokeWidth,
                        start = androidx.compose.ui.geometry.Offset(0f, 0f),
                        end = androidx.compose.ui.geometry.Offset(size.width, size.height)
                    )
                    drawLine(
                        color = androidx.compose.ui.graphics.Color.Red,
                        strokeWidth = strokeWidth,
                        start = androidx.compose.ui.geometry.Offset(size.width, 0f),
                        end = androidx.compose.ui.geometry.Offset(0f, size.height)
                    )
                }
            }
        }

        // FAB: Marker selection dialog
        FloatingActionButton(
            onClick = { showMarkerSelectDialog = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(x = -45.dp)
                .padding(bottom = 50.dp)
        ) {
            Icon(Icons.Filled.Place, contentDescription = "Select Markers")
        }

        // FAB: Zoom to My Location with retry until location available
        FloatingActionButton(
            onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                    zoomingToLocation = true
                } else {
                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = (-80).dp)
                .padding(end = 0.dp)
        ) {
            Icon(Icons.Filled.MyLocation, contentDescription = "Zoom to My Location")
        }

        // FAB: Toggle Aimer / Place Marker
        FloatingActionButton(
            onClick = {
                val mapView = mapViewRef.value ?: return@FloatingActionButton
                if (showAimer) {
                    val center = mapView.model.mapViewPosition.center
                    val newMarker = SmsCoordinate(center, -2L)
                    placedMarkers.add(newMarker)
                    markerManager.addMarker(mapView, center)
                    Toast.makeText(
                        context,
                        "Marker placed at: ${center.latitude.format(6)}, ${center.longitude.format(6)}",
                        Toast.LENGTH_SHORT
                    ).show()
                    showAimer = false
                } else {
                    showAimer = true
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(x = 45.dp)
                .padding(bottom = 50.dp)
        ) {
            Icon(Icons.Filled.Flag, contentDescription = "Place Flag Marker")
        }
    }

    // Keep trying to zoom to location until available
    LaunchedEffect(zoomingToLocation, currentLocation) {
        if (zoomingToLocation) {
            val loc = currentLocation
            val mapView = mapViewRef.value
            if (loc != null && mapView != null) {
                mapView.setCenter(loc)
                mapView.setZoomLevel(18.toByte())
                mapView.post { mapView.repaint() }
                zoomingToLocation = false
            } else {
                val now = System.currentTimeMillis()
                if (now - lastToastTime > 2000) {
                    Toast.makeText(context, "Waiting for GPS location...", Toast.LENGTH_SHORT).show()
                    lastToastTime = now
                }
            }
        }
    }

    // Update SMS markers on map
    LaunchedEffect(smsCoordinates) {
        val mapView = mapViewRef.value ?: return@LaunchedEffect
        markerManager.clearAllMarkers(mapView)
        smsCoordinates.forEach { coord ->
            markerManager.addMarkerWithDate(mapView, coord.latLong, coord.dateMillis)
        }
    }

    // Update placed markers on map when list changes
    LaunchedEffect(placedMarkers.toList()) {
        val mapView = mapViewRef.value ?: return@LaunchedEffect
        placedMarkers.forEach { coord ->
            markerManager.addMarker(mapView, coord.latLong)
        }
    }

    // Draw route polyline when triggered or selected coords change
    LaunchedEffect(drawRouteTrigger, selectedCoordinates.toList()) {
        val mapView = mapViewRef.value ?: return@LaunchedEffect
        polylineRef.value?.let {
            mapView.layerManager.layers.remove(it)
            polylineRef.value = null
        }
        if (drawRouteTrigger && selectedCoordinates.size >= 2) {
            try {
                val routePoints = withContext(Dispatchers.IO) {
                    fetchGraphhopperRoute(selectedCoordinates.map { it.latLong })
                }
                if (routePoints.isNotEmpty()) {
                    val linePaint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                        color = android.graphics.Color.BLUE
                        strokeWidth = 6f
                        setStyle(Style.STROKE)
                    }
                    val routePolyline = Polyline(linePaint, AndroidGraphicFactory.INSTANCE)
                    routePoints.forEach { latLong -> routePolyline.addPoint(latLong) }
                    mapView.layerManager.layers.add(routePolyline)
                    polylineRef.value = routePolyline

                    lastUserCenter.value?.let { center ->
                        lastUserZoom.value?.let { zoom ->
                            try {
                                mapView.setCenter(center)
                                mapView.setZoomLevel(zoom)
                            } catch (t: Throwable) {
                                Log.w(
                                    TAG,
                                    "Could not restore center/zoom after route: ${t.message}"
                                )
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

    // Draw current GPS location as red triangle
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

    // Marker select dialog
    if (showMarkerSelectDialog) {
        AlertDialog(
            onDismissRequest = { showMarkerSelectDialog = false },
            title = { Text("Select Markers to Route") },
            text = {
                LazyColumn {
                    myLocationCoordinate?.let { myLoc ->
                        val isSelected = selectedCoordinates.contains(myLoc)
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        myLocationSelected = checked
                                        if (checked) {
                                            selectedCoordinates.add(myLoc)
                                        } else {
                                            selectedCoordinates.removeAll { it.dateMillis == -1L }
                                        }
                                    }
                                )
                                Text(
                                    text = "My Location",
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }
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
                                text = SimpleDateFormat(
                                    "yyyy-MM-dd HH:mm",
                                    Locale.getDefault()
                                ).format(Date(coord.dateMillis)),
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                    items(placedMarkers) { coord ->
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
                                text = "Placed Marker at ${coord.latLong.latitude.format(6)}, ${coord.latLong.longitude.format(6)}",
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


// Starts location updates with callback
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

// Fetches a route from Graphhopper API given a list of points
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

