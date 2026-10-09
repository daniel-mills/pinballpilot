package app.pinballpilot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import app.pinballpilot.domain.*
import kotlin.math.hypot

@Composable fun PlayfieldViewer(pack: MachinePack, selected: String?, highlighted: Set<String>, onSelect: (String) -> Unit) {
    var scale by remember(pack.variantId) { mutableFloatStateOf(1f) }
    var offset by remember(pack.variantId) { mutableStateOf(Offset.Zero) }
    val context = LocalContext.current
    val loader = remember { ImageLoader.Builder(context).components { add(SvgDecoder.Factory()) }.build() }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Tap a shot · pinch to explore", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("Reset view") }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(pack.image.width.toFloat()/pack.image.height).background(Color(0xFF17243A)).graphicsLayer { clip = true }
            .pointerInput(pack.variantId) { detectTransformGestures { centroid, pan, zoom, _ ->
                val old = scale; val new = (old * zoom).coerceIn(1f,4f)
                val centre = Offset(size.width/2f,size.height/2f)
                offset = (offset + (centroid-centre-offset)*(1-new/old) + pan)
                val maxX = size.width*(new-1)/2; val maxY = size.height*(new-1)/2
                offset = Offset(offset.x.coerceIn(-maxX,maxX),offset.y.coerceIn(-maxY,maxY)); scale = new
            } }
            .pointerInput(pack, scale, offset) { detectTapGestures { tap ->
                val centre=Offset(size.width/2f,size.height/2f)
                val original=(tap-centre-offset)/scale+centre
                val p=Point(original.x/size.width,original.y/size.height)
                val hit=pack.shots.firstOrNull { it.geometry.polygon?.let { polygon -> Coordinates.contains(p,polygon) } == true }
                    ?: pack.shots.minByOrNull { hypot((it.geometry.point.x-p.x)*size.width,(it.geometry.point.y-p.y)*size.height) }?.takeIf { hypot((it.geometry.point.x-p.x)*size.width,(it.geometry.point.y-p.y)*size.height)*scale < 36.dp.toPx() }
                hit?.let { onSelect(it.id) }
            } }) {
            Box(Modifier.fillMaxSize().graphicsLayer { scaleX=scale;scaleY=scale;translationX=offset.x;translationY=offset.y }) {
                AsyncImage(model = if(pack.demo) "file:///android_asset/workshop.svg" else pack.image.path, imageLoader=loader, contentDescription="${pack.name} reference playfield", contentScale=ContentScale.FillBounds, modifier=Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize().semantics { contentDescription="Shot markers. A text list of shots follows the playfield." }) {
                    pack.shots.forEach { shot ->
                        val focus=shot.id==selected || shot.id in highlighted
                        val colour=if(focus) Color(0xFFFFC46B) else Color(0xFF93C7DB)
                        shot.geometry.polygon?.let { points ->
                            val path=Path().apply { points.forEachIndexed { i,p -> if(i==0) moveTo(p.x*size.width,p.y*size.height) else lineTo(p.x*size.width,p.y*size.height) }; close() }
                            drawPath(path,colour.copy(alpha=.18f)); drawPath(path,colour,style=Stroke(2.dp.toPx()/scale))
                        }
                        val centre=Offset(shot.geometry.point.x*size.width,shot.geometry.point.y*size.height)
                        drawCircle(if(focus) colour else Color(0xFF202C41),if(focus) 13.dp.toPx()/scale else 9.dp.toPx()/scale,centre)
                        drawCircle(colour,if(focus) 13.dp.toPx()/scale else 9.dp.toPx()/scale,centre,style=Stroke(2.dp.toPx()/scale))
                        drawCircle(if(focus) Color(0xFF202C41) else colour,3.dp.toPx()/scale,centre)
                    }
                }
            }
        }
    }
}
