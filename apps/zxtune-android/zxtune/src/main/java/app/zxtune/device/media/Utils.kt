package app.zxtune.device.media

import android.support.v4.media.session.PlaybackStateCompat
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import app.zxtune.R
import app.zxtune.playback.PlaybackControl

@PlaybackStateCompat.State
internal fun PlaybackControl.State.toState() = when (this) {
    PlaybackControl.State.PLAYING -> PlaybackStateCompat.STATE_PLAYING
    PlaybackControl.State.SEEKING -> PlaybackStateCompat.STATE_BUFFERING //?
    PlaybackControl.State.STOPPED -> PlaybackStateCompat.STATE_STOPPED
}

@PlaybackStateCompat.ShuffleMode
internal fun PlaybackControl.SequenceMode.toShuffleMode() = when (this) {
    PlaybackControl.SequenceMode.ORDERED -> PlaybackStateCompat.SHUFFLE_MODE_NONE
    PlaybackControl.SequenceMode.SHUFFLE -> PlaybackStateCompat.SHUFFLE_MODE_ALL
    PlaybackControl.SequenceMode.LOOPED -> PlaybackStateCompat.SHUFFLE_MODE_GROUP
}

internal fun fromShuffleMode(@PlaybackStateCompat.ShuffleMode mode: Int) = when (mode) {
    PlaybackStateCompat.SHUFFLE_MODE_NONE -> PlaybackControl.SequenceMode.ORDERED
    PlaybackStateCompat.SHUFFLE_MODE_ALL -> PlaybackControl.SequenceMode.SHUFFLE
    PlaybackStateCompat.SHUFFLE_MODE_GROUP -> PlaybackControl.SequenceMode.LOOPED
    else -> null
}

// Remote clients (Android Auto first of all) expect none/one/all, while the app has a single
// "loop track" toggle; looping the whole sequence is reported as "all" since there is no
// better place for it - the shuffle control exposes the very same state as a group
@PlaybackStateCompat.RepeatMode
internal fun PlaybackControl.toRepeatMode() = when {
    trackMode == PlaybackControl.TrackMode.LOOPED -> PlaybackStateCompat.REPEAT_MODE_ONE
    sequenceMode == PlaybackControl.SequenceMode.LOOPED -> PlaybackStateCompat.REPEAT_MODE_ALL
    else -> PlaybackStateCompat.REPEAT_MODE_NONE
}

// Cars render custom actions as buttons and require a distinct icon and name per state.
// Both buttons mirror the phone toggles: repeat switches the "loop track" mode and
// shuffle switches the sequence mode, so the state is always the actual player state
internal fun nextTrackMode(mode: PlaybackControl.TrackMode) = when (mode) {
    PlaybackControl.TrackMode.REGULAR -> PlaybackControl.TrackMode.LOOPED
    PlaybackControl.TrackMode.LOOPED -> PlaybackControl.TrackMode.REGULAR
}

internal fun nextSequenceMode(mode: PlaybackControl.SequenceMode) = when (mode) {
    // looping the sequence has no button of its own, so any non-shuffled state turns shuffle on
    PlaybackControl.SequenceMode.ORDERED, PlaybackControl.SequenceMode.LOOPED -> PlaybackControl.SequenceMode.SHUFFLE

    PlaybackControl.SequenceMode.SHUFFLE -> PlaybackControl.SequenceMode.ORDERED
}

internal fun repeatIcon(mode: PlaybackControl.TrackMode) = when (mode) {
    PlaybackControl.TrackMode.REGULAR -> R.drawable.ic_repeat_off
    PlaybackControl.TrackMode.LOOPED -> R.drawable.ic_repeat_one
}

internal fun repeatLabel(mode: PlaybackControl.TrackMode) = when (mode) {
    PlaybackControl.TrackMode.REGULAR -> R.string.repeat_off
    PlaybackControl.TrackMode.LOOPED -> R.string.repeat_one
}

internal fun shuffleIcon(mode: PlaybackControl.SequenceMode) = when (mode) {
    PlaybackControl.SequenceMode.ORDERED, PlaybackControl.SequenceMode.LOOPED -> R.drawable.ic_sequence_shuffle_off
    PlaybackControl.SequenceMode.SHUFFLE -> R.drawable.ic_sequence_shuffle
}

internal fun shuffleLabel(mode: PlaybackControl.SequenceMode) = when (mode) {
    PlaybackControl.SequenceMode.ORDERED, PlaybackControl.SequenceMode.LOOPED -> R.string.shuffle_off
    PlaybackControl.SequenceMode.SHUFFLE -> R.string.shuffle_on
}