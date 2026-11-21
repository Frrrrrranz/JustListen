package com.example.filmplayer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BackgroundBlurTest {
    @Test
    public void darkStripeSoftensAcrossNeighboringPixels() {
        int white = 0xffffffff;
        int black = 0xff000000;
        int[] result = BackgroundBlur.apply(
                new int[]{white, white, black, white, white}, 5, 1, 1);

        assertEquals(white, result[0]);
        assertEquals(0xffaaaaaa, result[1]);
        assertEquals(0xffaaaaaa, result[2]);
        assertEquals(0xffaaaaaa, result[3]);
        assertEquals(white, result[4]);
    }

    @Test
    public void verticalBlurAlsoSoftensAndKeepsSolidColor() {
        int[] vertical = BackgroundBlur.apply(new int[]{
                0xffffffff, 0xffffffff, 0xff000000, 0xffffffff, 0xffffffff
        }, 1, 5, 1);
        assertEquals(0xffaaaaaa, vertical[2]);

        int[] solid = BackgroundBlur.apply(new int[]{
                0xffa98469, 0xffa98469, 0xffa98469, 0xffa98469
        }, 2, 2, 1);
        for (int pixel : solid) {
            assertEquals(0xffa98469, pixel);
        }
    }
}
