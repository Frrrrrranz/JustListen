package com.example.filmplayer;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AudioFileTypesTest {
    @Test
    public void folderScanAcceptsCommonMusicContainers() {
        assertTrue(AudioFileTypes.isSupported("song.MP3"));
        assertTrue(AudioFileTypes.isSupported("song.flac"));
        assertTrue(AudioFileTypes.isSupported("song.m4a"));
        assertTrue(AudioFileTypes.isSupported("song.aac"));
        assertTrue(AudioFileTypes.isSupported("song.ogg"));
        assertTrue(AudioFileTypes.isSupported("song.opus"));
        assertTrue(AudioFileTypes.isSupported("song.wav"));
    }

    @Test
    public void folderScanRejectsNonAudioAndMisleadingSuffixes() {
        assertFalse(AudioFileTypes.isSupported("cover.jpg"));
        assertFalse(AudioFileTypes.isSupported("song.mp3.exe"));
        assertFalse(AudioFileTypes.isSupported(""));
    }

    @Test
    public void folderScanAcceptsAudioMimeTypeWithoutKnownExtension() {
        assertTrue(AudioFileTypes.isSupported("recording.wma", "audio/x-ms-wma"));
        assertTrue(AudioFileTypes.isSupported("track", "audio/mp4"));
        assertFalse(AudioFileTypes.isSupported("cover.jpg", "image/jpeg"));
    }
}
