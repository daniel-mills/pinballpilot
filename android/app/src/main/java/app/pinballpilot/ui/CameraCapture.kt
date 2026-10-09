package app.pinballpilot.ui

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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

@Composable fun CameraCapture(onClose: () -> Unit, onPhotos: (List<String>) -> Unit) {
    val context=LocalContext.current; val owner=LocalLifecycleOwner.current
    val capture=remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val worker=remember { Executors.newSingleThreadExecutor() }
    val photos=remember { mutableStateListOf<String>() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { provider?.unbindAll(); worker.shutdown() } }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(if(photos.isEmpty()) "Photograph the machine" else "Add the display or playfield",style=MaterialTheme.typography.headlineSmall)
            Text("Use a clear, steady view. You can send one or two photos. Game progress will need your confirmation.")
            AndroidView(factory={ctx -> PreviewView(ctx).also { view ->
                val future=ProcessCameraProvider.getInstance(ctx)
                future.addListener({ try { provider=future.get(); val preview=Preview.Builder().build().apply { setSurfaceProvider(view.surfaceProvider) }; provider?.bindToLifecycle(owner,CameraSelector.DEFAULT_BACK_CAMERA,preview,capture) } catch(_:Exception) { error="Camera could not start. Close this view and try again." } },ContextCompat.getMainExecutor(ctx))
            }},modifier=Modifier.weight(1f).fillMaxWidth())
            if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
            Text("${photos.size} of 2 photos ready")
            Button(enabled=!busy&&photos.size<2,onClick={
                busy=true; val file=File.createTempFile("capture-",".jpg",context.cacheDir)
                capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),worker,object:ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(result:ImageCapture.OutputFileResults) {
                        try {
                            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeFile(file.path,bounds)
                            val opts=BitmapFactory.Options().apply { inSampleSize=maxOf(1,maxOf(bounds.outWidth,bounds.outHeight)/1600) }
                            val original=BitmapFactory.decodeFile(file.path,opts) ?: error("Invalid image")
                            val factor=minOf(1f,1600f/maxOf(original.width,original.height))
                            val transform=Matrix().apply { postScale(factor,factor);postRotate(ExifInterface(file).rotationDegrees.toFloat()) }
                            val resized=Bitmap.createBitmap(original,0,0,original.width,original.height,transform,true)
                            val bytes=ByteArrayOutputStream().use { resized.compress(Bitmap.CompressFormat.JPEG,80,it);it.toByteArray() }
                            if(resized!==original) resized.recycle();original.recycle()
                            check(bytes.size<2_000_000) { "Photo is too large. Move closer and try again." }
                            val encoded="data:image/jpeg;base64,"+Base64.encodeToString(bytes,Base64.NO_WRAP)
                            ContextCompat.getMainExecutor(context).execute {photos+=encoded;busy=false}
                        } catch(_:Exception) {ContextCompat.getMainExecutor(context).execute {error="Photo could not be prepared. Try again.";busy=false}}
                        finally {file.delete()}
                    }
                    override fun onError(exception:ImageCaptureException) {file.delete();ContextCompat.getMainExecutor(context).execute {error="Photo capture failed. Try again.";busy=false}}
                })
            },modifier=Modifier.fillMaxWidth()) {Text(if(busy) "Preparing photo…" else "Take photo")}
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {OutlinedButton(onClick=onClose) {Text("Cancel")};Button(onClick={onPhotos(photos.toList())},enabled=photos.isNotEmpty()&&!busy) {Text("Analyse photos")} }
        } }
    }
}
