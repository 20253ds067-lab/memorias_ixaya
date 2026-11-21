package mx.edu.utez.memorias.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

@Composable
fun MapLibreLocationView(
    latitud: Double,
    longitud: Double,
    markerTitle: String = "",
    interactive: Boolean = false,
    onLocationSelected: ((Double, Double) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isMapReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            MapLibre.getInstance(context)
            isMapReady = true
        } catch (_: Exception) {
            isMapReady = false
        }
    }

    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))) {
        if (isMapReady && latitud != 0.0 && longitud != 0.0) {
            AndroidView(
                factory = { ctx ->
                    MapView(ctx).apply {
                        onCreate(null)
                        getMapAsync { map ->
                            map.setStyle("https://demotiles.maplibre.org/style.json") { _ ->
                                val position = LatLng(latitud, longitud)
                                map.cameraPosition = CameraPosition.Builder()
                                    .target(position)
                                    .zoom(14.0)
                                    .build()

                                map.uiSettings.setAllGesturesEnabled(interactive)

                                if (interactive && onLocationSelected != null) {
                                    map.addOnMapClickListener { point ->
                                        onLocationSelected(point.latitude, point.longitude)
                                        true
                                    }
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { mapView ->
                    mapView.getMapAsync { map ->
                        val position = LatLng(latitud, longitud)
                        map.cameraPosition = CameraPosition.Builder()
                            .target(position)
                            .zoom(14.0)
                            .build()
                    }
                }
            )
        }

        // Overlay con información de coordenadas
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (markerTitle.isNotBlank()) "$markerTitle (${"%.4f".format(latitud)}, ${"%.4f".format(longitud)})"
                       else "(${latitud}, ${longitud})",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
