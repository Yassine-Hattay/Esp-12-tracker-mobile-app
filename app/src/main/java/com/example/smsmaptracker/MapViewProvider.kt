package com.example.smsmaptracker

import android.content.Context
import android.util.Log
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.XmlRenderTheme
import org.mapsforge.map.rendertheme.XmlRenderThemeMenuCallback
import org.mapsforge.map.rendertheme.XmlThemeResourceProvider
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.view.InputListener
import java.io.File
import java.io.InputStream
import androidx.compose.runtime.MutableState
import android.view.ViewGroup

class MapViewProvider(
    private val context: Context,
    private val mapFile: File,
    private val renderThemeFile: File,
    private val previousZoomLevel: MutableState<Byte?>,
    private val markerManager: MarkerManager
) {

    private var _mapCenter: LatLong? = null

    var mapCenter: LatLong?
        get() = _mapCenter
        set(value) {
            _mapCenter = value
        }

    fun createMapView(
        initialCenter: LatLong,
        onMapReady: (MapView) -> Unit
    ): MapView {
        val mapView = MapView(context)
        mapView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        mapView.setCenter(initialCenter)
        mapView.setZoomLevel(16.toByte())

        val tileCache: TileCache = AndroidUtil.createTileCache(
            context,
            "mapcache",
            mapView.model.displayModel.tileSize,
            1.0f,
            mapView.model.frameBufferModel.overdrawFactor
        )

        val renderTheme = object : XmlRenderTheme {
            private var menuCallback: XmlRenderThemeMenuCallback? = null
            private var resourceProvider: XmlThemeResourceProvider? = null

            override fun getRenderThemeAsStream(): InputStream = renderThemeFile.inputStream()

            override fun getRelativePathPrefix(): String = ""

            override fun getMenuCallback(): XmlRenderThemeMenuCallback =
                menuCallback ?: XmlRenderThemeMenuCallback { emptySet() }

            override fun getResourceProvider(): XmlThemeResourceProvider =
                resourceProvider ?: XmlThemeResourceProvider { relativePath, _ ->
                    try {
                        if (relativePath != null) {
                            val path = if (relativePath.startsWith("/")) relativePath.substring(1) else relativePath
                            context.assets.open(path)
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                }

            override fun setMenuCallback(menuCallback: XmlRenderThemeMenuCallback) {
                this.menuCallback = menuCallback
            }

            override fun setResourceProvider(resourceProvider: XmlThemeResourceProvider) {
                this.resourceProvider = resourceProvider
            }
        }

        val tileRendererLayer = TileRendererLayer(
            tileCache,
            MapFile(mapFile),
            mapView.model.mapViewPosition,
            AndroidGraphicFactory.INSTANCE
        ).apply {
            setXmlRenderTheme(renderTheme)
            isVisible = true
        }

        mapView.layerManager.layers.add(tileRendererLayer)

        mapView.addInputListener(object : InputListener {
            override fun onMoveEvent() {
                Log.d("MapsforgeMap", "Map moved!")
            }

            override fun onZoomEvent() {
                val currentZoom = mapView.model.mapViewPosition.zoomLevel
                val previous = previousZoomLevel.value

                if (previous != null && currentZoom != previous) {
                    if (currentZoom > previous) {
                        Log.d("MapsforgeMap", "Zoomed in: $previous → $currentZoom")
                    } else {
                        Log.d("MapsforgeMap", "Zoomed out: $previous → $currentZoom")
                    }

                } else if (previous == null) {
                    Log.d("MapsforgeMap", "Initial zoom level: $currentZoom")

                }
                previousZoomLevel.value = currentZoom
            }
        })

        onMapReady(mapView)
        return mapView
    }
}