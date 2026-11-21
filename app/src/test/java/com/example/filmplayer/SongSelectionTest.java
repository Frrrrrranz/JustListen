package com.example.filmplayer;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class SongSelectionTest {
    @Test
    public void restoresLastPlayedSongAfterLibraryOrderChanges() {
        SongEntry first = SongEntry.preset(1, "first", "第一首", "");
        SongEntry lastPlayed = SongEntry.preset(2, "last_played", "上一首", "");
        SongEntry newSong = SongEntry.preset(3, "new_song", "新增歌曲", "");
        List<SongEntry> reorderedSongs = Arrays.asList(newSong, first, lastPlayed);

        assertEquals(2, SongSelection.findIndexByKey(reorderedSongs, "preset:last_played"));
        assertEquals(-1, SongSelection.findIndexByKey(reorderedSongs, "preset:missing"));
    }

    @Test
    public void presetKeyDoesNotDependOnCompiledResourceNumber() {
        SongEntry previousBuild = SongEntry.preset(1, "same_song", "同一首", "");
        SongEntry currentBuild = SongEntry.preset(99, "same_song", "同一首", "");

        assertEquals(previousBuild.key, currentBuild.key);
    }
}
