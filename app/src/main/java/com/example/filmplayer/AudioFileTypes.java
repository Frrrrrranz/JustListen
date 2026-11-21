package com.example.filmplayer;

import java.util.Locale;

final class AudioFileTypes {
    private static final String[] EXTENSIONS = {
            ".mp3", ".flac", ".m4a", ".aac", ".ogg", ".opus", ".wav",
            ".amr", ".3gp", ".mid", ".midi"
    };

    private AudioFileTypes() {
    }

    static boolean isSupported(String fileName) {
        return isSupported(fileName, null);
    }

    static boolean isSupported(String fileName, String mimeType) {
        if (mimeType != null && mimeType.toLowerCase(Locale.ROOT).startsWith("audio/")) {
            return true;
        }
        if (fileName == null) {
            return false;
        }
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        for (String extension : EXTENSIONS) {
            if (lowerName.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }
}
