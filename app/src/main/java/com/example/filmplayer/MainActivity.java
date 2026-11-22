package com.example.filmplayer;

import android.app.Activity;
import android.app.Dialog;
import android.Manifest;
import android.content.ContentUris;
import android.content.ContentResolver;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int MAX_DOCUMENTS = 10000;
    private static final long TEMPORARY_STATUS_DISPLAY_MS = 4000L;
    private static final String LAST_PLAYED_SONG_KEY = "last_played_song_key";
    private static final String HIDE_INTRO_KEY = "hide_intro";
    private static final String DEVICE_SCAN_SOURCE = "mediastore:external";
    private static final String[] DOCUMENT_COLUMNS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
    };
    private static final String[] MEDIA_COLUMNS = {
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION
    };

    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private ActivityResultLauncher<Intent> folderPicker;
    private ActivityResultLauncher<String> audioPermissionRequest;
    private DiscCarouselView carouselView;
    private TextView scanStatus;
    private View emptyLibraryOverlay;
    private TextView selectedSongTitle;
    private SongStore songStore;
    private PlaybackService playbackService;
    private boolean serviceBound;
    private List<SongEntry> presetSongs;
    private List<SongEntry> displayedSongs;
    private boolean resumeAfterScrub;
    private boolean activityResumed;
    private boolean introShownThisVisit;
    private boolean skipIntroOnReturn;
    private Dialog introDialog;
    private final Runnable hideScanStatus = () -> scanStatus.setVisibility(View.GONE);
    private final ServiceConnection playbackConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            playbackService = ((PlaybackService.LocalBinder) binder).getService();
            playbackService.setListener(playbackListener);
            syncPlaybackFromService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            playbackService = null;
        }
    };
    private final PlaybackService.Listener playbackListener = new PlaybackService.Listener() {
        @Override
        public void onPlaybackChanged(SongEntry song, boolean playing) {
            syncPlaybackFromService();
        }

        @Override
        public void onPlaybackFailed(SongEntry song) {
            resumeAfterScrub = false;
            carouselView.exitPlayback();
            selectedSongTitle.setVisibility(View.GONE);
            showStatus(getString(R.string.playback_failed));
            if (song.uri != null && displayedSongs != null) {
                markSongUnavailable(song);
            }
        }

        @Override
        public void onPlaybackCompleted(SongEntry song) {
            resumeAfterScrub = false;
            carouselView.exitPlayback();
            selectedSongTitle.setVisibility(View.GONE);
            showTemporaryStatus(getString(R.string.playback_finished));
        }

        @Override
        public void onPlaybackStopped() {
            resumeAfterScrub = false;
            carouselView.exitPlayback();
            selectedSongTitle.setVisibility(View.GONE);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        carouselView = findViewById(R.id.discCarouselView);
        scanStatus = findViewById(R.id.scanStatus);
        emptyLibraryOverlay = findViewById(R.id.emptyLibraryOverlay);
        selectedSongTitle = findViewById(R.id.selectedSongTitle);
        songStore = new SongStore(getApplicationContext());
        presetSongs = createPresetSongs();
        carouselView.setOnSongSettledListener(song -> {
            if (!song.available || !carouselView.enterPlayback(song)) {
                return;
            }
            selectedSongTitle.setVisibility(View.GONE);
            try {
                ContextCompat.startForegroundService(this,
                        PlaybackService.playIntent(this, song));
            } catch (RuntimeException error) {
                carouselView.exitPlayback();
                showStatus(getString(R.string.playback_failed));
            }
        });
        carouselView.setOnPlaybackGestureListener(new DiscCarouselView.OnPlaybackGestureListener() {
            @Override
            public void onExitRequested() {
                resumeAfterScrub = false;
                carouselView.exitPlayback();
                if (playbackService != null) {
                    playbackService.stopPlayback();
                } else {
                    stopService(new Intent(MainActivity.this, PlaybackService.class));
                }
                selectedSongTitle.setVisibility(View.GONE);
            }

            @Override
            public void onPlaybackToggleRequested() {
                Boolean playing = playbackService == null
                        ? null : playbackService.togglePlayback();
                if (playing != null) {
                    carouselView.setPlaybackRotating(playing);
                }
            }

            @Override
            public boolean onScrubStarted() {
                if (playbackService == null || !playbackService.isReady()) {
                    return false;
                }
                long durationMs = playbackService.getDurationMs();
                if (durationMs <= 0L) {
                    return false;
                }
                resumeAfterScrub = playbackService.pauseForScrub();
                carouselView.setScrubRange(playbackService.getCurrentPositionMs(), durationMs);
                return true;
            }

            @Override
            public void onScrubFinished(long deltaMs) {
                if (playbackService == null) {
                    return;
                }
                playbackService.seekBy(deltaMs);
                boolean playing = playbackService.resumeAfterScrub(resumeAfterScrub);
                resumeAfterScrub = false;
                carouselView.setPlaybackRotating(playing);
            }
        });

        folderPicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> handleFolderResult(result.getResultCode(), result.getData()));
        audioPermissionRequest = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        scanDeviceMusic();
                    } else {
                        showStatus(getString(R.string.audio_permission_denied));
                    }
                });
        findViewById(R.id.addDiscButton).setOnClickListener(view -> showAddMusicDialog());
        findViewById(R.id.emptyLibraryAction).setOnClickListener(view -> showAddMusicDialog());

        scanExecutor.execute(() -> {
            try {
                List<SongEntry> savedSongs = songStore.loadSongs();
                runOnUiThread(() -> showInitialSongs(savedSongs));
            } catch (RuntimeException error) {
                runOnUiThread(() -> {
                    showInitialSongs(new ArrayList<>());
                    showStatus(getString(R.string.library_load_failed));
                });
            }
        });

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(systemBars.left, systemBars.top,
                    systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    private List<SongEntry> createPresetSongs() {
        List<SongEntry> songs = new ArrayList<>();
        addPresetIfPresent(songs, "preset_spring_breeze", "春风吹", "方大同");
        addPresetIfPresent(songs, "preset_ten_years", "十年", "陈奕迅");
        addPresetIfPresent(songs, "preset_fish", "快鱼仔", "卢广仲");
        addPresetIfPresent(songs, "preset_growing_old", "我忘了我已老去", "林志炫");
        addPresetIfPresent(songs, "preset_wanderer", "浪人的…", "张震岳");
        addPresetIfPresent(songs, "preset_color_your_night", "Color Your Night", "Lotus Juice");
        return songs;
    }

    private void addPresetIfPresent(List<SongEntry> songs, String resourceName,
            String title, String artist) {
        // 预置音频仅保存在本地，公开源码缺少素材时仍需能正常构建。
        int rawResourceId = getResources().getIdentifier(resourceName, "raw", getPackageName());
        if (rawResourceId != 0) {
            songs.add(SongEntry.preset(rawResourceId, resourceName, title, artist));
        }
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        skipIntroOnReturn = true;
        folderPicker.launch(intent);
    }

    private void showAddMusicDialog() {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_add_music);
        dialog.findViewById(R.id.scanDeviceOption).setOnClickListener(view -> {
            dialog.dismiss();
            requestDeviceScan();
        });
        dialog.findViewById(R.id.scanFolderOption).setOnClickListener(view -> {
            dialog.dismiss();
            openFolderPicker();
        });
        showCenteredDialog(dialog, 360);
    }

    private void showIntroIfNeeded() {
        if (!activityResumed || introShownThisVisit || displayedSongs == null
                || getPreferences(MODE_PRIVATE).getBoolean(HIDE_INTRO_KEY, false)) {
            return;
        }
        if (introDialog != null && introDialog.isShowing()) {
            introShownThisVisit = true;
            return;
        }
        introShownThisVisit = true;
        Dialog dialog = new Dialog(this);
        introDialog = dialog;
        dialog.setContentView(R.layout.dialog_intro);
        CheckBox hideNextTime = dialog.findViewById(R.id.hideIntroCheckBox);
        dialog.findViewById(R.id.introDoneButton).setOnClickListener(view -> dialog.dismiss());
        dialog.setOnDismissListener(ignored -> {
            introDialog = null;
            getPreferences(MODE_PRIVATE).edit()
                    .putBoolean(HIDE_INTRO_KEY, hideNextTime.isChecked()).apply();
        });
        showCenteredDialog(dialog, 390);
    }

    private void showCenteredDialog(Dialog dialog, int maxWidthDp) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.show();
        if (window != null) {
            float density = getResources().getDisplayMetrics().density;
            int horizontalMargin = Math.round(48 * density);
            int maxWidth = Math.round(maxWidthDp * density);
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels
                    - horizontalMargin, maxWidth), WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private void requestDeviceScan() {
        String permission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ContextCompat.checkSelfPermission(this, permission)
                == PackageManager.PERMISSION_GRANTED) {
            scanDeviceMusic();
        } else {
            skipIntroOnReturn = true;
            audioPermissionRequest.launch(permission);
        }
    }

    private void scanDeviceMusic() {
        showStatus(getString(R.string.device_scan_started));
        scanExecutor.execute(() -> {
            try {
                List<SongEntry> scannedSongs = readDeviceMusic();
                songStore.saveScan(DEVICE_SCAN_SOURCE, scannedSongs);
                List<SongEntry> allImportedSongs = songStore.loadSongs();
                runOnUiThread(() -> {
                    showSongs(allImportedSongs);
                    showTemporaryStatus(getString(R.string.scan_complete, scannedSongs.size()));
                });
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> showStatus(getString(R.string.device_scan_failed)));
            }
        });
    }

    private List<SongEntry> readDeviceMusic() throws IOException {
        List<SongEntry> songs = new ArrayList<>();
        Uri collection = Build.VERSION.SDK_INT >= 29
                ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        try (Cursor cursor = getContentResolver().query(collection, MEDIA_COLUMNS,
                MediaStore.Audio.Media.IS_MUSIC + " != 0", null,
                MediaStore.Audio.Media._ID + " ASC")) {
            if (cursor == null) {
                throw new IOException("Cannot read device music");
            }
            while (cursor.moveToNext()) {
                if (songs.size() >= MAX_DOCUMENTS) {
                    throw new IOException("Too many songs on device");
                }
                long durationMs = cursor.getLong(4);
                if (durationMs <= 0L) {
                    continue;
                }
                String displayName = cursor.getString(1);
                String title = cursor.getString(2);
                if (title == null || title.trim().isEmpty()) {
                    title = displayName == null ? "" : displayName;
                    int extensionStart = title.lastIndexOf('.');
                    if (extensionStart > 0) {
                        title = title.substring(0, extensionStart);
                    }
                }
                if (title.trim().isEmpty()) {
                    continue;
                }
                String artist = cursor.getString(3);
                Uri songUri = ContentUris.withAppendedId(collection, cursor.getLong(0));
                songs.add(SongEntry.document(songUri, title.trim(),
                        artist == null ? "" : artist.trim(), durationMs, true));
            }
        }
        return songs;
    }

    private void handleFolderResult(int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri folderUri = data.getData();
        if (!ContentResolver.SCHEME_CONTENT.equals(folderUri.getScheme())) {
            showStatus(getString(R.string.folder_unavailable));
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(
                    folderUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException error) {
            showStatus(getString(R.string.folder_unavailable));
            return;
        }
        showStatus(getString(R.string.scan_started));
        scanExecutor.execute(() -> {
            try {
                List<SongEntry> scannedSongs = scanFolder(folderUri);
                songStore.saveScan(folderUri.toString(), scannedSongs);
                List<SongEntry> allImportedSongs = songStore.loadSongs();
                runOnUiThread(() -> {
                    showSongs(allImportedSongs);
                    showTemporaryStatus(getString(R.string.scan_complete, scannedSongs.size()));
                });
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> showStatus(getString(R.string.scan_failed)));
            }
        });
    }

    private List<SongEntry> scanFolder(Uri folderUri) throws IOException {
        List<SongEntry> foundSongs = new ArrayList<>();
        ArrayDeque<String> directories = new ArrayDeque<>();
        Set<String> visitedDirectories = new HashSet<>();
        directories.add(DocumentsContract.getTreeDocumentId(folderUri));
        int documentCount = 0;
        while (!directories.isEmpty()) {
            String directoryId = directories.removeFirst();
            if (!visitedDirectories.add(directoryId)) {
                continue;
            }
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    folderUri, directoryId);
            try (Cursor cursor = getContentResolver().query(
                    childrenUri, DOCUMENT_COLUMNS, null, null, null)) {
                if (cursor == null) {
                    throw new IOException("Cannot read selected folder");
                }
                while (cursor.moveToNext()) {
                    if (++documentCount > MAX_DOCUMENTS) {
                        throw new IOException("Too many documents in selected folder");
                    }
                    String documentId = cursor.getString(0);
                    String fileName = cursor.getString(1);
                    String mimeType = cursor.getString(2);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                        directories.add(documentId);
                        continue;
                    }
                    if (fileName == null || !AudioFileTypes.isSupported(fileName, mimeType)) {
                        continue;
                    }
                    Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(
                            folderUri, documentId);
                    SongEntry song = readSong(fileUri, fileName);
                    if (song != null) {
                        foundSongs.add(song);
                        if (foundSongs.size() % 50 == 0) {
                            int count = foundSongs.size();
                            runOnUiThread(() -> showStatus(getString(R.string.scan_progress, count)));
                        }
                    }
                }
            }
        }
        return foundSongs;
    }

    private SongEntry readSong(Uri uri, String fileName) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            String durationValue = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationValue == null) {
                return null;
            }
            long durationMs = Long.parseLong(durationValue);
            if (durationMs <= 0) {
                return null;
            }
            String title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            if (title == null || title.trim().isEmpty()) {
                int extensionStart = fileName.lastIndexOf('.');
                title = extensionStart > 0 ? fileName.substring(0, extensionStart) : fileName;
            }
            String artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            return SongEntry.document(uri, title.trim(), artist == null ? "" : artist.trim(),
                    durationMs, true);
        } catch (RuntimeException error) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (IOException ignored) {
                //这首歌的标签读取结果仍可保留。
            }
        }
    }

    private void showSongs(List<SongEntry> importedSongs) {
        if (isDestroyed()) {
            return;
        }
        List<SongEntry> allSongs = new ArrayList<>(presetSongs);
        allSongs.addAll(importedSongs);
        displayedSongs = allSongs;
        carouselView.setSongs(allSongs);
        boolean isEmpty = allSongs.isEmpty();
        carouselView.setVisibility(isEmpty ? View.INVISIBLE : View.VISIBLE);
        emptyLibraryOverlay.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
    }

    private void showInitialSongs(List<SongEntry> importedSongs) {
        if (isDestroyed()) {
            return;
        }
        showSongs(importedSongs);
        String lastPlayedKey = getPreferences(MODE_PRIVATE)
                .getString(LAST_PLAYED_SONG_KEY, null);
        int selectedIndex = SongSelection.findIndexByKey(displayedSongs, lastPlayedKey);
        if (selectedIndex >= 0) {
            carouselView.setSelectedSlot(selectedIndex);
        }
        syncPlaybackFromService();
        showIntroIfNeeded();
    }

    private void syncPlaybackFromService() {
        if (playbackService == null || displayedSongs == null) {
            return;
        }
        SongEntry song = playbackService.getCurrentSong();
        if (song == null) {
            return;
        }
        int index = SongSelection.findIndexByKey(displayedSongs, song.key);
        if (index < 0) {
            return;
        }
        carouselView.setSelectedSlot(index);
        carouselView.enterPlayback(displayedSongs.get(index));
        if (playbackService.isReady()) {
            selectedSongTitle.setText(song.title);
            selectedSongTitle.setVisibility(View.VISIBLE);
        }
        carouselView.setPlaybackRotating(playbackService.isPlaying());
    }

    private void markSongUnavailable(SongEntry failedSong) {
        List<SongEntry> updatedSongs = new ArrayList<>(displayedSongs);
        for (int index = 0; index < updatedSongs.size(); index++) {
            SongEntry song = updatedSongs.get(index);
            if (song.key.equals(failedSong.key)) {
                updatedSongs.set(index, SongEntry.document(song.uri, song.title,
                        song.artist, song.durationMs, false));
                break;
            }
        }
        displayedSongs = updatedSongs;
        carouselView.setSongs(updatedSongs);
    }

    private void showStatus(String message) {
        if (isDestroyed()) {
            return;
        }
        scanStatus.removeCallbacks(hideScanStatus);
        scanStatus.setText(message);
        scanStatus.setVisibility(View.VISIBLE);
    }

    private void showTemporaryStatus(String message) {
        if (isDestroyed()) {
            return;
        }
        showStatus(message);
        scanStatus.postDelayed(hideScanStatus, TEMPORARY_STATUS_DISPLAY_MS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        if (skipIntroOnReturn) {
            skipIntroOnReturn = false;
            introShownThisVisit = true;
        } else {
            showIntroIfNeeded();
        }
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        serviceBound = bindService(new Intent(this, PlaybackService.class),
                playbackConnection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        introShownThisVisit = false;
        // 先结束可能正在进行的调进度手势，让服务恢复长按前的播放状态。
        carouselView.exitPlayback();
        if (serviceBound) {
            if (playbackService != null) {
                playbackService.setListener(null);
            }
            unbindService(playbackConnection);
            serviceBound = false;
            playbackService = null;
        }
        resumeAfterScrub = false;
        selectedSongTitle.setVisibility(View.GONE);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (introDialog != null) {
            introDialog.dismiss();
        }
        scanStatus.removeCallbacks(hideScanStatus);
        scanExecutor.shutdownNow();
        songStore.close();
        super.onDestroy();
    }
}
