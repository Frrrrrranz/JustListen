package com.example.filmplayer;

import java.util.List;

final class SongSelection {
    private SongSelection() {
    }

    static int findIndexByKey(List<SongEntry> songs, String key) {
        if (key == null) {
            return -1;
        }
        for (int index = 0; index < songs.size(); index++) {
            if (key.equals(songs.get(index).key)) {
                return index;
            }
        }
        return -1;
    }
}
