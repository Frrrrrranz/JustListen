package com.example.filmplayer;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import androidx.core.content.ContextCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class PlaybackServiceInstrumentedTest {
    @Test
    public void presetKeepsPlayingAfterPageGoesToBackground() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        TestConnection firstConnection = new TestConnection();
        TestConnection secondConnection = new TestConnection();
        PlaybackService firstService = null;
        PlaybackService secondService = null;
        try {
            SongEntry song = SongEntry.preset(R.raw.preset_spring_breeze,
                    "preset_spring_breeze", "春风吹", "方大同");
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    ContextCompat.startForegroundService(context,
                            PlaybackService.playIntent(context, song)));
            firstService = bind(context, firstConnection);
            waitForPlayback(firstService);
            assertTrue(hasPlaybackNotification(context));

            context.unbindService(firstConnection);
            firstConnection.bound = false;
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> activity.moveTaskToBack(true));
            Thread.sleep(1000L);

            secondService = bind(context, secondConnection);
            assertSame(firstService, secondService);
            waitForPlayback(secondService);
        } finally {
            PlaybackService serviceToStop = secondService != null ? secondService : firstService;
            if (serviceToStop != null) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(
                        serviceToStop::stopPlayback);
            } else {
                context.stopService(new Intent(context, PlaybackService.class));
            }
            if (secondConnection.bound) {
                context.unbindService(secondConnection);
            }
            if (firstConnection.bound) {
                context.unbindService(firstConnection);
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);
        }
    }

    private PlaybackService bind(Context context, TestConnection connection)
            throws InterruptedException {
        connection.bound = context.bindService(new Intent(context, PlaybackService.class),
                connection, Context.BIND_AUTO_CREATE);
        assertTrue(connection.bound);
        assertTrue("Service did not bind", connection.connected.await(5, TimeUnit.SECONDS));
        return connection.service;
    }

    private void waitForPlayback(PlaybackService service) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15000L;
        AtomicBoolean playing = new AtomicBoolean();
        while (System.currentTimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> playing.set(service.isPlaying()));
            if (playing.get()) {
                return;
            }
            Thread.sleep(100L);
        }
        assertTrue("Preset did not start playing", playing.get());
    }

    private boolean hasPlaybackNotification(Context context) {
        for (android.service.notification.StatusBarNotification notification :
                context.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (notification.getId() == 1) {
                return true;
            }
        }
        return false;
    }

    private static final class TestConnection implements ServiceConnection {
        final CountDownLatch connected = new CountDownLatch(1);
        PlaybackService service;
        boolean bound;

        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlaybackService.LocalBinder) binder).getService();
            connected.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    }
}
