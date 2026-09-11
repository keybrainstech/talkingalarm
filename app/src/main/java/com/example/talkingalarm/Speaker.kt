package com.example.talkingalarm

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale

/** One-shot preview so you can hear how an alarm will sound while editing it. */
object Speaker {

    private var tts: TextToSpeech? = null
    private var pending: String? = null

    fun preview(context: Context, text: String) {
        val sentence = if (text.isBlank()) "Alarm" else text
        val engine = tts
        if (engine != null) {
            engine.speak(sentence, TextToSpeech.QUEUE_FLUSH, null, "preview")
            return
        }
        pending = sentence
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            tts?.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                val result = setLanguage(Locale.getDefault())
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    setLanguage(Locale.US)
                }
                setSpeechRate(0.95f)
                pending?.let { speak(it, TextToSpeech.QUEUE_FLUSH, null, "preview") }
                pending = null
            }
        }
    }

    fun release() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
    }
}
