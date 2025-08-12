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

    fun addMarker(mapView: MapView, position: LatLong) {
        // Your existing logic to create a simple marker (or adapt from addStaticMarker)
        val input = context.assets.open("symbols/marker.png")
        val originalBitmap = BitmapFactory.decodeStream(input)
        input.close()

        val size = 64
        val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, size, size, true)
        val drawable = BitmapDrawable(context.resources, scaledBitmap)
        val mapsforgeBitmap = AndroidGraphicFactory.convertToBitmap(drawable)

        val marker = Marker(position, mapsforgeBitmap, (size * 0.5f).toInt(), (size * -0.4f).toInt())

        mapView.layerManager.layers.add(marker)
        markers.add(marker) // or a separate list if you want to distinguish placed markers
    }

}
