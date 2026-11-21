package com.example.filmplayer;

import android.net.Uri;

final class SongEntry {
    final String key;
    final String title;
    final String artist;
    final long durationMs;
    final int rawResourceId;
    final Uri uri;
    final boolean available;

    private SongEntry(String key, String title, String artist, long durationMs,
                      int rawResourceId, Uri uri, boolean available) {
        this.key = key;
        this.title = title;
        this.artist = artist;
        this.durationMs = durationMs;
        this.rawResourceId = rawResourceId;
        this.uri = uri;
        this.available = available;
    }

    static SongEntry preset(int rawResourceId, String resourceName, String title, String artist) {
        return new SongEntry("preset:" + resourceName, title, artist, 0L,
                rawResourceId, null, true);
    }

    static SongEntry document(Uri uri, String title, String artist,
                              long durationMs, boolean available) {
        return new SongEntry(uri.toString(), title, artist, durationMs,
                0, uri, available);
    }
}
