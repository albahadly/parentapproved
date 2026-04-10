package tv.parentapproved.app.playback

import android.view.KeyEvent

object DpadKeyHandler {
    fun mapKeyToCommand(keyCode: Int): PlaybackCommand? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> PlaybackCommand.TogglePause

        KeyEvent.KEYCODE_MEDIA_NEXT -> PlaybackCommand.SkipNext

        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PlaybackCommand.SkipPrev

        KeyEvent.KEYCODE_BACK -> PlaybackCommand.Stop

        KeyEvent.KEYCODE_DPAD_LEFT -> PlaybackCommand.SeekBack
        KeyEvent.KEYCODE_DPAD_RIGHT -> PlaybackCommand.SeekForward

        else -> null
    }
}
