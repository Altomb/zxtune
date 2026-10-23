package app.zxtune.device.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ResultReceiver
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.support.v4.media.session.PlaybackStateCompat.RepeatMode
import android.support.v4.media.session.PlaybackStateCompat.ShuffleMode
import app.zxtune.Logger
import app.zxtune.MainService
import app.zxtune.ScanService
import app.zxtune.TimeStamp.Companion.fromMilliseconds
import app.zxtune.core.PropertiesAccessor
import app.zxtune.core.PropertiesModifier
import app.zxtune.playback.PlaybackControl
import app.zxtune.playback.service.PlaybackServiceLocal
import app.zxtune.playback.stubs.PlayableItemStub
import app.zxtune.preferences.RawPropertiesAdapter
import app.zxtune.utils.getParcelableArrayCompat
import app.zxtune.utils.getUntyped
import app.zxtune.utils.ifNotNulls

internal class ControlCallback(
    private val ctx: Context,
    private val svc: PlaybackServiceLocal,
    private val statusCallback: StatusCallback,
) : MediaSessionCompat.Callback() {
    private val ctrl
        get() = svc.playbackControl
    private val seek
        get() = svc.seekControl

    override fun onPlay() = ctrl.play()

    override fun onPause() = onStop()

    override fun onStop() = ctrl.stop()

    override fun onSkipToPrevious() = ctrl.prev()

    override fun onSkipToNext() = ctrl.next()

    override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
        return super.onMediaButtonEvent(mediaButtonEvent)
    }

    override fun onSeekTo(ms: Long) {
        try {
            seek.position = fromMilliseconds(ms)
        } catch (e: Exception) {
            LOG.w(e) { "Failed to seek" }
        }
    }

    override fun onCustomAction(action: String, extra: Bundle?) = when (action) {
        MainService.CUSTOM_ACTION_ADD_CURRENT -> addCurrent()
        MainService.CUSTOM_ACTION_ADD -> extra?.getParcelableArrayCompat<Uri>("uris")?.let {
            ScanService.add(ctx, Array(it.size) { idx -> it[idx] as Uri })
        } ?: Unit

        // cars mirror the phone toggles: repeat flips the "loop track" mode
        // and shuffle flips the sequence mode
        MainService.CUSTOM_ACTION_REPEAT -> {
            ctrl.trackMode = nextTrackMode(ctrl.trackMode)
            publishModes()
        }

        MainService.CUSTOM_ACTION_SHUFFLE -> {
            ctrl.sequenceMode = nextSequenceMode(ctrl.sequenceMode)
            publishModes()
        }

        else -> Unit
    }

    override fun onCommand(command: String?, extras: Bundle?, cb: ResultReceiver?) =
        when (command) {
            MainService.COMMAND_SET_CURRENT_PARAMETERS -> ifNotNulls(
                extras, svc.playbackProperties
            ) { data, props ->
                setProperties(data, props)
                cb?.send(0, data)
            } ?: cb?.send(-1, null) ?: Unit

            MainService.COMMAND_GET_CURRENT_PARAMETERS -> ifNotNulls(
                extras, svc.playbackProperties, cb
            ) { query, props, receiver ->
                getProperties(props, query)
                receiver.send(0, query)
            } ?: cb?.send(-1, null) ?: Unit

            else -> Unit
        }

    private fun addCurrent() = svc.nowPlaying.takeIf { it !== PlayableItemStub.instance() }?.let {
        ScanService.add(ctx, it)
    } ?: Unit

    override fun onSetShuffleMode(@ShuffleMode mode: Int) = fromShuffleMode(mode)?.let {
        ctrl.sequenceMode = it
        publishModes()
    } ?: Unit

    // Android Auto rotates over none/one/all, so "all" is mapped onto looping the sequence
    // - keep the existing mapping for non-car clients; the custom action handles car UI
    override fun onSetRepeatMode(@RepeatMode mode: Int) {
        when (mode) {
            PlaybackStateCompat.REPEAT_MODE_NONE -> {
                ctrl.trackMode = PlaybackControl.TrackMode.REGULAR
                // looping the sequence is the very same state as "all", so it goes off as well
                if (ctrl.sequenceMode == PlaybackControl.SequenceMode.LOOPED) {
                    ctrl.sequenceMode = PlaybackControl.SequenceMode.ORDERED
                }
            }

            PlaybackStateCompat.REPEAT_MODE_ONE -> ctrl.trackMode = PlaybackControl.TrackMode.LOOPED

            PlaybackStateCompat.REPEAT_MODE_ALL -> {
                ctrl.trackMode = PlaybackControl.TrackMode.REGULAR
                ctrl.sequenceMode = PlaybackControl.SequenceMode.LOOPED
            }

            else -> return
        }
        publishModes()
    }

    // both indicators describe a single state, so any change updates them together
    private fun publishModes() = statusCallback.publishModes()

    override fun onPlayFromUri(uri: Uri, extras: Bundle?) = svc.setNowPlaying(uri)

    // Android Auto plays browsed items by their media id, which is the playlist content uri
    override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) = try {
        svc.setNowPlaying(Uri.parse(mediaId))
    } catch (e: Exception) {
        LOG.w(e) { "Failed to play media id '$mediaId'" }
    }

    companion object {
        private val LOG = Logger(ControlCallback::class.java.name)

        private fun setProperties(src: Bundle, props: PropertiesModifier) =
            with(RawPropertiesAdapter(props)) {
                src.keySet().forEach { key ->
                    src.getUntyped(key)?.let {
                        LOG.d { "set prop[$key]=$it" }
                        setProperty(key, it)
                    }
                }
            }

        private fun getProperties(src: PropertiesAccessor, data: Bundle) =
            data.keySet().forEach { key ->
                copyProperty(src, key, data)
            }

        private fun copyProperty(src: PropertiesAccessor, key: String, data: Bundle) =
            when (val obj = data.getUntyped(key)) {
                is String -> src.getProperty(key, obj).let {
                    LOG.d { "get prop[$key, $obj]=$it" }
                    data.putString(key, it)
                }

                is Long -> src.getProperty(key, obj).let {
                    LOG.d { "get prop[$key, $obj]=$it" }
                    data.putLong(key, it)
                }

                is Int -> src.getProperty(key, obj.toLong()).let {
                    LOG.d { "get prop[$key, $obj]=$it" }
                    data.putInt(key, it.toInt())
                }

                else -> Unit
            }
    }
}