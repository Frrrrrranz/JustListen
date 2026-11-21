package com.example.filmplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlaybackService extends Service {
    private static final String CHANNEL_ID = "music_playback";
    private static final int NOTIFICATION_ID = 1;
    private static final int NOTIFICATION_ARTWORK_SIZE_PX = 256;
    private static final String ACTION_PLAY = "com.example.filmplayer.PLAY";
    private static final String ACTION_TOGGLE = "com.example.filmplayer.TOGGLE";
    private static final String ACTION_STOP = "com.example.filmplayer.STOP";
    private static final String EXTRA_KEY = "song_key";
    private static final String EXTRA_TITLE = "song_title";
    private static final String EXTRA_ARTIST = "song_artist";
    private static final String EXTRA_DURATION = "song_duration";
    private static final String EXTRA_RAW_ID = "song_raw_id";
    private static final String EXTRA_URI = "song_uri";
    private static final String LAST_PLAYED_SONG_KEY = "last_played_song_key";

    interface Listener {
        void onPlaybackChanged(SongEntry song, boolean playing);
        void onPlaybackFailed(SongEntry song);
        void onPlaybackCompleted(SongEntry song);
        void onPlaybackStopped();
    }

    final class LocalBinder extends Binder {
        PlaybackService getService() {
            return PlaybackService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final ExecutorService artworkExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SongPlayer player;
    private MediaSession mediaSession;
    private SongEntry currentSong;
    private Listener listener;
    private Bitmap currentArtwork;
    private boolean foregroundStarted;
    private boolean preparing;
    private boolean destroyed;

    static Intent playIntent(Context context, SongEntry song) {
        Intent intent = new Intent(context, PlaybackService.class).setAction(ACTION_PLAY);
        intent.putExtra(EXTRA_KEY, song.key);
        intent.putExtra(EXTRA_TITLE, song.title);
        intent.putExtra(EXTRA_ARTIST, song.artist);
        intent.putExtra(EXTRA_DURATION, song.durationMs);
        intent.putExtra(EXTRA_RAW_ID, song.rawResourceId);
        if (song.uri != null) {
            intent.putExtra(EXTRA_URI, song.uri.toString());
        }
        return intent;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.playback_channel), NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        mediaSession = new MediaSession(this, "听歌");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                setPlaying(true);
            }

            @Override
            public void onPause() {
                setPlaying(false);
            }

            @Override
            public void onStop() {
                stopPlayback();
            }

            @Override
            public void onSeekTo(long positionMs) {
                if (player.isReady()) {
                    seekBy(positionMs - player.getCurrentPositionMs());
                }
            }
        });
        mediaSession.setActive(true);
        player = new SongPlayer(this, new SongPlayer.Listener() {
            @Override
            public void onStarted(SongEntry song) {
                preparing = false;
                getSharedPreferences("MainActivity", MODE_PRIVATE).edit()
                        .putString(LAST_PLAYED_SONG_KEY, song.key).apply();
                updatePlaybackState();
            }

            @Override
            public void onFailed(SongEntry song) {
                if (song.uri != null) {
                    try (SongStore store = new SongStore(getApplicationContext())) {
                        store.markUnavailable(song.key);
                    } catch (RuntimeException ignored) {
                        // 播放失败仍需结束服务；下次扫描可重新更新歌曲状态。
                    }
                }
                currentSong = null;
                currentArtwork = null;
                preparing = false;
                finishForeground();
                if (listener != null) {
                    listener.onPlaybackFailed(song);
                }
                stopSelf();
            }

            @Override
            public void onCompleted(SongEntry song) {
                currentSong = null;
                currentArtwork = null;
                preparing = false;
                finishForeground();
                if (listener != null) {
                    listener.onPlaybackCompleted(song);
                }
                stopSelf();
            }
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        listener = null;
        return false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_PLAY.equals(action)) {
            SongEntry song = songFromIntent(intent);
            if (song == null) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            if (currentSong != null && player.isCurrentSong(song)) {
                return START_NOT_STICKY;
            }
            currentSong = song;
            currentArtwork = null;
            preparing = true;
            updateSession(PlaybackState.STATE_BUFFERING);
            if (!foregroundStarted) {
                startForeground(NOTIFICATION_ID, buildNotification());
                foregroundStarted = true;
            } else {
                updateNotification();
            }
            loadArtwork(song);
            player.play(song);
        } else if (ACTION_TOGGLE.equals(action)) {
            togglePlayback();
        } else if (ACTION_STOP.equals(action)) {
            stopPlayback();
        }
        return START_NOT_STICKY;
    }

    private SongEntry songFromIntent(Intent intent) {
        String key = intent.getStringExtra(EXTRA_KEY);
        String title = intent.getStringExtra(EXTRA_TITLE);
        if (key == null || title == null) {
            return null;
        }
        String artist = intent.getStringExtra(EXTRA_ARTIST);
        int rawId = intent.getIntExtra(EXTRA_RAW_ID, 0);
        if (rawId != 0) {
            return SongEntry.preset(rawId, key.substring(key.indexOf(':') + 1), title,
                    artist == null ? "" : artist);
        }
        String uriString = intent.getStringExtra(EXTRA_URI);
        if (uriString == null) {
            return null;
        }
        Uri uri = Uri.parse(uriString);
        if (!"content".equals(uri.getScheme())) {
            return null;
        }
        return SongEntry.document(uri, title, artist == null ? "" : artist,
                intent.getLongExtra(EXTRA_DURATION, 0L), true);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    SongEntry getCurrentSong() {
        return currentSong;
    }

    boolean isPlaying() {
        return player.isPlaying();
    }

    boolean isReady() {
        return player.isReady();
    }

    long getCurrentPositionMs() {
        return player.getCurrentPositionMs();
    }

    long getDurationMs() {
        return player.getDurationMs();
    }

    Boolean togglePlayback() {
        if (!player.isReady()) {
            return null;
        }
        boolean playing = player.togglePlayback();
        updatePlaybackState();
        return playing;
    }

    boolean pauseForScrub() {
        boolean wasPlaying = player.pauseForScrub();
        updatePlaybackState();
        return wasPlaying;
    }

    boolean resumeAfterScrub(boolean shouldResume) {
        boolean playing = player.resumeAfterScrub(shouldResume);
        updatePlaybackState();
        return playing;
    }

    void seekBy(long deltaMs) {
        player.seekBy(deltaMs);
        updatePlaybackState();
    }

    private void setPlaying(boolean shouldPlay) {
        if (player.isReady()) {
            player.setPlaying(shouldPlay);
            updatePlaybackState();
        }
    }

    void stopPlayback() {
        player.release();
        currentSong = null;
        currentArtwork = null;
        preparing = false;
        finishForeground();
        if (listener != null) {
            listener.onPlaybackStopped();
        }
        stopSelf();
    }

    private void updatePlaybackState() {
        updateSession(player.isPlaying() ? PlaybackState.STATE_PLAYING
                : PlaybackState.STATE_PAUSED);
        updateNotification();
        if (listener != null && currentSong != null) {
            listener.onPlaybackChanged(currentSong, player.isPlaying());
        }
    }

    private void updateSession(int state) {
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_SEEK_TO;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, player == null ? 0L : player.getCurrentPositionMs(),
                        state == PlaybackState.STATE_PLAYING ? 1f : 0f)
                .build());
        MediaMetadata.Builder metadata = new MediaMetadata.Builder();
        if (currentSong != null) {
            metadata.putString(MediaMetadata.METADATA_KEY_TITLE, currentSong.title);
            metadata.putString(MediaMetadata.METADATA_KEY_ARTIST, currentSong.artist);
            metadata.putLong(MediaMetadata.METADATA_KEY_DURATION,
                    player != null && player.isReady()
                            ? player.getDurationMs() : currentSong.durationMs);
            if (currentArtwork != null) {
                metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, currentArtwork);
                metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, currentArtwork);
            }
        }
        mediaSession.setMetadata(metadata.build());
    }

    private void loadArtwork(SongEntry song) {
        artworkExecutor.execute(() -> {
            Bitmap artwork = AlbumArtwork.load(this, song, NOTIFICATION_ARTWORK_SIZE_PX);
            if (artwork == null) {
                return;
            }
            mainHandler.post(() -> {
                if (destroyed || currentSong == null || !currentSong.key.equals(song.key)) {
                    return;
                }
                currentArtwork = artwork;
                updateSession(preparing ? PlaybackState.STATE_BUFFERING
                        : player.isPlaying() ? PlaybackState.STATE_PLAYING
                        : PlaybackState.STATE_PAUSED);
                updateNotification();
            });
        });
    }

    private Notification buildNotification() {
        boolean playing = player != null && player.isPlaying();
        PendingIntent openApp = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent toggle = PendingIntent.getService(this, 1,
                new Intent(this, PlaybackService.class).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, PlaybackService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_disc)
                .setContentTitle(currentSong == null ? getString(R.string.app_name) : currentSong.title)
                .setContentText(preparing ? getString(R.string.playback_loading)
                        : currentSong == null || currentSong.artist.isEmpty()
                        ? getString(R.string.app_name) : currentSong.artist)
                .setContentIntent(openApp)
                .setLargeIcon(currentArtwork)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(playing ? android.R.drawable.ic_media_pause
                                : android.R.drawable.ic_media_play,
                        getString(playing ? R.string.playback_pause : R.string.playback_resume),
                        toggle)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        getString(R.string.playback_stop), stop)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1))
                .build();
    }

    private void updateNotification() {
        if (foregroundStarted) {
            getSystemService(NotificationManager.class).notify(
                    NOTIFICATION_ID, buildNotification());
        }
    }

    private void finishForeground() {
        updateSession(PlaybackState.STATE_STOPPED);
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foregroundStarted = false;
        }
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        artworkExecutor.shutdownNow();
        player.release();
        mediaSession.setActive(false);
        mediaSession.release();
        super.onDestroy();
    }
}
