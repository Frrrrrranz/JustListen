package com.example.filmplayer;

final class PlaybackGesture {
    private static final float EXIT_DISTANCE_DP = 72f;
    private static final long SEEK_MS_PER_DP = 100L;
    private static final float ROTATION_DEGREES_PER_DP = 1.2f;

    static boolean isExitSwipe(float deltaX, float deltaY, float density) {
        return deltaX / density <= -EXIT_DISTANCE_DP
                && Math.abs(deltaX) > Math.abs(deltaY);
    }

    static long scrubDeltaMs(float deltaY, float density, boolean startedOnLeft) {
        float direction = startedOnLeft ? -1f : 1f;
        return Math.round(direction * deltaY / density * SEEK_MS_PER_DP);
    }

    static float scrubRotationDegrees(float deltaY, float density, boolean startedOnLeft) {
        float direction = startedOnLeft ? -1f : 1f;
        return direction * deltaY / density * ROTATION_DEGREES_PER_DP;
    }

    static long scrubPositionMs(long startMs, long durationMs, long deltaMs) {
        return Math.max(0L, Math.min(startMs + deltaMs, durationMs));
    }
}
