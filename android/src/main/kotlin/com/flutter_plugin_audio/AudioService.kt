package com.flutter_plugin_audio

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ALBUM_ART
import android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST
import android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION
import android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
import android.view.KeyEvent.KEYCODE_MEDIA_PLAY
import android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import androidx.palette.graphics.Palette
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import com.danielgauci.native_audio.BluetoothManager
import com.danielgauci.native_audio.HeadsetManager

class AudioService : Service() {

    companion object {
        var SKIP_FORWARD_TIME_MILLIS = 30_000L
        var SKIP_BACKWARD_TIME_MILLIS = 10_000L

        private const val MEDIA_SESSION_TAG = "com.danielgauci.native_audio"
        private const val NOTIFICATION_ID = 10
        private const val NOTIFICATION_CHANNEL_ID = "media_playback_channel"
        private const val NOTIFICATION_CHANNEL_NAME = "Media Playback"
        private const val NOTIFICATION_CHANNEL_DESCRIPTION = "Media Playback Controls"
    }

    var onLoaded: ((Long, Boolean) -> Unit)? = null
    var onProgressChanged: ((Long) -> Unit)? = null
    var onResumed: (() -> Unit)? = null
    var onPaused: (() -> Unit)? = null
    var onStopped: (() -> Unit)? = null
    var onCompleted: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onPrevious: (() -> Unit)? = null

    private var currentPlaybackState = PlaybackStateCompat.STATE_STOPPED
    private var oldPlaybackState: Int = Int.MIN_VALUE
    private var currentPositionInMillis = 0L
    private var durationInMillis = 0L
    private var resumeOnAudioFocus = false
    private var isNotificationShown = false

    private val binder by lazy { AudioServiceBinder() }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private var metadata = MediaMetadataCompat.Builder()

    private val session by lazy {
        MediaSessionCompat(this, MEDIA_SESSION_TAG).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                    val keyEvent =
                        mediaButtonEvent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)

                    if (keyEvent?.action == ACTION_DOWN) {
                        when (keyEvent.keyCode) {
                            KEYCODE_MEDIA_PLAY -> {
                                resume()
                                return true
                            }

                            KEYCODE_MEDIA_PAUSE -> {
                                pause()
                                return true
                            }

                            KEYCODE_MEDIA_PLAY_PAUSE -> {
                                if (isPlaying()) pause() else resume()
                                return true
                            }
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }

                override fun onSeekTo(pos: Long) {
                    super.onSeekTo(pos)
                    seekTo(pos)
                }

                override fun onSkipToNext() {
                    super.onSkipToNext()
                    onNext?.invoke()
                }

                override fun onSkipToPrevious() {
                    super.onSkipToPrevious()
                    onPrevious?.invoke()
                }

                override fun onFastForward() {
                    super.onFastForward()
                    onNext?.invoke()
                }

                override fun onRewind() {
                    super.onRewind()
                    skipBackward()
                }
            })
        }
    }

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private var notificationBuilder: NotificationCompat.Builder =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)

    val audioPlayer by lazy {
        AudioPlayer(
            onLoaded = { totalDurationInMillis, startedAutomatically ->
                durationInMillis = totalDurationInMillis
                onLoaded?.invoke(totalDurationInMillis, startedAutomatically)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    metadata.putLong(METADATA_KEY_DURATION, durationInMillis)
                    session.setMetadata(metadata.build())
                }
            },
            onProgressChanged = {
                currentPositionInMillis = it
                onProgressChanged?.invoke(it)
                updatePlaybackState()
            },
            onResumed = onResumed,
            onPaused = onPaused,
            onStopped = onStopped,
            onCompleted = {
                stop()
                onCompleted?.invoke()
            }
        )
    }

    private val playbackStateBuilder by lazy {
        PlaybackStateCompat.Builder().setActions(
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_FAST_FORWARD or
                    PlaybackStateCompat.ACTION_REWIND or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SEEK_TO
        )
    }

    private val audioFocusRequest by lazy {
        AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setOnAudioFocusChangeListener { audioFocus ->
                when (audioFocus) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        if (resumeOnAudioFocus && !isPlaying()) {
                            resume()
                            resumeOnAudioFocus = false
                        }
                    }

                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {}

                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        if (isPlaying()) {
                            resumeOnAudioFocus = true
                            pause()
                        }
                    }

                    AudioManager.AUDIOFOCUS_LOSS -> {
                        resumeOnAudioFocus = false
                        pause()
                    }
                }
            }
            .build()
    }

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val headsetManager by lazy { HeadsetManager() }
    private val bluetoothManager by lazy { BluetoothManager() }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("NATIVE_PLUGIN", "onStartCommand")

        MediaButtonReceiver.handleIntent(session, intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            headsetManager.registerHeadsetPlugReceiver(
                this,
                onConnected = {},
                onDisconnected = { pause() }
            )
        }

        bluetoothManager.registerBluetoothReceiver(
            this,
            onConnected = {},
            onDisconnected = { pause() }
        )

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        audioPlayer.release()
        cancelNotification()
        try {
            headsetManager.unregisterHeadsetPlugReceiver(this)
        } catch (_: Exception) {
        }
        try {
            bluetoothManager.unregisterBluetoothReceiver(this)
        } catch (_: Exception) {
        }
    }

    fun play(
        url: String,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        imageUrl: String? = null,
        startAutomatically: Boolean = true,
        startFromMillis: Long = 0L
    ) {
        requestFocus()

        audioPlayer.play(url, startAutomatically, startFromMillis)

        session.isActive = true
        currentPlaybackState = PlaybackStateCompat.STATE_PLAYING
        updatePlaybackState()

        showNotification(
            title = title ?: "",
            artist = artist ?: "",
            album = album ?: "",
            imageUrl = imageUrl ?: ""
        )
    }

    fun resume() {
        requestFocus()
        audioPlayer.resume()
        currentPlaybackState = PlaybackStateCompat.STATE_PLAYING
        updatePlaybackState()
    }

    fun pause() {
        audioPlayer.pause()
        currentPlaybackState = PlaybackStateCompat.STATE_PAUSED
        updatePlaybackState()

        if (!resumeOnAudioFocus) {
            abandonFocus()
        }
    }

    fun stop() {
        audioPlayer.stop()
        currentPlaybackState = PlaybackStateCompat.STATE_STOPPED
        currentPositionInMillis = 0L
        durationInMillis = 0L

        cancelNotification()
        session.isActive = false
        abandonFocus()
        stopSelf()
    }

    fun seekTo(timeInMillis: Long) {
        audioPlayer.seekTo(timeInMillis)
    }

    fun skipForward() {
        seekTo(currentPositionInMillis + SKIP_FORWARD_TIME_MILLIS)
    }

    fun skipBackward() {
        val seekTime = currentPositionInMillis - SKIP_BACKWARD_TIME_MILLIS
        seekTo(if (seekTime < 0) 0 else seekTime)
    }

    private fun requestFocus() {
        AudioManagerCompat.requestAudioFocus(audioManager, audioFocusRequest)
    }

    fun abandonFocus() {
        AudioManagerCompat.abandonAudioFocusRequest(audioManager, audioFocusRequest)
    }

    @TargetApi(26)
    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = NOTIFICATION_CHANNEL_DESCRIPTION
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
        )
    }

    private fun pendingIntentFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    private fun startPlaybackForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notificationBuilder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notificationBuilder.build())
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun updateNotificationBuilder(
        title: String,
        artist: String,
        album: String,
        @ColorInt notificationColor: Int? = null,
        image: Bitmap? = null
    ) {
        metadata.putString(METADATA_KEY_TITLE, title)
            .putString(METADATA_KEY_ARTIST, artist)
            .putBitmap(METADATA_KEY_ALBUM_ART, image)

        session.setMetadata(metadata.build())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(this, 0, it, pendingIntentFlags())
        }

        val stopIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this,
            PlaybackStateCompat.ACTION_STOP
        )

        val mediaStyle = MediaStyle()
            .setMediaSession(session.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)
            .setCancelButtonIntent(stopIntent)
            .setShowCancelButton(true)

        notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setStyle(mediaStyle)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSmallIcon(R.drawable.native_audio_notification_icon)
            .setDeleteIntent(stopIntent)
            .setContentTitle(title)
            .setContentText(album)
            .setSubText(artist)

        if (contentIntent != null) {
            notificationBuilder.setContentIntent(contentIntent)
        }

        if (image != null) {
            notificationBuilder.setLargeIcon(image)
        }

        if (notificationColor != null) {
            notificationBuilder.color = notificationColor
        }

        setNotificationButtons(notificationBuilder, isPlaying())
    }

    @SuppressLint("RestrictedApi")
    private fun setNotificationButtons(
        builder: NotificationCompat.Builder,
        isPlaying: Boolean = true
    ) {
        builder.mActions.clear()

        val rewindIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this,
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        )
        if (rewindIntent != null) {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_previous,
                    "Rewind",
                    rewindIntent
                ).build()
            )
        }

        val playPauseIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this,
            PlaybackStateCompat.ACTION_PLAY_PAUSE
        )
        if (playPauseIntent != null) {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                    if (isPlaying) "Pause" else "Play",
                    playPauseIntent
                ).build()
            )
        }

        val forwardIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
            this,
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        )
        if (forwardIntent != null) {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_next,
                    "Fast Forward",
                    forwardIntent
                ).build()
            )
        }
    }

    private fun showNotification(
        title: String,
        artist: String,
        album: String,
        imageUrl: String? = null
    ) {
        if (imageUrl.isNullOrBlank()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                updateNotificationBuilder(title, artist, album)
            }
            startPlaybackForeground()
            isNotificationShown = true
            return
        }

        Glide.with(this)
            .asBitmap()
            .load(imageUrl)
            .into(object : SimpleTarget<Bitmap>() {
                override fun onResourceReady(
                    resource: Bitmap,
                    transition: Transition<in Bitmap>?
                ) {
                    Palette.from(resource).generate { palette ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            if (palette != null) {
                                val color = palette.getVibrantColor(Color.WHITE)
                                updateNotificationBuilder(
                                    title = title,
                                    artist = artist,
                                    album = album,
                                    notificationColor = color,
                                    image = resource
                                )
                            } else {
                                updateNotificationBuilder(
                                    title = title,
                                    artist = artist,
                                    album = album,
                                    image = resource
                                )
                            }
                        }

                        startPlaybackForeground()
                        isNotificationShown = true
                    }
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    super.onLoadFailed(errorDrawable)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        updateNotificationBuilder(title, artist, album)
                    }
                    startPlaybackForeground()
                    isNotificationShown = true
                }
            })
    }

    fun cancelNotification() {
        stopForeground(true)
        notificationManager.cancel(NOTIFICATION_ID)
        isNotificationShown = false
    }

    private fun updatePlaybackState() {
        val playbackState = playbackStateBuilder
            .setState(currentPlaybackState, currentPositionInMillis, 0f)
            .build()

        session.setPlaybackState(playbackState)

        if (isNotificationShown) {
            val stateChanged = currentPlaybackState != oldPlaybackState

            setNotificationButtons(notificationBuilder, isPlaying())
            notificationBuilder.setOngoing(isPlaying())

            if (isPlaying() && stateChanged) {
                startPlaybackForeground()
            } else if (isPlaying()) {
                notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
            } else {
                stopForeground(false)
                notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
            }
        }

        oldPlaybackState = currentPlaybackState
    }

    private fun isPlaying(): Boolean {
        return currentPlaybackState == PlaybackStateCompat.STATE_PLAYING
    }

    inner class AudioServiceBinder : Binder() {
        fun getService(): AudioService = this@AudioService
    }
}