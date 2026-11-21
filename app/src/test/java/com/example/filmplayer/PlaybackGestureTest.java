package com.example.filmplayer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackGestureTest {
    @Test
    public void leftSwipeExitsPlayback() {
        assertTrue(PlaybackGesture.isExitSwipe(-110f, 15f, 1f));
    }

    @Test
    public void heldDragUpOnRightRewindsAndRotatesCounterclockwise() {
        assertEquals(-5000L, PlaybackGesture.scrubDeltaMs(-100f, 2f, false));
        assertEquals(-60f, PlaybackGesture.scrubRotationDegrees(-100f, 2f, false), 0.001f);
    }

    @Test
    public void heldDragDownOnRightFastForwardsAndRotatesClockwise() {
        assertEquals(5000L, PlaybackGesture.scrubDeltaMs(100f, 2f, false));
        assertEquals(60f, PlaybackGesture.scrubRotationDegrees(100f, 2f, false), 0.001f);
    }

    @Test
    public void heldDragDownOnLeftRewindsAndRotatesCounterclockwise() {
        assertEquals(-5000L, PlaybackGesture.scrubDeltaMs(100f, 2f, true));
        assertEquals(-60f, PlaybackGesture.scrubRotationDegrees(100f, 2f, true), 0.001f);
    }

    @Test
    public void heldDragUpOnLeftFastForwardsAndRotatesClockwise() {
        assertEquals(5000L, PlaybackGesture.scrubDeltaMs(-100f, 2f, true));
        assertEquals(60f, PlaybackGesture.scrubRotationDegrees(-100f, 2f, true), 0.001f);
    }

    @Test
    public void shortOrRightSwipeDoesNotExit() {
        assertFalse(PlaybackGesture.isExitSwipe(-20f, 0f, 1f));
        assertFalse(PlaybackGesture.isExitSwipe(120f, 0f, 1f));
        assertFalse(PlaybackGesture.isExitSwipe(-110f, 130f, 1f));
    }

    @Test
    public void previewPositionMatchesSongTimelineAndStopsAtEnds() {
        assertEquals(75000L, PlaybackGesture.scrubPositionMs(60000L, 180000L, 15000L));
        assertEquals(0L, PlaybackGesture.scrubPositionMs(60000L, 180000L, -90000L));
        assertEquals(180000L, PlaybackGesture.scrubPositionMs(60000L, 180000L, 200000L));
    }
}
