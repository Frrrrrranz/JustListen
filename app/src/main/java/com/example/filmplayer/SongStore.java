package com.example.filmplayer;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

final class SongStore extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "songs.db";
    private static final int DATABASE_VERSION = 1;

    SongStore(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE songs (uri TEXT PRIMARY KEY, folder_uri TEXT NOT NULL, "
                + "title TEXT NOT NULL, artist TEXT NOT NULL, duration_ms INTEGER NOT NULL, "
                + "available INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        //首版没有旧数据需要迁移。
    }

    List<SongEntry> loadSongs() {
        List<SongEntry> songs = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("songs",
                new String[]{"uri", "title", "artist", "duration_ms", "available"},
                null, null, null, null, "rowid")) {
            while (cursor.moveToNext()) {
                Uri uri = Uri.parse(cursor.getString(0));
                songs.add(SongEntry.document(uri, cursor.getString(1), cursor.getString(2),
                        cursor.getLong(3), cursor.getInt(4) != 0));
            }
        }
        return songs;
    }

    void saveScan(String folderUri, List<SongEntry> scannedSongs) {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            //完整扫描后才标记缺失；同一目录重扫不会重复插入唱片。
            ContentValues unavailable = new ContentValues();
            unavailable.put("available", 0);
            database.update("songs", unavailable, "folder_uri = ?", new String[]{folderUri});
            for (SongEntry song : scannedSongs) {
                ContentValues values = new ContentValues();
                values.put("folder_uri", folderUri);
                values.put("title", song.title);
                values.put("artist", song.artist);
                values.put("duration_ms", song.durationMs);
                values.put("available", 1);
                if (database.update("songs", values, "uri = ?", new String[]{song.key}) == 0) {
                    values.put("uri", song.key);
                    database.insertOrThrow("songs", null, values);
                }
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    void markUnavailable(String songUri) {
        ContentValues values = new ContentValues();
        values.put("available", 0);
        getWritableDatabase().update("songs", values, "uri = ?", new String[]{songUri});
    }
}
