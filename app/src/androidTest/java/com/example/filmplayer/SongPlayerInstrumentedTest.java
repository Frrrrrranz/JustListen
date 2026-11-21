package com.example.filmplayer;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

@RunWith(AndroidJUnit4.class)
public class SongPlayerInstrumentedTest {
    @Test
    public void presetResourceStartsOnDevice() throws InterruptedException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertStarts(context, SongEntry.preset(R.raw.preset_spring_breeze,
                "preset_spring_breeze", "春风吹", "方大同"));
    }

    @Test
    public void savedPhoneUriStartsOnDevice() throws InterruptedException {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SongEntry savedSong = null;
        try (SongStore store = new SongStore(context)) {
            for (SongEntry song : store.loadSongs()) {
                if (song.available) {
                    savedSong = song;
                    break;
                }
            }
        }
        assumeNotNull(savedSong);
        assertStarts(context, savedSong);
    }

    private void assertStarts(Context context, SongEntry song) throws InterruptedException {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean started = new AtomicBoolean();
        AtomicBoolean failed = new AtomicBoolean();
        SongPlayer[] player = new SongPlayer[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            player[0] = new SongPlayer(context, new SongPlayer.Listener() {
                @Override
                public void onStarted(SongEntry ignored) {
                    started.set(true);
                    finished.countDown();
                }

                @Override
                public void onFailed(SongEntry ignored) {
                    failed.set(true);
                    finished.countDown();
                }

                @Override
                public void onCompleted(SongEntry ignored) {
                    finished.countDown();
                }
            });
            player[0].play(song);
        });
        boolean callbackReceived = finished.await(15, TimeUnit.SECONDS);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> player[0].release());
        assertTrue("No playback callback", callbackReceived);
        assertFalse("Playback failed", failed.get());
        assertTrue("Playback did not start", started.get());
    }
}
