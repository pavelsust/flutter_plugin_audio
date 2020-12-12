package com.flutter_plugin_audio

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.annotation.NonNull
import com.danielgauci.native_audio.isServiceRunning

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.view.FlutterNativeView

/** FlutterPluginAudioPlugin */
class FlutterPluginAudioPlugin: FlutterPlugin, MethodCallHandler, ActivityAware {


  private lateinit var channel : MethodChannel
  private lateinit var context: Context


  companion object {
    
    private const val NATIVE_METHOD_PLAY = "play"
    private const val NATIVE_METHOD_PLAY_ARG_URL = "url"
    private const val NATIVE_METHOD_PLAY_ARG_TITLE = "title"
    private const val NATIVE_METHOD_PLAY_ARG_ARTIST = "artist"
    private const val NATIVE_METHOD_PLAY_ARG_ALBUM = "album"
    private const val NATIVE_METHOD_PLAY_ARG_IMAGE_URL = "imageUrl"
    private const val NATIVE_METHOD_PLAY_START_AUTOMATICALLY = "startAutomatically"
    private const val NATIVE_METHOD_PLAY_START_FROM_MILLIS = "startFromMillis"
    private const val NATIVE_METHOD_RESUME = "resume"
    private const val NATIVE_METHOD_PAUSE = "pause"
    private const val NATIVE_METHOD_STOP = "stop"
    private const val NATIVE_METHOD_SKIP_FORWARD = "skipForward"
    private const val NATIVE_METHOD_SKIP_BACKWARD = "skipBackward"
    private const val NATIVE_METHOD_SEEK_TO = "seekTo"
    private const val NATIVE_METHOD_SEEK_TO_ARG_TIME = "timeInMillis"
    private const val NATIVE_METHOD_RELEASE = "release"
    private const val NATIVE_METHOD_SET_SKIP_TIME = "setSkipTime"
    private const val NATIVE_METHOD_SET_SKIP_TIME_ARG_FORWARD_MILLIS = "forwardMillis"
    private const val NATIVE_METHOD_SET_SKIP_TIME_ARG_BACKWARD_MILLIS = "backwardMillis"
    private const val FLUTTER_METHOD_ON_LOADED = "onLoaded"
    private const val FLUTTER_METHOD_ON_LOADED_ARG_STARTED_AUTOMATICALLY = "startedAutomatically"
    private const val FLUTTER_METHOD_ON_LOADED_ARG_TOTAL_DURATION_IN_MILLIS = "totalDurationInMillis"
    private const val FLUTTER_METHOD_ON_PROGRESS_CHANGED = "onProgressChanged"
    private const val FLUTTER_METHOD_ON_RESUMED = "onResumed"
    private const val FLUTTER_METHOD_ON_PAUSED = "onPaused"
    private const val FLUTTER_METHOD_ON_STOPPED = "onStopped"
    private const val FLUTTER_METHOD_ON_COMPLETED = "onCompleted"
    private const val FLUTTER_METHOD_ON_NEXT = "onNext"
    private const val FLUTTER_METHOD_ON_PREVIOUS = "onPrevious"

    private var audioService: AudioService? = null

  }

  private var serviceConnection: ServiceConnection? = null

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "flutter_plugin_audio")
    channel.setMethodCallHandler(this)
    context = flutterPluginBinding.applicationContext

  }
  private var flutterView: FlutterNativeView? = null
  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    if (call.method == "getPlatformVersion") {
      result.success("Android ${android.os.Build.VERSION.RELEASE}")
    } else {
      result.notImplemented()
    }


    withService { service ->

      when (call.method) {
        NATIVE_METHOD_PLAY -> {
          withArgument(call, NATIVE_METHOD_PLAY_ARG_URL) { url: String ->
            // Get optional arguments
            val title = call.argument<String>(NATIVE_METHOD_PLAY_ARG_TITLE)
            val artist = call.argument<String>(NATIVE_METHOD_PLAY_ARG_ARTIST)
            val album = call.argument<String>(NATIVE_METHOD_PLAY_ARG_ALBUM)
            val imageUrl = call.argument<String>(NATIVE_METHOD_PLAY_ARG_IMAGE_URL)
            val startAutomatically = call.argument<Boolean>(NATIVE_METHOD_PLAY_START_AUTOMATICALLY)
            val startFromMillis = call.argument<Int>(NATIVE_METHOD_PLAY_START_FROM_MILLIS)

            // Call service
            withService {
              it.play(
                      url = url,
                      title = title,
                      artist = artist,
                      album = album,
                      imageUrl = imageUrl,
                      startAutomatically = startAutomatically ?: true,
                      startFromMillis = startFromMillis?.toLong() ?: 0L
              )
            }
          }
        }
        NATIVE_METHOD_RESUME -> service.resume()
        NATIVE_METHOD_PAUSE -> service.pause()
        NATIVE_METHOD_STOP -> service.stop()
        NATIVE_METHOD_RELEASE -> releaseAudioService()
        NATIVE_METHOD_SEEK_TO -> {
          withArgument(call, NATIVE_METHOD_SEEK_TO_ARG_TIME) { timeInMillis: Long ->
            service.seekTo(timeInMillis)
          }
        }
        NATIVE_METHOD_SKIP_FORWARD -> service.skipForward()
        NATIVE_METHOD_SKIP_BACKWARD -> service.skipBackward()
        NATIVE_METHOD_SET_SKIP_TIME -> {
          withArgument(call, NATIVE_METHOD_SET_SKIP_TIME_ARG_FORWARD_MILLIS) { forward: Long ->
            withArgument(call, NATIVE_METHOD_SET_SKIP_TIME_ARG_BACKWARD_MILLIS) { rewind: Long ->
              AudioService.SKIP_FORWARD_TIME_MILLIS = forward
              AudioService.SKIP_BACKWARD_TIME_MILLIS = rewind
            }
          }
        }
      }
    }
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }

  /**
   *   Flutter attached
   *
   */

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
      binding.activity.registerActivityLifecycleCallbacks(object :Application.ActivityLifecycleCallbacks{
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
          Log.d("NATIVE_PLUGIN", "onActivityCreated")
        }

        override fun onActivityStarted(activity: Activity) {
          Log.d("NATIVE_PLUGIN", "onActivityStarted")

          val serviceIntent = Intent(context, AudioService::class.java)
          if (!context.isServiceRunning(AudioService::class.java)) context.startService(serviceIntent)
          serviceConnection?.let { context.bindService(serviceIntent, it, Context.BIND_AUTO_CREATE) }

        }

        override fun onActivityResumed(activity: Activity) {
          Log.d("NATIVE_PLUGIN", "onActivityResumed")
        }

        override fun onActivityPaused(activity: Activity) {
          Log.d("NATIVE_PLUGIN", "onActivityPaused")
        }

        override fun onActivityStopped(activity: Activity) {
          Log.d("NATIVE_PLUGIN", "onActivityStopped")
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        
        }

        override fun onActivityDestroyed(activity: Activity) {
          Log.d("NATIVE_PLUGIN", "onActivityDestroyed")
          audioService?.stop()
        }

      })
    }
  }

  private fun withService(withService: (AudioService) -> Unit) {
    if (audioService == null) {

      // Audio service not available yes, bind and setup
      serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
          val service = (binder as AudioService.AudioServiceBinder).getService()
          bindAudioServiceWithChannel(service)
          withService(service)

          audioService = service
        }

        override fun onServiceDisconnected(name: ComponentName?) {
          audioService = null
        }
      }

      val serviceIntent = Intent(context, AudioService::class.java)
      if (!context.isServiceRunning(AudioService::class.java)) context.startService(serviceIntent)
      context.bindService(serviceIntent, serviceConnection as ServiceConnection, Context.BIND_AUTO_CREATE)

      // Return and wait for service to be connected
      return
    }

    // Call lambda with service
    withService(audioService!!)
  }


  private fun <T> withArgument(methodCall: MethodCall, argumentKey: String, withArgument: (T) -> Unit) {
    val argument = methodCall.argument<T>(argumentKey)
            ?: throw IllegalArgumentException("Argument $argumentKey is required when calling the ${methodCall.method} method.")

    withArgument(argument)
  }

  private fun bindAudioServiceWithChannel(service: AudioService) {
    service.apply {
      // Notify flutter with audio updates
      onLoaded = { totalDurationInMillis, startedAutomatically ->
        try {
          channel.invokeMethod(FLUTTER_METHOD_ON_LOADED, mapOf(
                  FLUTTER_METHOD_ON_LOADED_ARG_TOTAL_DURATION_IN_MILLIS to totalDurationInMillis,
                  FLUTTER_METHOD_ON_LOADED_ARG_STARTED_AUTOMATICALLY to startedAutomatically
          ))
        } catch (e: Exception) {
          Log.e(this::class.java.simpleName, e.message, e)
        }
      }

      onProgressChanged = {
        try {
          channel.invokeMethod(FLUTTER_METHOD_ON_PROGRESS_CHANGED, it)
        } catch (e: Exception) {
          Log.e(this::class.java.simpleName, e.message, e)
        }
      }

      onResumed = {
        try {
          channel.invokeMethod(FLUTTER_METHOD_ON_RESUMED, null)
        } catch (e: Exception) {
          Log.e(this::class.java.simpleName, e.message, e)
        }
      }

      onPaused = {
        try {
          channel.invokeMethod(FLUTTER_METHOD_ON_PAUSED, null)
        } catch (e: Exception) {
          Log.e(this::class.java.simpleName, e.message, e)
        }
      }

      onStopped = {
        try {
          channel.invokeMethod(FLUTTER_METHOD_ON_STOPPED, null)
        } catch (e: Exception) {
          Log.e(this::class.java.simpleName, e.message, e)
        }
      }

      onCompleted = {
        try {
          channel.invokeMethod(FLUTTER_METHOD_ON_COMPLETED, null)
        } catch (e: Exception) {
          Log.e(this::class.java.simpleName, e.message, e)
        }
      }

      onNext ={
        try {
          channel.invokeMethod(FLUTTER_METHOD_ON_NEXT, null)
        } catch (e: Exception) {
          Log.e(this::class.java.simpleName, e.message, e)
        }
      }

      onPrevious = {
        try {
          channel.invokeMethod(FLUTTER_METHOD_ON_PREVIOUS, null)
        } catch (e: Exception) {
          Log.e(this::class.java.simpleName, e.message, e)
        }
      }

    }
  }

  private fun releaseAudioService() {
    serviceConnection?.let { context.unbindService(it) }
    context.stopService(Intent(context, AudioService::class.java))
  }

  override fun onDetachedFromActivityForConfigChanges() {

  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {

  }

  override fun onDetachedFromActivity() {
    Log.d("NATIVE_PLUGIN", "Detached from Activity")
  }
}
