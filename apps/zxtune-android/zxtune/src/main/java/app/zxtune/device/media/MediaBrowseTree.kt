package app.zxtune.device.media

import android.content.Context
import android.net.Uri
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import app.zxtune.Logger
import app.zxtune.R
import app.zxtune.core.Identifier
import app.zxtune.coverart.CoverartProviderClient
import app.zxtune.fs.provider.Schema
import app.zxtune.fs.provider.VfsProviderClient
import app.zxtune.playlist.ProviderClient
import app.zxtune.ui.playlist.Entry
import app.zxtune.utils.getParcelableCompat

// Browsable content tree for media clients having no access to the app's own UI
// (Android Auto first of all): the playlist plus the file browser. Storages require
// permissions and remote catalogs require network, so a listing may come back empty -
// the browsing client simply has nothing to show there, which is not an error.
internal class MediaBrowseTree(
    private val ctx: Context,
    private val client: ProviderClient,
    private val vfs: VfsProviderClient = VfsProviderClient(ctx),
    private val coverart: CoverartProviderClient = CoverartProviderClient(ctx)
) {
    val roots
        get() = listOf(
            folder(PLAYLIST, ctx.getString(R.string.playlist)),
            folder(BROWSER, ctx.getString(R.string.browser))
        )

    // Android Auto requests the root returned from getRoot first, then every listed container
    suspend fun children(mediaId: String): List<MediaItem> = when (mediaId) {
        ROOT -> roots

        PLAYLIST -> tracks()

        BROWSER -> browser(ROOT_URI)

        // any other media id addresses a browsed container by its vfs uri
        else -> browser(Uri.parse(mediaId))
    }

    // provider returns nothing on failure, which is not an error for the browsing client -
    // it just has nothing to show
    suspend fun tracks() = client.queryContent().orEmpty().map(::track)

    suspend fun track(mediaId: String) = client.queryContent().orEmpty().find {
        it.uri.toString() == mediaId
    }?.let(::track)

    // a playlist track is addressed by its content uri, a browsed item by its vfs uri
    suspend fun item(mediaId: String) = track(mediaId) ?: browserItem(mediaId)

    private fun track(entry: Entry): MediaItem {
        val uri = entry.uri
        return MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId(uri.toString())
                .setMediaUri(uri)
                .setTitle(entry.displayTitle)
                .setSubtitle(entry.author)
                .setIconUri(artworkOf(entry.location))
                .build(),
            MediaItem.FLAG_PLAYABLE
        )
    }

    private suspend fun browser(uri: Uri): List<MediaItem> {
        val result = ArrayList<MediaItem>()
        runCatching {
            vfs.list(uri, object : VfsProviderClient.ListingCallback {
                override fun onProgress(status: Schema.Status.Progress) = Unit
                override fun onDir(dir: Schema.Listing.Dir) {
                    result.add(browserFolder(dir))
                }

                override fun onFile(file: Schema.Listing.File) {
                    // the player has nothing to do with an unsupported file
                    if (file.type != Schema.Listing.File.Type.UNSUPPORTED) {
                        result.add(browserTrack(file))
                    }
                }
            })
        }.onFailure {
            LOG.w(it) { "Failed to list $uri" }
        }
        return result
    }

    private suspend fun browserItem(mediaId: String): MediaItem? {
        val uri = Uri.parse(mediaId)
        var result: MediaItem? = null
        runCatching {
            vfs.resolve(uri, object : VfsProviderClient.ListingCallback {
                override fun onProgress(status: Schema.Status.Progress) = Unit
                override fun onDir(dir: Schema.Listing.Dir) {
                    result = browserFolder(dir)
                }

                override fun onFile(file: Schema.Listing.File) {
                    result = browserTrack(file)
                }
            })
        }.onFailure {
            LOG.w(it) { "Failed to resolve $uri" }
        }
        return result
    }

    // a directory with a feed plays as a whole, but stays browsable too
    private fun browserFolder(dir: Schema.Listing.Dir) = MediaItem(
        MediaDescriptionCompat.Builder()
            .setMediaId(dir.uri.toString())
            .setTitle(dir.name)
            .setSubtitle(dir.description)
            .build(),
        if (dir.hasFeed) {
            MediaItem.FLAG_BROWSABLE or MediaItem.FLAG_PLAYABLE
        } else {
            MediaItem.FLAG_BROWSABLE
        }
    )

    // icons of browsed objects are app-internal resource uris which external clients
    // cannot load, so they are left out in favor of the client's own glyphs
    private fun browserTrack(file: Schema.Listing.File) = MediaItem(
        MediaDescriptionCompat.Builder()
            .setMediaId(file.uri.toString())
            .setMediaUri(file.uri)
            .setTitle(file.name)
            .setSubtitle(file.description)
            .build(),
        MediaItem.FLAG_PLAYABLE
    )

    private fun folder(mediaId: String, title: String) = MediaItem(
        MediaDescriptionCompat.Builder()
            .setMediaId(mediaId)
            .setTitle(title)
            .build(),
        MediaItem.FLAG_BROWSABLE
    )

    private fun artworkOf(id: Identifier) = try {
        coverart.getMediaUris(id)?.let { uris ->
            ART_KEYS.firstNotNullOfOrNull { uris.getParcelableCompat<Uri>(it) }
        }
    } catch (e: Exception) {
        LOG.w(e) { "Failed to get artwork of $id" }
        null
    }

    companion object {
        // Media ids are opaque for the clients, so keep them stable and non-localizable
        const val ROOT = "/"
        const val PLAYLIST = "/playlist"
        const val BROWSER = "/browser"

        // the vfs root is addressed by a media id of its own: its uri is empty
        private val ROOT_URI = Uri.EMPTY

        // same order of preference as the one used for the session's metadata
        private val ART_KEYS = arrayOf(
            MediaMetadataCompat.METADATA_KEY_ART_URI,
            MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI
        )
        private val LOG = Logger(MediaBrowseTree::class.java.name)
    }
}

private val Entry.uri
    get() = ProviderClient.createUri(id)
