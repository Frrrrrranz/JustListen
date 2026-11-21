package com.example.filmplayer;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;

import java.io.IOException;

final class AlbumArtwork {
    private AlbumArtwork() {
    }

    static Bitmap load(Context context, SongEntry song, int maxSizePx) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if (song.rawResourceId != 0) {
                try (AssetFileDescriptor source = context.getResources()
                        .openRawResourceFd(song.rawResourceId)) {
                    if (source == null) {
                        return null;
                    }
                    retriever.setDataSource(source.getFileDescriptor(),
                            source.getStartOffset(), source.getLength());
                }
            } else {
                retriever.setDataSource(context, song.uri);
            }
            byte[] image = retriever.getEmbeddedPicture();
            if (image == null) {
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(image, 0, image.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                return null;
            }
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize
                    > maxSizePx) {
                options.inSampleSize *= 2;
            }
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(image, 0, image.length, options);
        } catch (IOException | RuntimeException error) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (IOException ignored) {
                // 封面读取已经结束，释放失败不影响占位样式。
            }
        }
    }
}
