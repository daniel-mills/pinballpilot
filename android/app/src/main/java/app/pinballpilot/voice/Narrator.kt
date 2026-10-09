package app.pinballpilot.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton class Narrator @Inject constructor(@ApplicationContext context: Context) {
    val speaking = MutableStateFlow(false)
    val status = MutableStateFlow("")
    private var ready = false
    private var pending: String? = null
    private var tts: TextToSpeech? = null
    init {
        tts = TextToSpeech(context) { code ->
            ready = code == TextToSpeech.SUCCESS
            if (ready) {
                val engine = tts ?: return@TextToSpeech
                engine.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                val voice = engine.voices?.firstOrNull { it.locale.language == "en" && !it.isNetworkConnectionRequired }
                if (voice != null) engine.voice = voice else { ready = false; status.value = "Install an offline English voice in Android text-to-speech settings." }
                engine.setSpeechRate(1.02f)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { speaking.value = true }
                    override fun onDone(id: String?) { speaking.value = false }
                    @Deprecated("Android callback") override fun onError(id: String?) { speaking.value = false; status.value = "Speech unavailable. Read the on-screen guide." }
                })
                pending?.let { pending = null; speak(it) }
            } else status.value = "Speech unavailable. Read the on-screen guide."
        }
    }
    fun speak(text: String) {
        if (!ready) { pending = text; return }
        speaking.value = true
        if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "coach") == TextToSpeech.ERROR) { speaking.value = false; status.value = "Could not play spoken advice." }
    }
    fun stop() { pending = null; tts?.stop(); speaking.value = false }
}
