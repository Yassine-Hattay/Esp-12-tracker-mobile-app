//MarkerManger.kt

package com.example.smsmaptracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.android.view.MapView

class MarkerManager(private val context: Context) {
    private val TAG = "MarkerManager"
    private var currentMarker: Marker? = null

    fun addStaticMarker(mapView: MapView, position: LatLong) {
        try {
            val input = context.assets.open("symbols/marker.png")
            val originalBitmap = BitmapFactory.decodeStream(input)
            input.close()

            val size = 64
            val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, size, size, true)
            val drawable = BitmapDrawable(context.resources, scaledBitmap)
            val mapsforgeBitmap = AndroidGraphicFactory.convertToBitmap(drawable)

            val marker = Marker(position, mapsforgeBitmap, (size * 0.5f).toInt(), (size * -0.4f).toInt())

            mapView.layerManager.layers.add(marker)
        } catch (e: Exception) {
            Log.e("MarkerManager", "Failed to add static marker", e)
        }
    }

    fun updateMarkerForZoom(
        mapView: MapView,
        zoom: Byte,
        center: LatLong
    ) {
        mapView.post {
            // Remove old marker
            currentMarker?.let { mapView.layerManager.layers.remove(it) }
            currentMarker = null

            try {
                val input = context.assets.open("symbols/marker.png")
                val originalBitmap = BitmapFactory.decodeStream(input)
                input.close()

                if (originalBitmap != null) {
                    val baseSize = 24
                    val maxSize = 150
                    val zoomLevel = zoom.toInt().coerceIn(0, 20)
                    val size = (baseSize + ((maxSize - baseSize) * zoomLevel / 20f)).toInt()

                    val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, size, size, true)
                    val drawable = BitmapDrawable(context.resources, scaledBitmap)
                    val mapsforgeBitmap = AndroidGraphicFactory.convertToBitmap(drawable)

                    val horizontalOffset = (size / 2f) - (size * 0.33f)
                    val verticalOffset = size - (size * 1.0f)

                    val newMarker = Marker(center, mapsforgeBitmap, horizontalOffset.toInt(), verticalOffset.toInt())

                    mapView.layerManager.layers.add(newMarker)
                    currentMarker = newMarker

                    Log.d(TAG, "Marker updated for zoom $zoom with size $size")
                } else {
                    Log.e(TAG, "Failed to decode bitmap for marker")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create marker for zoom", e)
            }
        }
    }
}
