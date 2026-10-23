package app.zxtune.device.media

import android.content.Context
import android.net.Uri
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.text.TextUtils
import android.widget.Toast
import app.zxtune.Logger
import app.zxtune.MainService
import app.zxtune.Releaseable
import app.zxtune.ReleaseableStub
import app.zxtune.TimeStamp
import app.zxtune.Util
import app.zxtune.core.Identifier
import app.zxtune.core.ModuleAttributes
import app.zxtune.coverart.BitmapLoader
import app.zxtune.coverart.CoverartProviderClient
import app.zxtune.fs.Vfs
import app.zxtune.fs.VfsExtensions
import app.zxtune.fs.shareUrl
import app.zxtune.playback.Callback
import app.zxtune.playback.Item
import app.zxtune.playback.PlayableItem
import app.zxtune.playback.PlaybackControl
import app.zxtune.playback.PlaybackService
import app.zxtune.utils.getParcelableCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

//! Events gate from local service to mediasession
internal class StatusCallback private constructor(
    private val ctx: Context,
    private val ctrl: PlaybackControl,
    private val session: MediaSessionCompat
) : Callback,
    Releaseable {
    private val scope = MainScope()
    private val coverartClient = CoverartProviderClient(ctx)
    private val lockscreenImageUrl = AtomicReference<Uri>()
    private val bitmapLoader by lazy {
        BitmapLoader("lockscreen", ctx, maxSize = 5, maxImageSize = 320)
    }
    private var subscription: Releaseable = ReleaseableStub
    private var state = PlaybackControl.State.STOPPED
    private var position = TimeStamp.EMPTY
    private var statePublished = false

    override fun onInitialState(state: PlaybackControl.State) = onStateChanged(state, TimeStamp.EMPTY)

    override fun onStateChanged(state: PlaybackControl.State, pos: TimeStamp) {
        this.state = state
        this.position = pos
        statePublished = true
        publishState()
    }

    private fun publishState() {
        val playbackState = PlaybackStateCompat.Builder().apply {
            setActions(ACTIONS)
            addCustomAction(repeatAction())
            addCustomAction(shuffleAction())
            setState(state.toState(), position.toMilliseconds(), 1f)
        }.build()
        session.setPlaybackState(playbackState)
        session.isActive = state !== PlaybackControl.State.STOPPED
        LOG.d { "published state=$state position=${position.toMilliseconds()} active=${session.isActive}" }
    }

    // Android Auto draws no standard repeat/shuffle buttons, the state travels as custom
    // actions whose icons and names must follow the actual modes - so the whole state is
    // rebuilt on changes
    private fun repeatAction() = PlaybackStateCompat.CustomAction.Builder(
        MainService.CUSTOM_ACTION_REPEAT,
        ctx.getString(repeatLabel(ctrl.trackMode)),
        repeatIcon(ctrl.trackMode)
    ).build()

    private fun shuffleAction() = PlaybackStateCompat.CustomAction.Builder(
        MainService.CUSTOM_ACTION_SHUFFLE,
        ctx.getString(shuffleLabel(ctrl.sequenceMode)),
        shuffleIcon(ctrl.sequenceMode)
    ).build()

    // both indicators describe a single state, so any change updates them together
    fun publishModes() {
        session.setShuffleMode(ctrl.sequenceMode.toShuffleMode())
        session.setRepeatMode(ctrl.toRepeatMode())
        if (statePublished) {
            publishState()
        }
    }

    override fun release() {
        subscription.release()
        scope.cancel()
    }

    override fun onItemChanged(item: Item) = try {
        val dataId = item.dataId
        val builder = MediaMetadataCompat.Builder().apply {
            val title = Util.formatTrackTitle(item.title, dataId)
            putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            val author = item.author
            if (author.isNotEmpty()) {
                putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, author)
                putString(MediaMetadataCompat.METADATA_KEY_ARTIST, author)
            } else {
                // Do not localize
                putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Unknown artist")
            }
            putNonEmptyString(ModuleAttributes.TITLE, item.title)
            putNonEmptyString(ModuleAttributes.AUTHOR, item.author)
            putNonEmptyString(ModuleAttributes.COMMENT, item.comment)
            putNonEmptyString(ModuleAttributes.PROGRAM, item.program)
            putNonEmptyString(ModuleAttributes.STRINGS, item.strings)
            if (item is PlayableItem) {
                putNonEmptyString(
                    ModuleAttributes.CHANNELS_NAMES,
                    item.module.getProperty(ModuleAttributes.CHANNELS_NAMES, "")
                )
            }
            putLong(MediaMetadataCompat.METADATA_KEY_DURATION, item.duration.toMilliseconds())
            putLong(ModuleAttributes.SIZE, item.size)
            putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, dataId.toString())
            putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, item.id.toString())
        }
        fillObjectUrls(dataId, builder).takeIf { Build.VERSION.SDK_INT <= 29 }?.let {
            // https://developer.android.com/media/legacy/mediasession#album_artwork
            fillAlbumArtwork(it, builder)
        }
        session.setMetadata(builder.build())
    } catch (e: Exception) {
        LOG.w(e) { "onItemChanged()" }
    }

    private fun fillObjectUrls(
        dataId: Identifier, builder: MediaMetadataCompat.Builder
    ): Uri? = with(builder) {
        try {
            val obj = Vfs.resolve(dataId.dataLocation)
            putNonEmptyString(VfsExtensions.SHARE_URL, obj.shareUrl)
            coverartClient.getMediaUris(dataId)?.let { uris ->
                var preferableResult: Uri? = null
                for (key in arrayOf(
                    MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI,
                    MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI,
                    MediaMetadataCompat.METADATA_KEY_ART_URI
                )) {
                    uris.getParcelableCompat<Uri>(key)?.let {
                        preferableResult = it
                        putString(key, it.toString())
                    }
                }
                return preferableResult
            }
        } catch (e: Exception) {
            LOG.w(e) { "Failed to get object urls" }
        }
        return null
    }

    override fun onError(e: String) {
        scope.launch {
            Toast.makeText(ctx, e, Toast.LENGTH_SHORT).show()
        }
    }

    private fun fillAlbumArtwork(uri: Uri, builder: MediaMetadataCompat.Builder) {
        bitmapLoader.getCached(uri)?.bitmap?.let {
            LOG.d { "Cached album art" }
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
            return
        }
        lockscreenImageUrl.set(uri)
        scope.launch {
            bitmapLoader.load(uri).bitmap?.let { bitmap ->
                if (lockscreenImageUrl.compareAndSet(uri, null)) {
                    LOG.d { "Fetched album art" }
                    builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bitmap)
                    session.setMetadata(builder.build())
                } else {
                    LOG.d { "Drop outdated album art retrieval" }
                }
            }
        }
    }

    companion object {
        private val LOG = Logger(StatusCallback::class.java.name)

        // repeating is handled by the session callback, but the button is shown by the client
        // only when the state advertises the corresponding action
        private val ACTIONS = PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SET_REPEAT_MODE or PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE

        @JvmStatic
        fun subscribe(
            ctx: Context, svc: PlaybackService, session: MediaSessionCompat
        ) = StatusCallback(ctx, svc.playbackControl, session).also {
            it.publishModes()
            it.subscription = svc.subscribe(it)
            }
    }
}

private fun MediaMetadataCompat.Builder.putNonEmptyString(key: String, value: String?) = apply {
    if (!TextUtils.isEmpty(value)) {
        putString(key, value)
    }
}