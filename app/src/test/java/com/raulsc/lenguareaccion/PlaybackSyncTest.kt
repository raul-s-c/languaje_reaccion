package com.raulsc.lenguareaccion

import org.junit.Assert.*
import org.junit.Test

class PlaybackSyncTest {
    @Test fun audioUsesMicrosecondsWithoutChangingSign() {
        assertEquals(200_000L, audioDelayMicros(200))
        assertEquals(-300_000L, audioDelayMicros(-300))
        assertEquals(0L, audioDelayMicros(0))
    }
    @Test fun jointShiftPreservesDifferenceAtBothLimits() {
        assertEquals(5_000L to 4_000L, shiftTogether(4_950, 3_950, 100))
        assertEquals(-4_000L to -5_000L, shiftTogether(-3_950, -4_950, -100))
        assertEquals(200L to 300L, shiftTogether(100, 200, 100))
    }
    @Test fun progressKeysAreStableAndDoNotExposeUrls() {
        assertEquals(playbackKey("content://video/1"), playbackKey("content://video/1"))
        assertNotEquals(playbackKey("content://video/1"), playbackKey("content://video/2"))
        assertEquals(64, playbackKey("https://plex/?token=secret").length)
    }
    @Test fun formatsElapsedTime() {
        assertEquals("20:00", playbackTime(1_200_000))
        assertEquals("0:00", playbackTime(-1))
    }
}
