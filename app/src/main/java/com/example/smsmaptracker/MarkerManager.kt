package com.example.smsmaptracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.android.view.MapView
import java.text.SimpleDateFormat
import java.util.Locale

class MarkerManager(private val context: Context) {
    private val TAG = "MarkerManager"
    private var currentMarker: Marker? = null
    private val markers = mutableListOf<Marker>() // store all markers

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
            markers.add(marker) // store it
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add static marker", e)
        }
    }

    fun addMarkerWithDate(mapView: MapView, position: LatLong, dateMillis: Long) {
        try {
            val input = context.assets.open("symbols/marker.png")
            val originalBitmap = BitmapFactory.decodeStream(input)
            input.close()

            val size = 64
            val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, size, size, true)

            val dateText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(dateMillis)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }

            val textWidth = paint.measureText(dateText)
            val textHeight = paint.fontMetrics.bottom - paint.fontMetrics.top

            val padding = 8f
            val bgRectWidth = textWidth + padding * 2
            val bgRectHeight = textHeight + padding

            val totalHeight = size + bgRectHeight.toInt() + 8
            val bitmapWidth = size.coerceAtLeast(bgRectWidth.toInt())
            val combinedBitmap = Bitmap.createBitmap(bitmapWidth, totalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(combinedBitmap)

            val bgRect = RectF(
                (bitmapWidth - bgRectWidth) / 2f,
                0f,
                (bitmapWidth + bgRectWidth) / 2f,
                bgRectHeight
            )
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.RED
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(bgRect, 6f, 6f, bgPaint)

            val textBaseline = bgRectHeight / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(dateText, bitmapWidth / 2f, textBaseline, paint)

            val markerIconY = bgRectHeight + 8f
            canvas.drawBitmap(scaledBitmap, (bitmapWidth - size) / 2f, markerIconY, null)

            val drawable = BitmapDrawable(context.resources, combinedBitmap)
            val mapsforgeBitmap = AndroidGraphicFactory.convertToBitmap(drawable)

            val horizontalOffset = (bitmapWidth / 2f) - (size * 1f)
            val verticalOffset = totalHeight - (size * 2.0f)

            val marker = Marker(position, mapsforgeBitmap, horizontalOffset.toInt(), verticalOffset.toInt())
            mapView.layerManager.layers.add(marker)
            markers.add(marker) // store it

        } catch (e: Exception) {
            Log.e(TAG, "Failed to add marker with date label", e)
        }
    }

    fun updateMarkerForZoom(mapView: MapView, zoom: Byte, center: LatLong) {
        mapView.post {
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

                    val horizontalOffset = (size / 2f) - (size * 0.44f)
                    val verticalOffset = size - (size * 1.0f)

                    val newMarker = Marker(center, mapsforgeBitmap, horizontalOffset.toInt(), verticalOffset.toInt())

                    mapView.layerManager.layers.add(newMarker)
                    currentMarker = newMarker

                    Log.d(TAG, "Marker updated for zoom $zoom with size $size")
                } else {
                    Log.e(TAG, "Failed to decode bitmap for marker")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create marker for zoom")
            }
        }
    }

    // 🗑 Remove a specific marker
    fun removeMarker(mapView: MapView, marker: Marker) {
        mapView.layerManager.layers.remove(marker)
        markers.remove(marker)
    }

    // 🗑 Remove all markers added by this manager
    fun clearAllMarkers(mapView: MapView) {
        for (marker in markers) {
            mapView.layerManager.layers.remove(marker)
        }
        markers.clear()

        // Also remove zoom marker
        currentMarker?.let { mapView.layerManager.layers.remove(it) }
        currentMarker = null
    }
}
