package com.example.notifications

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.model.NotificationEventType
import com.example.model.StoreNotification
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Plays a distinct sound chime, vibration pulse, and spoken voice announcement for task
 * notifications using [AudioAttributes.USAGE_ALARM] / [AudioManager.STREAM_ALARM] so that
 * alerts remain audible even when the phone is in Silent or Vibrate mode.
 */
object TaskAlertAudioPlayer {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val recentAlertTimestamps = ConcurrentHashMap<String, Long>()
    private const val DEDUPE_WINDOW_MS = 2500L

    fun playTaskSoundAndVoice(context: Context, notification: StoreNotification) {
        if (!notification.type.isTaskEvent) return

        val appCtx = context.applicationContext
        val now = System.currentTimeMillis()
        val dedupeKey = notification.id.ifBlank { "${notification.type.key}_${notification.title}" }
        val prev = recentAlertTimestamps[dedupeKey]
        if (prev != null && (now - prev) < DEDUPE_WINDOW_MS) {
            return
        }
        recentAlertTimestamps[dedupeKey] = now
        if (recentAlertTimestamps.size > 60) {
            val cutoff = now - 60_000L
            recentAlertTimestamps.entries.removeIf { it.value < cutoff }
        }

        triggerVibration(appCtx)
        playAlarmStreamChime(appCtx)
        speakOnAlarmStream(appCtx, buildSpokenMessage(notification))
    }

    private fun buildSpokenMessage(notification: StoreNotification): String {
        val prefix = when (notification.type) {
            NotificationEventType.TASK_ASSIGNED -> "নতুন টাস্ক যুক্ত হয়েছে।"
            NotificationEventType.TASK_COMPLETED -> "টাস্ক কমপ্লিট হয়েছে।"
            NotificationEventType.TASK_UPDATED -> "টাস্ক প্রসেস বা আপডেট হয়েছে।"
            else -> "টাস্ক নোটিফিকেশন।"
        }
        val detail = notification.title.trim().take(120)
        return if (detail.isNotBlank() && !detail.equals(prefix, ignoreCase = true)) {
            "$prefix $detail"
        } else {
            prefix
        }
    }

    private fun playAlarmStreamChime(context: Context) {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: return

            val player = MediaPlayer()
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            player.setAudioAttributes(attrs)
            player.setDataSource(context, uri)
            player.isLooping = false
            player.setOnPreparedListener { mp ->
                runCatching { mp.start() }
            }
            player.setOnCompletionListener { mp ->
                runCatching {
                    mp.stop()
                    mp.release()
                }
            }
            player.setOnErrorListener { mp, _, _ ->
                runCatching { mp.release() }
                true
            }
            player.prepareAsync()

            // Safety cleanup in case a long alarm tone was chosen
            mainHandler.postDelayed({
                runCatching {
                    if (player.isPlaying) player.stop()
                    player.release()
                }
            }, 3200L)
        }
    }

    private fun speakOnAlarmStream(context: Context, spokenText: String) {
        if (spokenText.isBlank()) return
        mainHandler.post {
            var ttsRef: TextToSpeech? = null
            val shutdownRunnable = Runnable {
                runCatching {
                    ttsRef?.stop()
                    ttsRef?.shutdown()
                }
            }

            ttsRef = TextToSpeech(context) { status ->
                val engine = ttsRef ?: return@TextToSpeech
                if (status != TextToSpeech.SUCCESS) {
                    runCatching { engine.shutdown() }
                    return@TextToSpeech
                }
                runCatching {
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    engine.setAudioAttributes(attrs)

                    val bnLocale = Locale("bn", "BD")
                    val bnResult = engine.setLanguage(bnLocale)
                    if (bnResult == TextToSpeech.LANG_MISSING_DATA ||
                        bnResult == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        val inBnResult = engine.setLanguage(Locale("bn", "IN"))
                        if (inBnResult == TextToSpeech.LANG_MISSING_DATA ||
                            inBnResult == TextToSpeech.LANG_NOT_SUPPORTED
                        ) {
                            engine.setLanguage(Locale.US)
                        }
                    }

                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            mainHandler.removeCallbacks(shutdownRunnable)
                            mainHandler.postDelayed(shutdownRunnable, 250L)
                        }

                        @Deprecated("Deprecated in Android API")
                        override fun onError(utteranceId: String?) {
                            mainHandler.removeCallbacks(shutdownRunnable)
                            mainHandler.post(shutdownRunnable)
                        }
                    })

                    val params = Bundle().apply {
                        putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
                        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                    }

                    // Delay speech slightly (~650ms) so the notification chime plays first clearly
                    mainHandler.postDelayed({
                        runCatching {
                            engine.speak(
                                spokenText,
                                TextToSpeech.QUEUE_FLUSH,
                                params,
                                "task_alert_${System.currentTimeMillis()}"
                            )
                        }
                    }, 650L)

                    // Safety timeout to guarantee TTS resources are always released
                    mainHandler.postDelayed(shutdownRunnable, 12_000L)
                }.onFailure {
                    runCatching { engine.shutdown() }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun triggerVibration(context: Context) {
        runCatching {
            val pattern = longArrayOf(0L, 220L, 120L, 260L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(
                    VibrationEffect.createWaveform(pattern, -1)
                )
            } else {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    vibrator?.vibrate(pattern, -1)
                }
            }
        }
    }
}
