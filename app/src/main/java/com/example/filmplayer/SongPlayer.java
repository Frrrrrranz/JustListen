package com.example.filmplayer;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;

import java.io.IOException;

final class SongPlayer {
    interface Listener {
        void onStarted(SongEntry song);
        void onFailed(SongEntry song);
        void onCompleted(SongEntry song);
    }

    private final Context context;
    private final Listener listener;
    private MediaPlayer currentPlayer;
    private AssetFileDescriptor currentSource;
    private String currentKey;
    private boolean prepared;

    SongPlayer(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    boolean isCurrentSong(SongEntry song) {
        return song.key.equals(currentKey);
    }

    boolean isPlaying() {
        if (!prepared || currentPlayer == null) {
            return false;
        }
        try {
            return currentPlayer.isPlaying();
        } catch (RuntimeException error) {
            return false;
        }
    }

    boolean isReady() {
        return prepared && currentPlayer != null;
    }

    long getCurrentPositionMs() {
        if (!isReady()) {
            return 0L;
        }
        try {
            return Math.max(0L, currentPlayer.getCurrentPosition());
        } catch (RuntimeException error) {
            return 0L;
        }
    }

    long getDurationMs() {
        if (!isReady()) {
            return 0L;
        }
        try {
            return Math.max(0L, currentPlayer.getDuration());
        } catch (RuntimeException error) {
            return 0L;
        }
    }

    Boolean togglePlayback() {
        if (!prepared) {
            return null;
        }
        return setPlaying(!isPlaying());
    }

    boolean pauseForScrub() {
        boolean wasPlaying = isPlaying();
        if (wasPlaying) {
            setPlaying(false);
        }
        return wasPlaying;
    }

    boolean resumeAfterScrub(boolean shouldResume) {
        return shouldResume && setPlaying(true);
    }

    boolean setPlaying(boolean shouldPlay) {
        if (!prepared || currentPlayer == null) {
            return false;
        }
        try {
            if (shouldPlay && !currentPlayer.isPlaying()) {
                currentPlayer.start();
            } else if (!shouldPlay && currentPlayer.isPlaying()) {
                currentPlayer.pause();
            }
            return currentPlayer.isPlaying();
        } catch (RuntimeException error) {
            return false;
        }
    }

    void play(SongEntry song) {
        if (song.key.equals(currentKey)) {
            return;
        }
        release();
        if (!song.available) {
            return;
        }

        MediaPlayer player = new MediaPlayer();
        currentPlayer = player;
        currentKey = song.key;
        try {
            currentSource = song.rawResourceId != 0
                    ? context.getResources().openRawResourceFd(song.rawResourceId)
                    : context.getContentResolver().openAssetFileDescriptor(song.uri, "r");
            if (currentSource == null) {
                fail(song, player);
                return;
            }
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            if (currentSource.getLength() == AssetFileDescriptor.UNKNOWN_LENGTH) {
                player.setDataSource(currentSource.getFileDescriptor());
            } else {
                player.setDataSource(currentSource.getFileDescriptor(),
                        currentSource.getStartOffset(), currentSource.getLength());
            }
            player.setOnPreparedListener(prepared -> {
                if (prepared != currentPlayer) {
                    return;
                }
                try {
                    SongPlayer.this.prepared = true;
                    prepared.start();
                    listener.onStarted(song);
                } catch (RuntimeException error) {
                    fail(song, prepared);
                }
            });
            player.setOnCompletionListener(completed -> {
                if (completed == currentPlayer) {
                    release();
                    listener.onCompleted(song);
                }
            });
            player.setOnErrorListener((failed, what, extra) -> {
                fail(song, failed);
                return true;
            });
            player.prepareAsync();
        } catch (IOException | RuntimeException error) {
            fail(song, player);
        }
    }

    void seekBy(long deltaMs) {
        if (!prepared || currentPlayer == null) {
            return;
        }
        try {
            int durationMs = currentPlayer.getDuration();
            if (durationMs <= 0) {
                return;
            }
            long positionMs = (long) currentPlayer.getCurrentPosition() + deltaMs;
            int targetMs = (int) Math.max(0L, Math.min(positionMs, durationMs));
            currentPlayer.seekTo(targetMs, MediaPlayer.SEEK_CLOSEST);
        } catch (RuntimeException ignored) {
            // 媒体状态可能恰好切换；保留当前播放位置。
        }
    }

    private void fail(SongEntry song, MediaPlayer player) {
        if (player != currentPlayer) {
            return;
        }
        release();
        listener.onFailed(song);
    }

    void release() {
        prepared = false;
        if (currentPlayer != null) {
            currentPlayer.setOnPreparedListener(null);
            currentPlayer.setOnCompletionListener(null);
            currentPlayer.setOnErrorListener(null);
            currentPlayer.release();
            currentPlayer = null;
        }
        if (currentSource != null) {
            try {
                currentSource.close();
            } catch (IOException ignored) {
                // 播放器已释放，关闭失败不影响下一首歌。
            }
            currentSource = null;
        }
        currentKey = null;
    }
}
