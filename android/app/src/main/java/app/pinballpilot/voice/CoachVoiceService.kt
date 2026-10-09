package app.pinballpilot.voice

import android.app.*
import android.content.Intent
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.AudioDeviceCallback
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import app.pinballpilot.MainActivity
import app.pinballpilot.domain.VoiceCommand
import app.pinballpilot.domain.VoiceCommands
import app.pinballpilot.ui.CoachSession
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import javax.inject.Inject

@AndroidEntryPoint class CoachVoiceService : Service() {
    @Inject lateinit var coach: CoachSession
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var model:Model?=null
    private var wake:SpeechService?=null
    private var wakeRecognizer:Recognizer?=null
    private var recognizer:SpeechRecognizer?=null
    private var awaitingCommand=false
    private var closed=false
    private var started=false
    private var lastInteraction=System.currentTimeMillis()
    private lateinit var audio:AudioManager
    private var previousAudioMode=AudioManager.MODE_NORMAL
    private var startedSco=false
    private val routeCallback=object:AudioDeviceCallback() {
        override fun onAudioDevicesAdded(devices:Array<out AudioDeviceInfo>?) {configureAudioRoute()}
        override fun onAudioDevicesRemoved(devices:Array<out AudioDeviceInfo>?) {configureAudioRoute()}
    }
    companion object { val status=MutableStateFlow("Not listening") }
    override fun onBind(intent:Intent?)=null
    private fun online():Boolean {val cm=getSystemService(ConnectivityManager::class.java);return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="STOP") {stopSelf();return START_NOT_STICKY}
        if(started) return START_NOT_STICKY
        started=true
        audio=getSystemService(AudioManager::class.java);previousAudioMode=audio.mode
        audio.registerAudioDeviceCallback(routeCallback,Handler(Looper.getMainLooper()));configureAudioRoute()
        val channel="coach-voice"
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel,"Hands-free coach",NotificationManager.IMPORTANCE_LOW))
        val stop=PendingIntent.getService(this,1,Intent(this,CoachVoiceService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        startForeground(42,NotificationCompat.Builder(this,channel).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Pinball Pilot is listening").setContentText("Say Hey Pinball. Tap Stop to release the microphone.").setContentIntent(open).addAction(android.R.drawable.ic_media_pause,"Stop",stop).setOngoing(true).build())
        if(!online()) {status.value="Offline. Use touch controls and spoken guides.";stopSelf();return START_NOT_STICKY}
        status.value="Preparing local wake phrase…"
        StorageService.unpack(this,"model-en-us","wake-model",{ loaded ->
            if(closed) {loaded.close();return@unpack}
            model=loaded;startWake()
        },{status.value="Wake model unavailable. Run the documented model setup before building.";stopSelf()})
        scope.launch {coach.narrator.speaking.collect {speaking -> wake?.setPause(speaking||awaitingCommand)}}
        scope.launch {while(isActive) {delay(15_000);if(!online()) {status.value="Offline. Hands-free paused; touch controls still work.";stopSelf();break};if(coach.state.value.prompts && !awaitingCommand && System.currentTimeMillis()-lastInteraction>180_000) {lastInteraction=System.currentTimeMillis();coach.narrator.speak("When you are ready, tell me how your game is going.")}}}
        return START_NOT_STICKY
    }
    @Suppress("DEPRECATION")
    private fun configureAudioRoute() {
        if(!::audio.isInitialized||closed) return
        try {
            if(Build.VERSION.SDK_INT>=31) {
                val headset=audio.availableCommunicationDevices.firstOrNull {it.type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO||it.type==AudioDeviceInfo.TYPE_BLE_HEADSET}
                    ?:audio.availableCommunicationDevices.firstOrNull {it.type==AudioDeviceInfo.TYPE_WIRED_HEADSET}
                if(headset!=null) {audio.mode=AudioManager.MODE_IN_COMMUNICATION;audio.setCommunicationDevice(headset)}
                else {audio.clearCommunicationDevice();audio.mode=AudioManager.MODE_NORMAL}
            } else if(audio.getDevices(AudioManager.GET_DEVICES_INPUTS).any {it.type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO}) {
                audio.mode=AudioManager.MODE_IN_COMMUNICATION;audio.startBluetoothSco();audio.isBluetoothScoOn=true;startedSco=true
            }
        } catch(_:SecurityException) {status.value="Headset permission unavailable; using the system audio route."}
    }
    private fun startWake() {
        if(closed||!online()) return
        awaitingCommand=false
        try {
            wake?.shutdown();wake=null;wakeRecognizer?.close()
            wakeRecognizer=Recognizer(model,16000f,"[\"hey pin ball\",\"hey pinball\",\"[unk]\"]")
            wake=SpeechService(wakeRecognizer,16000f)
            wake?.startListening(object:org.vosk.android.RecognitionListener {
                override fun onPartialResult(hypothesis:String?) {detect(hypothesis)}
                override fun onResult(hypothesis:String?) {detect(hypothesis)}
                override fun onFinalResult(hypothesis:String?) {}
                override fun onError(e:Exception?) {scope.launch {status.value="Microphone unavailable. Check the audio route and enable hands-free again.";stopSelf()}}
                override fun onTimeout() {}
            })
            wake?.setPause(coach.narrator.speaking.value)
            status.value="Quietly waiting for ‘Hey Pinball’"
        } catch(_:Exception) {status.value="Wake listener could not start. Use tap-to-talk.";stopSelf()}
    }
    private fun detect(raw:String?) {
        val text=runCatching {val j=Json.parseToJsonElement(raw?:"{}").jsonObject;j["partial"]?.jsonPrimitive?.content?:j["text"]?.jsonPrimitive?.content?:""}.getOrDefault("")
        if(text.contains("hey pinball")||text.contains("hey pin ball")) scope.launch {if(!awaitingCommand&&!coach.narrator.speaking.value) listenCommand()}
    }
    private suspend fun listenCommand() {
        awaitingCommand=true;lastInteraction=System.currentTimeMillis()
        wake?.shutdown();wake=null;wakeRecognizer?.close();wakeRecognizer=null
        if(!SpeechRecognizer.isRecognitionAvailable(this)) {status.value="Online speech recognition is unavailable. Use touch controls.";stopSelf();return}
        status.value="Listening for your question…"
        delay(250)
        if(closed) return
        recognizer?.destroy();recognizer=SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object:android.speech.RecognitionListener {
            override fun onReadyForSpeech(params:Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB:Float) {}
            override fun onBufferReceived(buffer:ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults:Bundle?) {}
            override fun onEvent(eventType:Int,params:Bundle?) {}
            override fun onError(error:Int) {scope.launch {delay(800);startWake()}}
            override fun onResults(results:Bundle?) {
                val text=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if(text!=null) {if(VoiceCommands.parse(text)==VoiceCommand.STOP) {stopSelf();return};coach.voice(text)}
                scope.launch {delay(800);startWake()}
            }
        })
        recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1))
        scope.launch {delay(12_000);if(awaitingCommand&&!closed){recognizer?.cancel();startWake()}}
    }
    override fun onDestroy() {
        closed=true;scope.cancel();recognizer?.destroy();wake?.shutdown();wakeRecognizer?.close();model?.close()
        if(::audio.isInitialized) runCatching {audio.unregisterAudioDeviceCallback(routeCallback);if(Build.VERSION.SDK_INT>=31) audio.clearCommunicationDevice();@Suppress("DEPRECATION") if(startedSco) {audio.stopBluetoothSco();audio.isBluetoothScoOn=false};audio.mode=previousAudioMode}
        if(status.value.startsWith("Quietly")||status.value.startsWith("Listening")||status.value.startsWith("Preparing")) status.value="Not listening"
        super.onDestroy()
    }
}
