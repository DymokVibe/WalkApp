package com.loopwalk.app

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class WalkVoiceGuide(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingSpeech: String? = null

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (_: Exception) {
            ready = false
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                var res = tts?.setLanguage(Locale("ru", "RU"))
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    res = tts?.setLanguage(Locale.getDefault())
                }
                ready = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setSpeechRate(1.05f)
                val queued = pendingSpeech
                pendingSpeech = null
                if (ready && !queued.isNullOrBlank()) {
                    speak(queued)
                }
            } catch (_: Exception) {
                ready = false
            }
        }
    }

    fun speak(text: String) {
        if (!WalkSessionHolder.voiceEnabled || text.isBlank()) return
        if (!ready) {
            pendingSpeech = text
            return
        }
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lw_${System.currentTimeMillis()}")
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
        pendingSpeech = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ready = false
    }
}
