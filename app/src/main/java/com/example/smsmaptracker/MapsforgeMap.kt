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

@Composable
fun MapsforgeMap() {
    val TAG = "MapsforgeMap"
    val context = LocalContext.current

    var mapCenter by remember { mutableStateOf(LatLong(36.7753026, 10.1120584)) }
    val previousZoomLevel = remember { mutableStateOf<Byte?>(null) }
    val mapViewRef = remember { mutableStateOf<MapView?>(null) }

    // <-- This is where we will store the parsed coords
    val smsCoordinates = remember { mutableStateListOf<LatLong>() }

    // Read latest 3 SMS coordinates on first composition
    LaunchedEffect(Unit) {
        try {
            Log.d(TAG, "Querying SMS inbox...")
            val cursor = context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf("address", "body", "date"),
                "address LIKE ?",
                arrayOf("%+21655895524%"),
                "date DESC"
            )

            if (cursor == null) {
                Log.e(TAG, "Cursor is null — no SMS permissions or content resolver failed.")
                return@LaunchedEffect
            }

            val coordinates = mutableListOf<LatLong>()

            cursor.use {
                while (it.moveToNext() && coordinates.size < 3) {
                    val body = it.getString(it.getColumnIndexOrThrow("body"))

                    val regex = Regex("""(-?\d{1,3}(?:\.\d+)?),\s*(-?\d{1,3}(?:\.\d+)?)""")
                    val match = regex.find(body)

                    if (match != null) {
                        val lat = match.groupValues[1].toDouble()
                        val lon = match.groupValues[2].toDouble()
                        val coord = LatLong(lat, lon)
                        Log.d(TAG, "Parsed coordinates: $lat, $lon")
                        coordinates.add(coord)
                    }
                }
            }

            if (coordinates.isNotEmpty()) {
                mapCenter = coordinates.first() // Use the latest as center
                smsCoordinates.clear()
                smsCoordinates.addAll(coordinates)
            } else {
                Log.w(TAG, "No valid coordinates found in last SMS messages.")
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
            mapCenter = mapCenter,
            previousZoomLevel = previousZoomLevel,
            markerManager = markerManager
        )
    }

    AndroidView(
        factory = { ctx ->
            mapViewProvider.createMapView { mapView ->
                mapViewRef.value = mapView

                // Update main dynamic marker to latest position
                val center = smsCoordinates.firstOrNull() ?: mapCenter
                markerManager.updateMarkerForZoom(mapView, 16, center)

                // Add all static SMS markers
                smsCoordinates.forEach { coord ->
                    markerManager.addStaticMarker(mapView, coord)
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
