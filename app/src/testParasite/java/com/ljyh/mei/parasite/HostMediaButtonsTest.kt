package com.ljyh.mei.parasite

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostMediaButtonsTest {
    @Test fun onlyInitialPlayKeysMayStartAService() {
        for (key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK)) {
            assertTrue(canResumeFromMediaButton(KeyEvent.ACTION_DOWN, key, 0))
            assertFalse(canResumeFromMediaButton(KeyEvent.ACTION_UP, key, 0))
            assertFalse(canResumeFromMediaButton(KeyEvent.ACTION_MULTIPLE, key, 0))
            assertFalse(canResumeFromMediaButton(KeyEvent.ACTION_DOWN, key, 1))
        }
    }

    @Test fun pauseSkipStopAndUnrelatedKeysCannotStartAService() {
        for (key in listOf(KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_ENTER)) {
            assertFalse(canResumeFromMediaButton(KeyEvent.ACTION_DOWN, key, 0))
        }
    }
}
