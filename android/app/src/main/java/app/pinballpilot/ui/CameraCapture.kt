package app.pinballpilot.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable fun CameraCapture(onClose: () -> Unit, onPhotos: (List<String>) -> Unit, progressScan: Boolean = false) {
    val context=LocalContext.current; val owner=LocalLifecycleOwner.current
    val capture=remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val preview=remember { Preview.Builder().build() }
    val scope=rememberCoroutineScope()
    val photos=remember { mutableStateListOf<String>() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val currentOnPhotos by rememberUpdatedState(onPhotos)
    DisposableEffect(Unit) {
        var disposed=false
        val future=ProcessCameraProvider.getInstance(context)
        future.addListener({
            if(!disposed) try {
                provider=future.get()
                provider?.bindToLifecycle(owner,CameraSelector.DEFAULT_BACK_CAMERA,preview,capture)
                ready=true
            } catch(_:Exception) { error="Camera could not start. Close this view and try again." }
        },ContextCompat.getMainExecutor(context))
        onDispose { disposed=true; provider?.unbind(preview,capture) }
    }
    fun takePhotos() {
        scope.launch {
            busy=true; error=""
            try {
                if(progressScan) photos.clear()
                repeat(if(progressScan) 2 else 1) { index ->
                    if(index>0) delay(800)
                    photos+=capturePhoto(context,capture)
                }
                if(progressScan) currentOnPhotos(photos.toList())
            } catch(e:Exception) {
                if(e is CancellationException) throw e
                photos.clear(); error="The capture failed. Hold steady and try again."
            } finally { busy=false }
        }
    }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(if(progressScan) "Scan between balls" else "Photograph the machine",style=MaterialTheme.typography.headlineSmall)
            Text(if(progressScan) "Wait for the display to show your score or progress. Fill the view with the display and hold steady for two snapshots." else "Use a clear, steady view. You can send one or two photos. Game progress will need your confirmation.")
            if(progressScan) Text("The snapshots are sent for online analysis. Review each reading before saving; scan images are not saved to your account.",style=MaterialTheme.typography.bodySmall)
            AndroidView(factory={ctx -> PreviewView(ctx).also { preview.setSurfaceProvider(it.surfaceProvider) }},modifier=Modifier.weight(1f).fillMaxWidth())
            if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("${photos.size} of 2 snapshots ready")
            Button(enabled=ready&&!busy&&(progressScan||photos.size<2),onClick=::takePhotos,modifier=Modifier.fillMaxWidth()) {
                Text(if(busy) "Hold steady…" else if(progressScan) "Scan display" else "Take photo")
            }
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick=onClose) {Text("Cancel")}
                if(!progressScan) Button(onClick={onPhotos(photos.toList())},enabled=photos.isNotEmpty()&&!busy) {Text("Analyse photos")}
            }
        } }
    }
}

private suspend fun capturePhoto(context: Context, capture: ImageCapture): String {
    val file=File.createTempFile("capture-",".jpg",context.cacheDir)
    try {
        suspendCancellableCoroutine<Unit> { continuation ->
            continuation.invokeOnCancellation {file.delete()}
            capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),ContextCompat.getMainExecutor(context),object:ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result:ImageCapture.OutputFileResults) {
                    if(continuation.isActive) continuation.resume(Unit) else file.delete()
                }
                override fun onError(exception:ImageCaptureException) {
                    file.delete(); if(continuation.isActive) continuation.resumeWithException(exception)
                }
            })
        }
        return withContext(Dispatchers.IO) {
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeFile(file.path,bounds)
            val opts=BitmapFactory.Options().apply { inSampleSize=maxOf(1,maxOf(bounds.outWidth,bounds.outHeight)/1600) }
            val original=BitmapFactory.decodeFile(file.path,opts) ?: error("Invalid image")
            val bytes=try {
                val factor=minOf(1f,1600f/maxOf(original.width,original.height))
                val transform=Matrix().apply { postScale(factor,factor); postRotate(ExifInterface(file).rotationDegrees.toFloat()) }
                val resized=Bitmap.createBitmap(original,0,0,original.width,original.height,transform,true)
                try { ByteArrayOutputStream().use { resized.compress(Bitmap.CompressFormat.JPEG,80,it); it.toByteArray() } }
                finally { if(resized!==original) resized.recycle() }
            } finally { original.recycle() }
            check(bytes.size<2_000_000)
            "data:image/jpeg;base64,"+Base64.encodeToString(bytes,Base64.NO_WRAP)
        }
    } finally {file.delete()}
}
