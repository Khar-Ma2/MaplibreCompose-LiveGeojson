package org.adsb.bikenavigation.mp.presentation.main

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import co.touchlab.kermit.Logger
import com.kharma.gdl90.decoder.messages.TrafficMessage
import com.kmp.gps.repository.LocationRepository
import com.kmp.maplibre.R
import com.kmp.maplibre.buildMbTilesMapStyle
import com.kmp.maplibre.getFileFromAssets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.adsb.bikenavigation.R.drawable.ic_transparent
import org.adsb.bikenavigation.theme.AppTheme
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.maps.Style.Builder
import org.maplibre.android.maps.Style.OnStyleLoaded
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.utils.BitmapUtils
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


/**
 * Remembers a MapView and gives it the lifecycle of the current LifecycleOwner
 */
@Composable
fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }

    // Makes MapView follow the lifecycle of this composable
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val lifecycleObserver = getMapLifecycleObserver(mapView)
        lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycle.removeObserver(lifecycleObserver)
        }
    }
    return mapView
}

fun getMapLifecycleObserver(mapView: MapView): LifecycleEventObserver =
    LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_CREATE -> mapView.onCreate(Bundle())
            Lifecycle.Event.ON_START -> mapView.onStart()
            Lifecycle.Event.ON_RESUME -> mapView.onResume()
            Lifecycle.Event.ON_PAUSE -> mapView.onPause()
            Lifecycle.Event.ON_STOP -> mapView.onStop()
            Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
            else -> throw IllegalStateException()
        }
    }

suspend inline fun MapView.awaitMap(): MapLibreMap =
    suspendCoroutine { continuation ->
        getMapAsync { map ->
            continuation.resume(map)
        }
    }

fun MapLibreMap.setStyleFromAssets(
    context: Context,
    mbtilesAssetName: String = "planet.mbtiles",
    styleAssetName: String = "dark_style.json",
    builderBlock: (Builder) -> Builder,
    onStyleLoaded: OnStyleLoaded,
) {
    val builder = builderBlock(
        this.buildMbTilesMapStyle(
            context.assets.open(styleAssetName),
            getFileFromAssets(context, mbtilesAssetName),
            context.filesDir.absolutePath,
            styleAssetName,
        )
    )
    this.setStyle(builder, onStyleLoaded)
}

//TODO temp, delete when MapLibreMP is done
@Composable
fun MapComposable(
    onMapReady: (map: MapLibreMap, style: Style) -> Unit,
    styleBuilderBlock: (builder: Builder) -> Builder,
    updateMap: (map: MapLibreMap?, style: Style?) -> Unit,
) {
    val c = LocalContext.current
    val cScope = rememberCoroutineScope()

    MapLibre.getInstance(c)
    val view = rememberMapViewWithLifecycle()
    val map = remember { mutableStateOf<MapLibreMap?>(null) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            Logger.i(tag = "OBJECT", messageString = "factory composable")
            cScope.launch {
                val libreMap = view.awaitMap()
                map.value = libreMap
                libreMap.setStyleFromAssets(
                    context = context,
                    builderBlock = styleBuilderBlock,
                    onStyleLoaded = { style ->
                        onMapReady(map.value!!, style)
                    }
                )
            }
            return@AndroidView view
        },
        update = { mapView ->
            Logger.i(tag = "OBJECT", messageString = "update composable")
            updateMap(map.value, map.value?.style)
        }
    )
}

class MainScreen() : Screen {

    //private lateinit var maplibreMap: MapLibreMap
    //private lateinit var mapStyle: Style

    private val planesFeatures = MutableStateFlow<FeatureCollection?>(null)

    @SuppressLint("MissingPermission")
    @Composable
    override fun Content() {
        Logger.i(tag = "OBJECT", messageString = "composition/recomposition")
        val context = LocalContext.current
        val screenModel = rememberScreenModel { MainScreenModel() }

        val screenState = screenModel.state.collectAsStateWithLifecycle()

        val location =
            LocationRepository.raw.map { it as Location }.collectAsStateWithLifecycle(null)


        LaunchedEffect(screenState.value.planes) {
            planesFeatures.update { screenState.value.planes }
        }
        AppTheme {
            MapComposable(
                styleBuilderBlock = { builder: Builder ->
                    return@MapComposable context.builderBlock(builder)
                },
                onMapReady = { map, style ->
                    //maplibreMap = map
                    Logger.i(tag = "OBJECT", messageString = "init map $map")
                    //mapStyle = style
                    Logger.i(tag = "OBJECT", messageString = "init style $style")
/*

                    val handler = Handler(Looper.getMainLooper())
                    val r = RefreshGeoJsonRunnable(map, handler)
                    handler.postDelayed(r, 2000L)
*/

                    val locationComponent = map.locationComponent
                    val locationComponentOptions = LocationComponentOptions.builder(context)
                        .backgroundDrawable(ic_transparent)
                        .gpsDrawable(ic_transparent)
                        .foregroundDrawable(ic_transparent)
                        .bearingDrawable(R.drawable.user_marker)
                        /*.compassAnimationEnabled(true)
                        .pulseEnabled(true)
                        .pulseFadeEnabled(true)
                        .pulseMaxRadius(2000f)
                        .accuracyAnimationEnabled(true)
                        */.maxZoomIconScale(10f)
                        .minZoomIconScale(.5f)
                        .build()

                    val locationComponentActivationOptions =
                        LocationComponentActivationOptions.builder(context, map.style!!)
                            .locationComponentOptions(locationComponentOptions)
                            .build()


                    locationComponent.apply {
                        activateLocationComponent(locationComponentActivationOptions)
                        isLocationComponentEnabled = true
                        forceLocationUpdate(location.value)
                        renderMode = RenderMode.COMPASS
                    }

                },
                updateMap = { map, style ->
                    screenState.value.planes
                        ?.let { features ->
                            style?.getSourceAs<GeoJsonSource>(GeoJsonSources.PLANES_SOURCE).also {
                                Logger.i(
                                    tag = "VALUE",
                                    messageString = "source: $it // features : $features"
                                )
                            }
                                ?.setGeoJson(features)


                        }
                }
            )
        }
    }


    private inner class RefreshGeoJsonRunnable(
        private val map: MapLibreMap,
        private val handler: Handler,
    ) : Runnable {
        override fun run() {
            map.style?.getSourceAs<GeoJsonSource>(GeoJsonSources.PLANES_SOURCE)?.let { source ->
                planesFeatures.value?.let { featureCollection ->
                    Logger.i(
                        tag = "VALUE",
                        messageString = "source: $source // features : $featureCollection"
                    )
                    source.setGeoJson(featureCollection)
                }
            }
            handler.postDelayed(this, 2000L)
        }
    }


    fun Context.maplibreImage(@DrawableRes resId: Int): Bitmap {
        return BitmapUtils.getBitmapFromDrawable(
            ResourcesCompat.getDrawable(
                this.resources,
                resId,
                null
            )
        )!!
    }


    val builderBlock: Context.(Builder) -> Builder = { builder: Builder ->
        builder.withImage("user", this.maplibreImage(R.drawable.user_marker), false)
            .withImage("plane", this.maplibreImage(R.drawable.plane), false)
            .withSource(GeoJsonSource(GeoJsonSources.PLANES_SOURCE))
            .withLayers(
                SymbolLayer(MapLayers.PLANES_LAYER, GeoJsonSources.PLANES_SOURCE)
                    .apply {
                        withProperties(
                            PropertyFactory.iconImage("plane"),
                            PropertyFactory.iconSize(.4f),
                            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
                            PropertyFactory.iconRotate(Expression.get(MaplibreProperty.Planes.TRACK_ANGLE)),
                            PropertyFactory.iconAllowOverlap(true),
                        )
                    },
            )


    }
}


