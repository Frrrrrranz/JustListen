package com.example.filmplayer;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class DiscCarouselStateTest {
    @Test
    public void firstSlotShowsLastTwoSlotsAboveIt() {
        DiscCarouselState state = new DiscCarouselState(10);

        assertArrayEquals(new int[]{8, 9, 0, 1, 2}, state.getVisibleSlotIndices());
    }

    @Test
    public void lastSlotContinuesWithFirstTwoSlots() {
        DiscCarouselState state = new DiscCarouselState(10);
        state.selectSlot(9);

        assertArrayEquals(new int[]{7, 8, 9, 0, 1}, state.getVisibleSlotIndices());
    }

    @Test
    public void halfSlotDragKeepsBothNeighboringDiscsInMotion() {
        DiscCarouselState state = new DiscCarouselState(10);

        state.moveBy(0.5f);

        assertEquals(0.5f, state.getPosition(), 0.0001f);
        assertEquals(0, state.getSlotIndexAtOffset(0));
        assertEquals(-0.5f, state.getRelativePosition(0), 0.0001f);
        assertEquals(1, state.getSlotIndexAtOffset(1));
        assertEquals(0.5f, state.getRelativePosition(1), 0.0001f);
    }

    @Test
    public void draggingAcrossLastAndFirstSlotKeepsTheirPositionsContinuous() {
        DiscCarouselState state = new DiscCarouselState(10);
        state.selectSlot(9);
        state.moveBy(0.75f);

        assertEquals(9, state.getSlotIndexAtOffset(0));
        assertEquals(-0.75f, state.getRelativePosition(0), 0.0001f);
        assertEquals(0, state.getSlotIndexAtOffset(1));
        assertEquals(0.25f, state.getRelativePosition(1), 0.0001f);

        state.moveBy(0.5f);

        assertEquals(0.25f, state.getPosition(), 0.0001f);
        assertEquals(9, state.getSlotIndexAtOffset(-1));
        assertEquals(-1.25f, state.getRelativePosition(-1), 0.0001f);
        assertEquals(0, state.getSlotIndexAtOffset(0));
        assertEquals(-0.25f, state.getRelativePosition(0), 0.0001f);

        state.moveBy(-0.5f);

        assertEquals(9.75f, state.getPosition(), 0.0001f);
        assertEquals(9, state.getSlotIndexAtOffset(0));
        assertEquals(-0.75f, state.getRelativePosition(0), 0.0001f);
        assertEquals(0, state.getSlotIndexAtOffset(1));
        assertEquals(0.25f, state.getRelativePosition(1), 0.0001f);
    }

    @Test
    public void snapNearLastSlotMovesForwardToFirstSlot() {
        DiscCarouselState state = new DiscCarouselState(10);
        state.selectSlot(9);
        state.moveBy(0.75f);

        assertEquals(10f, state.getSnapTarget(), 0.0001f);

        state.setPosition(state.getSnapTarget());

        assertEquals(0f, state.getPosition(), 0.0001f);
        assertEquals(0, state.getSlotIndexAtOffset(0));
    }

    @Test
    public void snapNearFirstSlotMovesBackwardAndReverseBoundaryStillWorks() {
        DiscCarouselState state = new DiscCarouselState(10);
        state.moveBy(-0.25f);

        assertEquals(10f, state.getSnapTarget(), 0.0001f);

        state.setPosition(state.getSnapTarget());
        state.moveBy(0.25f);

        assertEquals(0f, state.getSnapTarget(), 0.0001f);
    }

    @Test
    public void largerLibraryWrapsFromItsLastSongToFirstSong() {
        DiscCarouselState state = new DiscCarouselState(257);
        state.selectSlot(256);

        assertArrayEquals(new int[]{254, 255, 256, 0, 1}, state.getVisibleSlotIndices());

        state.moveBy(0.75f);
        assertEquals(257f, state.getSnapTarget(), 0.0001f);
        assertEquals(0, state.getSlotIndexAtOffset(1));
        assertEquals(0.25f, state.getRelativePosition(1), 0.0001f);

        state.setPosition(state.getSnapTarget());
        assertEquals(0, state.getSlotIndexAtOffset(0));
    }

    @Test
    public void libraryGrowthKeepsCurrentDiscAndAllowsNewSongs() {
        DiscCarouselState state = new DiscCarouselState(6);
        state.selectSlot(5);
        state.setSlotCount(12);

        assertEquals(5, state.getSlotIndexAtOffset(0));
        state.moveBy(6f);
        assertEquals(11, state.getSlotIndexAtOffset(0));
        assertEquals(0, state.getSlotIndexAtOffset(1));
    }

    @Test
    public void completedGestureEmitsOneSelectionOnlyAfterSnap() {
        DiscCarouselState state = new DiscCarouselState(6);
        assertEquals(-1, state.takeSettledSlot());

        state.beginInteraction();
        state.moveBy(1.6f);
        assertEquals(-1, state.takeSettledSlot());
        state.setPosition(state.getSnapTarget());
        assertEquals(2, state.takeSettledSlot());
        assertEquals(-1, state.takeSettledSlot());
    }

    @Test
    public void interruptedAndReversedGestureOnlyEmitsFinalSlot() {
        DiscCarouselState state = new DiscCarouselState(6);
        state.beginInteraction();
        state.moveBy(3.6f);
        state.cancelInteraction();
        assertEquals(-1, state.takeSettledSlot());

        state.beginInteraction();
        state.moveBy(-0.8f);
        state.setPosition(state.getSnapTarget());
        assertEquals(3, state.takeSettledSlot());
        assertEquals(-1, state.takeSettledSlot());
    }

    @Test
    public void wrappedSnapEmitsFirstSlotOnce() {
        DiscCarouselState state = new DiscCarouselState(6);
        state.selectSlot(5);
        state.beginInteraction();
        state.moveBy(0.8f);
        state.setPosition(state.getSnapTarget());

        assertEquals(0, state.takeSettledSlot());
        assertEquals(-1, state.takeSettledSlot());
    }

    @Test
    public void tappingCenteredDiscCanSelectItOnce() {
        DiscCarouselState state = new DiscCarouselState(6);
        state.beginInteraction();

        assertEquals(0, state.takeSettledSlot());
        assertEquals(-1, state.takeSettledSlot());
    }
}
