package com.example.talkingalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

/**
 * Runs while an alarm is going off: speaks the alarm text on a loop through the
 * alarm audio stream, vibrates, and shows the full screen dismiss screen.
 */
class AlarmService : Service(), TextToSpeech.OnInitListener {

    companion object {
        /** The alarm currently ringing, or null. Observed by AlarmRingActivity. */
        val ringing = MutableStateFlow<Alarm?>(null)

        const val ACTION_DISMISS = "com.example.talkingalarm.action.DISMISS"
        const val ACTION_SNOOZE = "com.example.talkingalarm.action.SNOOZE"

        const val SNOOZE_MINUTES = 5

        private const val CHANNEL_ID = "ringing_alarm"
        private const val NOTIFICATION_ID = 7301
        private const val AUTO_STOP_MS = 5 * 60 * 1000L
        private const val GAP_BETWEEN_REPEATS_MS = 1500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var alarm: Alarm? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var previousAlarmVolume: Int? = null
    private var vibrator: Vibrator? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getIntExtra(EXTRA_ALARM_ID, -1) ?: -1

        when (intent?.action) {
            ACTION_DISMISS -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_SNOOZE -> {
                (alarm ?: AlarmStore.get(this, id))?.let {
                    AlarmScheduler.scheduleSnooze(this, it, SNOOZE_MINUTES)
                }
                shutdown()
                return START_NOT_STICKY
            }
        }

        val current = AlarmStore.get(this, id)
        if (current == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        alarm = current
        ringing.value = current

        startInForeground(current)
        keepAwake()
        raiseAlarmVolume()
        playAttentionTone()
        startVibrating()
        startSpeaking()
        openRingScreen()

        handler.postDelayed({ shutdown() }, AUTO_STOP_MS)
        return START_STICKY
    }

    // ---------- speech ----------

    private fun startSpeaking() {
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val engine = tts ?: return

        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )

        val result = engine.setLanguage(Locale.getDefault())
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.US)
        }
        engine.setSpeechRate(0.95f)

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                handler.postDelayed({ speakOnce() }, GAP_BETWEEN_REPEATS_MS)
            }
            @Deprecated("Required by the base class")
            override fun onError(utteranceId: String?) = Unit
        })

        ttsReady = true
        handler.postDelayed({ speakOnce() }, 900)
    }

    private fun speakOnce() {
        if (stopping || !ttsReady) return
        val a = alarm ?: return
        val body = if (a.text.isBlank()) "Alarm" else a.text
        val sentence = "It's ${a.timeText()}. $body"
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
        }
        tts?.speak(sentence, TextToSpeech.QUEUE_FLUSH, params, "alarm-${a.id}")
    }

    // ---------- audio, vibration, wake ----------

    private fun playAttentionTone() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 700)
            handler.postDelayed({ runCatching { tone.release() } }, 1200)
        }
    }

    private fun raiseAlarmVolume() {
        runCatching {
            val audio = getSystemService(AudioManager::class.java)
            previousAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0
            )
        }
    }

    private fun restoreAlarmVolume() {
        val previous = previousAlarmVolume ?: return
        runCatching {
            getSystemService(AudioManager::class.java)
                .setStreamVolume(AudioManager.STREAM_ALARM, previous, 0)
        }
        previousAlarmVolume = null
    }

    private fun startVibrating() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        runCatching {
            vibrator?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 700, 2300), 0),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
    }

    private fun keepAwake() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TalkingAlarm:ringing"
        ).also { it.acquire(AUTO_STOP_MS + 30_000L) }
    }

    // ---------- notification and ring screen ----------

    private fun startInForeground(a: Alarm) {
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(a),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        )
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Ringing alarm", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Shown while an alarm is speaking"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun serviceIntent(action: String, a: Alarm): PendingIntent {
        val intent = Intent(this, AlarmService::class.java)
            .setAction(action)
            .putExtra(EXTRA_ALARM_ID, a.id)
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(a: Alarm): Notification {
        val fullScreen = PendingIntent.getActivity(
            this, 1, ringScreenIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(a.timeText())
            .setContentText(if (a.text.isBlank()) "Alarm" else a.text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(0, "Stop", serviceIntent(ACTION_DISMISS, a))
            .addAction(0, "Snooze $SNOOZE_MINUTES min", serviceIntent(ACTION_SNOOZE, a))
            .build()
    }

    private fun ringScreenIntent() = Intent(this, AlarmRingActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun openRingScreen() {
        runCatching { startActivity(ringScreenIntent()) }
    }

    // ---------- teardown ----------

    private fun shutdown() {
        if (stopping) return
        stopping = true
        handler.removeCallbacksAndMessages(null)
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
        runCatching { vibrator?.cancel() }
        restoreAlarmVolume()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        ringing.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }
}
