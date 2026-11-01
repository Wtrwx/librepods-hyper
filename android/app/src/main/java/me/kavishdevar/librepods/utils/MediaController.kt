/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.utils

import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import me.kavishdevar.librepods.services.ServiceManager
import kotlin.io.encoding.ExperimentalEncodingApi

object MediaController {
    private var initialVolume: Int? = null
    private lateinit var audioManager: AudioManager
    var iPausedTheMedia = false
    var userPlayedTheMedia = false
    private lateinit var sharedPreferences: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var preferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener

    var pausedWhileTakingOver = false
    var pausedForOtherDevice = false

    private var lastSelfActionAt: Long = 0L
    private const val SELF_ACTION_IGNORE_MS = 800L
    private const val PLAYBACK_DEBOUNCE_MS = 300L
    private var lastPlaybackCallbackAt: Long = 0L
    private var lastKnownIsMusicActive: Boolean? = null
    private var remoteAudioFocusRequest: AudioFocusRequest? = null

    private fun releaseRemoteAudioFocus() {
        val request = remoteAudioFocusRequest ?: return
        remoteAudioFocusRequest = null
        audioManager.abandonAudioFocusRequest(request)
    }

    private fun holdRemoteAudioFocus() {
        if (!this::audioManager.isInitialized || remoteAudioFocusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener({ change ->
                if (remoteAudioFocusRequest == null) return@setOnAudioFocusChangeListener
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    val playbackWasStopped = lastKnownIsMusicActive != true
                    releaseRemoteAudioFocus()
                    // A new Android player claiming permanent focus is a fresh local
                    // intent, even before its first AudioTrack starts producing audio.
                    if (change == AudioManager.AUDIOFOCUS_LOSS && pausedForOtherDevice && playbackWasStopped) {
                        lastKnownIsMusicActive = false
                        Log.d("MediaController", "New local audio focus while remote owns the route")
                        ServiceManager.getService()?.takeOver("music", localPlaybackIntent = true)
                    }
                }
            }, handler)
            .build()
        remoteAudioFocusRequest = request
        val result = runCatching { audioManager.requestAudioFocus(request) }.getOrElse {
            Log.w("MediaController", "Cannot hold remote audio focus: ${it.message}")
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) remoteAudioFocusRequest = null
        Log.d("MediaController", "Remote audio focus result: $result")
    }

    private const val OWNERSHIP_RETRY_MS = 3200L
    private var deferredTakeoverGeneration = -1L
    private val deferredTakeOverRunnable = Runnable {
        if (!this::audioManager.isInitialized) return@Runnable
        if (!audioManager.isMusicActive) {
            Log.d("MediaController", "Deferred take-over skipped because music is no longer active")
            return@Runnable
        }
        val service = ServiceManager.getService() ?: return@Runnable
        if (service.routingGeneration() != deferredTakeoverGeneration) return@Runnable
        Log.d("MediaController", "Deferred take-over after ownership-loss guard")
        if (!pausedWhileTakingOver) service.takeOver("music", localPlaybackIntent = true)
    }

    private var relativeVolume: Boolean = false
    private var conversationalAwarenessVolume: Int = 2
    private var conversationalAwarenessPauseMusic: Boolean = false

    var recentlyLostOwnership: Boolean = false

    private var lastPlayWithReplay: Boolean = false
    private var lastPlayTime: Long = 0L

    fun initialize(audioManager: AudioManager, sharedPreferences: SharedPreferences) {
        if (this::audioManager.isInitialized) {
            return
        }
        this.audioManager = audioManager
        this.sharedPreferences = sharedPreferences
        Log.d("MediaController", "Initializing MediaController")
        relativeVolume = sharedPreferences.getBoolean("relative_conversational_awareness_volume", false)
        conversationalAwarenessVolume = sharedPreferences.getInt("conversational_awareness_volume", (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 0.4).toInt())
        conversationalAwarenessPauseMusic = sharedPreferences.getBoolean("conversational_awareness_pause_music", false)

        preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "relative_conversational_awareness_volume" -> {
                    relativeVolume = sharedPreferences.getBoolean("relative_conversational_awareness_volume", false)
                }
                "conversational_awareness_volume" -> {
                    conversationalAwarenessVolume = sharedPreferences.getInt("conversational_awareness_volume", (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.4).toInt())
                }
                "conversational_awareness_pause_music" -> {
                    conversationalAwarenessPauseMusic = sharedPreferences.getBoolean("conversational_awareness_pause_music", false)
                }
            }
        }

        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        audioManager.registerAudioPlaybackCallback(cb, handler)
    }

    val cb = object : AudioManager.AudioPlaybackCallback() {
        @RequiresApi(Build.VERSION_CODES.R)
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            super.onPlaybackConfigChanged(configs)
            val now = SystemClock.uptimeMillis()
            val isActive = audioManager.isMusicActive
            // Short-video players commonly use USAGE_MEDIA with CONTENT_TYPE_UNKNOWN.
            // UI sounds share the music stream but must not hide a new media start.
            val hasMediaPlayback = configs.orEmpty().any { config ->
                val attrs = config.audioAttributes
                attrs.usage == AudioAttributes.USAGE_MEDIA ||
                    attrs.contentType == AudioAttributes.CONTENT_TYPE_MUSIC ||
                    attrs.contentType == AudioAttributes.CONTENT_TYPE_MOVIE
            }
            val mediaActive = isActive && hasMediaPlayback
            ServiceManager.getService()?.updateTipiScore(mediaActive)
            val playbackStarted = mediaActive && lastKnownIsMusicActive != true
            Log.d("MediaController", "Playback config changed, iPausedTheMedia: $iPausedTheMedia, isActive: $isActive, pausedForOtherDevice: $pausedForOtherDevice, lastKnownIsMusicActive: $lastKnownIsMusicActive")

            if (!isActive && lastPlayWithReplay && now - lastPlayTime < 2500L) {
                Log.d("MediaController", "Music paused shortly after play with replay; retrying play")
                lastPlayWithReplay = false
                sendPlay()
                lastKnownIsMusicActive = mediaActive
                return
            }

            if (now - lastPlaybackCallbackAt < PLAYBACK_DEBOUNCE_MS && !playbackStarted) {
                Log.d("MediaController", "Ignoring playback callback due to debounce (${now - lastPlaybackCallbackAt}ms)")
                lastKnownIsMusicActive = mediaActive
                return
            }
            lastPlaybackCallbackAt = now

            if (now - lastSelfActionAt < SELF_ACTION_IGNORE_MS &&
                !(pausedForOtherDevice && playbackStarted && remoteAudioFocusRequest == null)) {
                Log.d("MediaController", "Ignoring playback callback because it's likely caused by our own action (${now - lastSelfActionAt}ms since last self-action)")
                lastKnownIsMusicActive = mediaActive
                return
            }

            Log.d("MediaController", "Has media playback: $hasMediaPlayback")

            if (pausedForOtherDevice) {
                if (isActive && playbackStarted) {
                    Log.d("MediaController", "Detected play while pausedForOtherDevice; attempting to take over")
                    if (recentlyLostOwnership && hasMediaPlayback) {
                        Log.d("MediaController", "Recently lost ownership; scheduling deferred take-over instead of dropping user play")
                        handler.removeCallbacks(deferredTakeOverRunnable)
                        deferredTakeoverGeneration = ServiceManager.getService()?.routingGeneration() ?: -1L
                        handler.postDelayed(deferredTakeOverRunnable, OWNERSHIP_RETRY_MS)
                    } else if (hasMediaPlayback) {
                        if (!pausedWhileTakingOver) {
                            ServiceManager.getService()?.takeOver("music", localPlaybackIntent = true)
                        }
                    } else {
                        Log.d("MediaController", "Skipping take-over: no media playback")
                    }
                } else {
                    Log.d("MediaController", "Still paused for the remote device; waiting for a local route request")
                }

                lastKnownIsMusicActive = mediaActive
                return
            }

            if (configs != null && !iPausedTheMedia && ServiceManager.getService()?.canUseLocalAudioRoute() == true) {
                val localMac = ServiceManager.getService()?.localMac ?: return
                if (localMac == "") return
                ServiceManager.getService()?.aacpManager?.sendMediaInformataion(
                    localMac,
                    isActive
                )
                Log.d("MediaController", "User changed media state themselves; will wait for ear detection pause before auto-play")
                val service = ServiceManager.getService()
                val generation = service?.routingGeneration()
                handler.postDelayed({
                    if (ServiceManager.getService() !== service || service?.routingGeneration() != generation) return@postDelayed
                    userPlayedTheMedia = audioManager.isMusicActive
                }, 7)
            }

            Log.d("MediaController", "pausedWhileTakingOver: $pausedWhileTakingOver")
            if (!pausedWhileTakingOver && isActive && hasMediaPlayback) {
                if (lastKnownIsMusicActive != true) {
                    if (!recentlyLostOwnership) {
                        Log.d("MediaController", "Music/movie is active and not pausedWhileTakingOver; requesting takeOver")
                        ServiceManager.getService()?.takeOver("music", localPlaybackIntent = true)
                    } else {
                        Log.d("MediaController", "Recently lost ownership; scheduling deferred take-over")
                        handler.removeCallbacks(deferredTakeOverRunnable)
                        deferredTakeoverGeneration = ServiceManager.getService()?.routingGeneration() ?: -1L
                        handler.postDelayed(deferredTakeOverRunnable, OWNERSHIP_RETRY_MS)
                    }
                }
            }

            lastKnownIsMusicActive = hasMediaPlayback && isActive
        }
    }

    @Synchronized
    fun remoteRouteRequested() {
        handler.removeCallbacks(deferredTakeOverRunnable)
        deferredTakeoverGeneration = -1L
        lastPlayWithReplay = false
        pausedWhileTakingOver = false
        pausedForOtherDevice = true
        recentlyLostOwnership = true
        if (this::audioManager.isInitialized) sendPause(force = true)
        // Some video apps have no active MediaSession and ignore media pause keys.
        // The remote host is a full media owner: a permanent focus loss asks the
        // actual player to pause instead of merely muting it for an interruption.
        holdRemoteAudioFocus()
        val generation = ServiceManager.getService()?.routingGeneration()
        handler.postDelayed({
            if (ServiceManager.getService()?.routingGeneration() == generation) {
                recentlyLostOwnership = false
            }
        }, 3000L)
    }

    @Synchronized
    fun localRouteRequested() {
        releaseRemoteAudioFocus()
        handler.removeCallbacks(deferredTakeOverRunnable)
        deferredTakeoverGeneration = -1L
        pausedForOtherDevice = false
        recentlyLostOwnership = false
        lastPlayWithReplay = false
    }

    @Synchronized
    fun localRouteConfirmed() {
        localRouteRequested()
    }

    @Synchronized
    fun resumeAfterRouteConfirmation() {
        iPausedTheMedia = true
        sendPlay()
    }

    @Synchronized
    fun resetRoutingState(preserveRemotePause: Boolean = false) {
        if (!preserveRemotePause) {
            localRouteRequested()
            iPausedTheMedia = false
            lastKnownIsMusicActive = null
        } else {
            handler.removeCallbacks(deferredTakeOverRunnable)
            deferredTakeoverGeneration = -1L
            lastKnownIsMusicActive = getMusicActive()
        }
        pausedWhileTakingOver = false
        userPlayedTheMedia = false
    }

    @Synchronized
    fun getMusicActive(): Boolean {
        return this::audioManager.isInitialized && audioManager.isMusicActive
    }

    @Synchronized
    fun sendPlayPause() {
        if (audioManager.isMusicActive) {
            Log.d("MediaController", "Sending pause because music is active")
            sendPause()
        } else {
            Log.d("MediaController", "Sending play because music is not active")
            sendPlay()
        }
    }

    @Synchronized
    fun sendPreviousTrack() {
        Log.d("MediaController", "Sending previous track")
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS
            )
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS
            )
        )
        lastSelfActionAt = SystemClock.uptimeMillis()
    }

    @Synchronized
    fun sendNextTrack() {
        Log.d("MediaController", "Sending next track")
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_NEXT
            )
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MEDIA_NEXT
            )
        )
        lastSelfActionAt = SystemClock.uptimeMillis()
    }

    @Synchronized
    fun sendPause(force: Boolean = false) {
        Log.d("MediaController", "Sending pause with iPausedTheMedia: $iPausedTheMedia, userPlayedTheMedia: $userPlayedTheMedia, isMusicActive: ${audioManager.isMusicActive}, force: $force")
        if ((audioManager.isMusicActive) && (!userPlayedTheMedia || force)) {
            iPausedTheMedia = if (force) audioManager.isMusicActive else true
            userPlayedTheMedia = false
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_MEDIA_PAUSE
                )
            )
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_MEDIA_PAUSE
                )
            )
            lastSelfActionAt = SystemClock.uptimeMillis()
        }
    }

    @Synchronized
    fun sendPlay(replayWhenPaused: Boolean = false, force: Boolean = false) {
        if (!this::audioManager.isInitialized || ServiceManager.getService()?.canResumeLocalMedia() != true) {
            Log.d("MediaController", "Skipping play without a confirmed local audio route")
            return
        }
        if (audioManager.isMusicActive) {
            Log.d("MediaController", "Skipping play because media is already active")
            pausedWhileTakingOver = false
            return
        }
        Log.d("MediaController", "Sending play with iPausedTheMedia: $iPausedTheMedia, replayWhenPaused: $replayWhenPaused, force: $force")
        if (replayWhenPaused) {
            lastPlayWithReplay = true
            lastPlayTime = SystemClock.uptimeMillis()
        }
        if (iPausedTheMedia || force) { // very creative, ik. thanks.
            Log.d("MediaController", "Sending play and setting userPlayedTheMedia to false")
            userPlayedTheMedia = false
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_MEDIA_PLAY
                )
            )
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_MEDIA_PLAY
                )
            )
            lastSelfActionAt = SystemClock.uptimeMillis()
        }
        if (!audioManager.isMusicActive) {
            Log.d("MediaController", "Setting iPausedTheMedia to false")
            iPausedTheMedia = false
        }
        if (pausedWhileTakingOver) {
            Log.d("MediaController", "Setting pausedWhileTakingOver to false")
            pausedWhileTakingOver = false
        }
    }

    @Synchronized
    fun startSpeaking() {
        Log.d("MediaController", "Starting speaking max vol: ${audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}, current vol: ${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)}, conversationalAwarenessVolume: $conversationalAwarenessVolume, relativeVolume: $relativeVolume")

        if (initialVolume == null) {
            initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            Log.d("MediaController", "Initial Volume: $initialVolume")
            val targetVolume = if (relativeVolume) {
                (initialVolume!! * conversationalAwarenessVolume / 100)
            } else if (initialVolume!! > (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * conversationalAwarenessVolume / 100)) {
                (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * conversationalAwarenessVolume / 100)
            } else {
                initialVolume!!
            }
            smoothVolumeTransition(initialVolume!!, targetVolume)
            if (conversationalAwarenessPauseMusic) {
                sendPause(force = true)
            }
        }
        Log.d("MediaController", "Initial Volume: $initialVolume")
    }

    @Synchronized
    fun stopSpeaking() {
        Log.d("MediaController", "Stopping speaking, initialVolume: $initialVolume")
        if (initialVolume != null) {
            smoothVolumeTransition(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC), initialVolume!!)
            if (conversationalAwarenessPauseMusic) {
                sendPlay()
            }
            initialVolume = null
        }
    }

    private fun smoothVolumeTransition(fromVolume: Int, toVolume: Int) {
        Log.d("MediaController", "Smooth volume transition from $fromVolume to $toVolume")
        val step = if (fromVolume < toVolume) 1 else -1
        val delay = 50L
        var currentVolume = fromVolume

        handler.post(object : Runnable {
            override fun run() {
                if (currentVolume != toVolume) {
                    currentVolume += step
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
                    handler.postDelayed(this, delay)
                }
            }
        })
    }
}
