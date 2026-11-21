package com.example.filmplayer;

import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.HapticFeedbackConstants;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DiscCarouselView extends View {
    private static final float[] X_POSITIONS = {0.02f, 0.02f, 0.14f, 0.35f, 0.14f, 0.02f, 0.02f};
    private static final float[] Y_POSITIONS = {-0.10f, 0.11f, 0.31f, 0.53f, 0.75f, 0.94f, 1.15f};
    private static final float[] SIZES = {0.10f, 0.14f, 0.22f, 0.32f, 0.22f, 0.14f, 0.10f};
    private static final float[] OPACITIES = {0, 130, 210, 255, 210, 130, 0};
    private static final float DRAG_STEP_FRACTION = 0.22f;
    private static final float EDGE_FADE_FRACTION = 0.12f;
    private static final long SNAP_DURATION_MS = 180L;
    private static final int COVER_SIZE_PX = 256;
    private static final int COVER_CACHE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PENDING_COVERS = 12;
    private static final long PLAYBACK_TRANSITION_MS = 360L;
    private static final long DISC_ROTATION_MS = 12000L;
    private static final int BACKGROUND_DOWNSAMPLE = 4;
    private static final int BACKGROUND_BLUR_RADIUS = 5;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgeMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backgroundPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final DiscCarouselState carouselState = new DiscCarouselState(1);
    private final int[] slotColors;
    private final LruCache<String, Bitmap> coverCache = new LruCache<String, Bitmap>(COVER_CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };
    private final Set<String> pendingCovers = new HashSet<>();
    private final Set<String> missingCovers = new HashSet<>();
    private final OverScroller flingScroller;
    private final int minFlingVelocity;
    private final int maxFlingVelocity;
    private final int playbackTouchSlop;
    private VelocityTracker velocityTracker;
    private ValueAnimator snapAnimator;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private int lastFlingY;
    private float lastTouchY;
    private boolean flinging;
    private ExecutorService coverExecutor = Executors.newSingleThreadExecutor();
    private List<SongEntry> songs = Collections.emptyList();
    private OnSongSettledListener songSettledListener;
    private OnPlaybackGestureListener playbackGestureListener;
    private ValueAnimator playbackAnimator;
    private ValueAnimator rotationAnimator;
    private Bitmap playbackBackground;
    private SongEntry playbackSong;
    private boolean playbackMode;
    private float playbackProgress;
    private float rotationDegrees;
    private float playbackTouchX;
    private float playbackTouchY;
    private float scrubStartAngle;
    private long scrubDeltaMs;
    private long scrubStartPositionMs;
    private long scrubDurationMs;
    private boolean playbackTouchActive;
    private boolean playbackScrubbing;
    private boolean scrubStartedOnLeft;
    private final Runnable longPressAction = () -> {
        if (!playbackTouchActive || !playbackMode || playbackScrubbing) {
            return;
        }
        if (playbackGestureListener == null || !playbackGestureListener.onScrubStarted()) {
            return;
        }
        playbackScrubbing = true;
        scrubStartAngle = rotationDegrees;
        scrubDeltaMs = 0L;
        setPlaybackRotating(false);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    };

    interface OnSongSettledListener {
        void onSongSettled(SongEntry song);
    }

    interface OnPlaybackGestureListener {
        void onExitRequested();
        void onPlaybackToggleRequested();
        boolean onScrubStarted();
        void onScrubFinished(long deltaMs);
    }

    public void setOnSongSettledListener(OnSongSettledListener listener) {
        songSettledListener = listener;
    }

    public void setOnPlaybackGestureListener(OnPlaybackGestureListener listener) {
        playbackGestureListener = listener;
    }

    public void setScrubRange(long positionMs, long durationMs) {
        scrubDurationMs = Math.max(0L, durationMs);
        scrubStartPositionMs = Math.max(0L, Math.min(positionMs, scrubDurationMs));
    }

    public boolean enterPlayback(SongEntry song) {
        if (song == null || !song.available || songs.isEmpty()
                || !songs.get(carouselState.getSlotIndexAtOffset(0)).key.equals(song.key)) {
            return false;
        }
        if (playbackMode) {
            if (playbackSong == null || !playbackSong.key.equals(song.key)) {
                return false;
            }
            if (playbackProgress < 1f) {
                animatePlaybackTo(1f);
            }
            return true;
        }
        playbackSong = song;
        playbackMode = true;
        capturePlaybackBackground();
        animatePlaybackTo(1f);
        return true;
    }

    public void exitPlayback() {
        if (!playbackMode) {
            return;
        }
        cancelPlaybackTouch();
        setPlaybackRotating(false);
        animatePlaybackTo(0f);
    }

    public void setPlaybackRotating(boolean rotating) {
        if (rotationAnimator != null) {
            rotationAnimator.cancel();
            rotationAnimator = null;
        }
        if (!rotating || !playbackMode) {
            return;
        }
        rotationAnimator = ValueAnimator.ofFloat(rotationDegrees, rotationDegrees + 360f);
        rotationAnimator.setDuration(DISC_ROTATION_MS);
        rotationAnimator.setRepeatCount(ValueAnimator.INFINITE);
        rotationAnimator.setInterpolator(new LinearInterpolator());
        rotationAnimator.addUpdateListener(animation -> {
            rotationDegrees = (float) animation.getAnimatedValue();
            invalidate();
        });
        rotationAnimator.start();
    }

    private void animatePlaybackTo(float target) {
        if (playbackAnimator != null) {
            ValueAnimator oldAnimator = playbackAnimator;
            playbackAnimator = null;
            oldAnimator.cancel();
        }
        float start = playbackProgress;
        playbackAnimator = ValueAnimator.ofFloat(start, target);
        playbackAnimator.setDuration(Math.max(1L,
                Math.round(PLAYBACK_TRANSITION_MS * Math.abs(target - start))));
        playbackAnimator.setInterpolator(new DecelerateInterpolator());
        playbackAnimator.addUpdateListener(animation -> {
            playbackProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        playbackAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (playbackAnimator != animation) {
                    return;
                }
                playbackAnimator = null;
                playbackProgress = target;
                if (target == 0f) {
                    playbackMode = false;
                    playbackSong = null;
                    playbackBackground = null;
                }
                invalidate();
            }
        });
        playbackAnimator.start();
    }

    private void capturePlaybackBackground() {
        if (getWidth() == 0 || getHeight() == 0) {
            return;
        }
        int width = Math.max(1, getWidth() / BACKGROUND_DOWNSAMPLE);
        int height = Math.max(1, getHeight() / BACKGROUND_DOWNSAMPLE);
        Bitmap background = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas backgroundCanvas = new Canvas(background);
        backgroundCanvas.drawColor(getContext().getColor(R.color.page_background));
        backgroundCanvas.scale(width / (float) getWidth(), height / (float) getHeight());
        drawQueue(backgroundCanvas, false);
        int[] pixels = new int[width * height];
        background.getPixels(pixels, 0, width, 0, 0, width, height);
        background.setPixels(BackgroundBlur.apply(pixels, width, height,
                BACKGROUND_BLUR_RADIUS), 0, width, 0, 0, width, height);
        playbackBackground = background;
    }

    public DiscCarouselView(Context context, AttributeSet attrs) {
        super(context, attrs);
        flingScroller = new OverScroller(context);
        ViewConfiguration viewConfiguration = ViewConfiguration.get(context);
        minFlingVelocity = viewConfiguration.getScaledMinimumFlingVelocity();
        maxFlingVelocity = viewConfiguration.getScaledMaximumFlingVelocity();
        playbackTouchSlop = viewConfiguration.getScaledTouchSlop();
        setHapticFeedbackEnabled(true);
        edgeMaskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        //十个槽位的颜色先固定好，转回来时还是原来那张唱片
        slotColors = new int[]{
                context.getColor(R.color.disc_ink),
                context.getColor(R.color.disc_blue),
                context.getColor(R.color.disc_paper),
                context.getColor(R.color.disc_copper),
                context.getColor(R.color.disc_stone),
                context.getColor(R.color.disc_blue),
                context.getColor(R.color.disc_ink),
                context.getColor(R.color.disc_paper),
                context.getColor(R.color.disc_stone),
                context.getColor(R.color.disc_copper)
        };
    }

    public void setSelectedSlot(int slotIndex) {
        stopMotion();
        carouselState.selectSlot(slotIndex);
        invalidate();
    }

    public void setSongs(List<SongEntry> newSongs) {
        stopMotion();
        carouselState.cancelInteraction();
        songs = new ArrayList<>(newSongs);
        carouselState.setSlotCount(Math.max(1, songs.size()));
        missingCovers.clear();
        if (playbackMode) {
            capturePlaybackBackground();
        }
        invalidate();
    }

    @Override
    public void computeScroll() {
        if (!flinging) {
            return;
        }
        if (!flingScroller.computeScrollOffset()) {
            flinging = false;
            startSnap();
            return;
        }
        int currentY = flingScroller.getCurrY();
        carouselState.moveBy((lastFlingY - currentY) / getDragStep());
        lastFlingY = currentY;
        if (flingScroller.isFinished()) {
            flinging = false;
            startSnap();
        } else {
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        edgeMaskPaint.setShader(new LinearGradient(0, 0, 0, height,
                new int[]{Color.TRANSPARENT, Color.WHITE, Color.WHITE, Color.TRANSPARENT},
                new float[]{0f, EDGE_FADE_FRACTION, 1f - EDGE_FADE_FRACTION, 1f},
                Shader.TileMode.CLAMP));
        if (playbackMode) {
            capturePlaybackBackground();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (!playbackMode) {
            drawQueue(canvas, true);
            return;
        }
        if (playbackProgress < 1f) {
            drawQueue(canvas, false);
        }
        canvas.drawColor(Color.argb(Math.round(playbackProgress * 220), 250, 249, 246));
        if (playbackBackground != null) {
            backgroundPaint.setAlpha(Math.round(playbackProgress * 180));
            canvas.drawBitmap(playbackBackground, null,
                    new android.graphics.Rect(0, 0, getWidth(), getHeight()), backgroundPaint);
        }
        drawPlaybackDisc(canvas);
        if (playbackScrubbing) {
            drawScrubFeedback(canvas);
        }
    }

    private void drawQueue(Canvas canvas, boolean includeCenter) {

        //边缘多留一个淡入淡出位置，滑动时不会突然冒出或消失
        float discScale = Math.min(getWidth(), dp(430));
        float layerRight = Math.min(getWidth(), discScale * 0.75f + dp(8));
        int layer = canvas.saveLayer(0, 0, layerRight, getHeight(), null);
        int[] drawOffsets = {-3, -2, -1, 0, 1, 2, 3};
        //远的先画，近的后画；滑到半格时遮挡关系也会跟着变
        for (int index = 1; index < drawOffsets.length; index++) {
            int offset = drawOffsets[index];
            int previous = index - 1;
            while (previous >= 0 && Math.abs(carouselState.getRelativePosition(drawOffsets[previous]))
                    < Math.abs(carouselState.getRelativePosition(offset))) {
                drawOffsets[previous + 1] = drawOffsets[previous];
                previous--;
            }
            drawOffsets[previous + 1] = offset;
        }
        for (int offset : drawOffsets) {
            if (!includeCenter && offset == 0) {
                continue;
            }
            float relativePosition = carouselState.getRelativePosition(offset);
            if (relativePosition < -3f || relativePosition > 3f) {
                continue;
            }
            int opacity = Math.round(interpolate(OPACITIES, relativePosition));
            if (opacity == 0) {
                continue;
            }
            float centerX = interpolate(X_POSITIONS, relativePosition) * discScale;
            float centerY = interpolate(Y_POSITIONS, relativePosition) * getHeight();
            float radius = interpolate(SIZES, relativePosition) * discScale;
            int slotIndex = carouselState.getSlotIndexAtOffset(offset);
            SongEntry song = songs.isEmpty() ? null : songs.get(slotIndex);
            Bitmap cover = null;
            if (song != null && song.available) {
                cover = coverCache.get(song.key);
                if (cover == null) {
                    requestCover(song);
                }
            }
            drawDisc(canvas, centerX, centerY, radius,
                    slotColors[slotIndex % slotColors.length], opacity, cover, true);
        }
        //整层一起渐隐，唱片外圈和阴影也不会在屏幕边缘留下白边
        canvas.drawRect(0, 0, layerRight, getHeight(), edgeMaskPaint);
        canvas.restoreToCount(layer);
    }

    private void drawPlaybackDisc(Canvas canvas) {
        if (playbackSong == null) {
            return;
        }
        float discScale = Math.min(getWidth(), dp(430));
        float centerX = lerp(X_POSITIONS[3] * discScale, getWidth() / 2f, playbackProgress);
        float centerY = lerp(Y_POSITIONS[3] * getHeight(), getHeight() / 2f,
                playbackProgress);
        float radius = lerp(SIZES[3] * discScale,
                Math.min(getWidth() * 0.43f, getHeight() * 0.28f), playbackProgress);
        int slot = carouselState.getSlotIndexAtOffset(0);
        Bitmap cover = coverCache.get(playbackSong.key);
        if (cover == null && playbackSong.available) {
            requestCover(playbackSong);
        }
        int save = canvas.save();
        canvas.rotate(rotationDegrees, centerX, centerY);
        drawDisc(canvas, centerX, centerY, radius,
                slotColors[slot % slotColors.length], 255, cover, false);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(Color.WHITE);
        paint.setAlpha(190);
        canvas.drawLine(centerX + radius * 0.78f, centerY,
                centerX + radius * 0.86f, centerY, paint);
        canvas.restoreToCount(save);
    }

    private float lerp(float start, float end, float progress) {
        return start + (end - start) * progress;
    }

    private void drawScrubFeedback(Canvas canvas) {
        long seconds = Math.abs(scrubDeltaMs) / 1000L;
        String label;
        if (scrubDeltaMs > 0L) {
            label = getResources().getString(R.string.scrub_forward,
                    seconds / 60L, seconds % 60L);
        } else if (scrubDeltaMs < 0L) {
            label = getResources().getString(R.string.scrub_backward,
                    seconds / 60L, seconds % 60L);
        } else {
            label = getResources().getString(R.string.scrub_hint);
        }
        float radius = Math.min(getWidth() * 0.43f, getHeight() * 0.28f);
        float barY = Math.min(getHeight() - dp(45),
                getHeight() / 2f + radius + dp(64));
        float textY = barY - dp(24);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(16));
        paint.setColor(getContext().getColor(R.color.text_primary));
        canvas.drawText(label, getWidth() / 2f, textY, paint);

        float barWidth = Math.min(getWidth() - dp(64), dp(300));
        float barLeft = (getWidth() - barWidth) / 2f;
        float barRight = barLeft + barWidth;
        long targetMs = PlaybackGesture.scrubPositionMs(
                scrubStartPositionMs, scrubDurationMs, scrubDeltaMs);
        float progress = scrubDurationMs > 0L ? targetMs / (float) scrubDurationMs : 0f;
        float thumbX = barLeft + barWidth * progress;
        paint.setColor(getContext().getColor(R.color.scrub_track));
        canvas.drawRoundRect(barLeft, barY - dp(1.5f), barRight,
                barY + dp(1.5f), dp(1.5f), dp(1.5f), paint);
        paint.setColor(getContext().getColor(R.color.scrub_progress));
        if (thumbX > barLeft) {
            canvas.drawRoundRect(barLeft, barY - dp(1.5f), thumbX,
                    barY + dp(1.5f), dp(1.5f), dp(1.5f), paint);
        }
        canvas.drawCircle(thumbX, barY, dp(4), paint);
        paint.setTextSize(dp(12));
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(formatTime(targetMs), barLeft, barY + dp(21), paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(formatTime(scrubDurationMs), barRight, barY + dp(21), paint);
    }

    private String formatTime(long durationMs) {
        long seconds = durationMs / 1000L;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L);
    }

    private float interpolate(float[] values, float relativePosition) {
        float arrayPosition = relativePosition + 3f;
        int lower = Math.min((int) arrayPosition, values.length - 2);
        float fraction = arrayPosition - lower;
        return values[lower] + (values[lower + 1] - values[lower]) * fraction;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (playbackMode) {
            return handlePlaybackTouch(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                stopMotion();
                carouselState.beginInteraction();
                velocityTracker = VelocityTracker.obtain();
                velocityTracker.addMovement(event);
                activePointerId = event.getPointerId(0);
                lastTouchY = event.getY(0);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (velocityTracker != null) {
                    velocityTracker.addMovement(event);
                }
                int pointerIndex = event.findPointerIndex(activePointerId);
                if (pointerIndex < 0 || getHeight() == 0) {
                    return true;
                }
                float touchY = event.getY(pointerIndex);
                carouselState.moveBy((lastTouchY - touchY) / getDragStep());
                lastTouchY = touchY;
                invalidate();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (velocityTracker != null) {
                    velocityTracker.addMovement(event);
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex()) == activePointerId) {
                    int nextPointer = event.getActionIndex() == 0 ? 1 : 0;
                    activePointerId = event.getPointerId(nextPointer);
                    lastTouchY = event.getY(nextPointer);
                    //换手指时重新计速，避免把两根手指的位移算成一次猛甩
                    if (velocityTracker != null) {
                        velocityTracker.clear();
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (velocityTracker != null) {
                    velocityTracker.addMovement(event);
                    velocityTracker.computeCurrentVelocity(1000, maxFlingVelocity);
                    float velocityY = velocityTracker.getYVelocity(activePointerId);
                    if (Math.abs(velocityY) >= minFlingVelocity && getHeight() > 0) {
                        startFling(Math.round(velocityY));
                    } else {
                        startSnap();
                    }
                } else {
                    startSnap();
                }
                recycleVelocityTracker();
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                return true;
            case MotionEvent.ACTION_CANCEL:
                recycleVelocityTracker();
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                carouselState.cancelInteraction();
                stopMotion();
                startSnap();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private boolean handlePlaybackTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                playbackTouchX = event.getX();
                playbackTouchY = event.getY();
                scrubStartedOnLeft = playbackTouchX < getWidth() / 2f;
                playbackTouchActive = true;
                playbackScrubbing = false;
                postDelayed(longPressAction, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!playbackTouchActive) {
                    return true;
                }
                float deltaX = event.getX() - playbackTouchX;
                float deltaY = event.getY() - playbackTouchY;
                if (playbackScrubbing) {
                    float density = getResources().getDisplayMetrics().density;
                    long requestedDeltaMs = PlaybackGesture.scrubDeltaMs(
                            deltaY, density, scrubStartedOnLeft);
                    scrubDeltaMs = PlaybackGesture.scrubPositionMs(scrubStartPositionMs,
                            scrubDurationMs, requestedDeltaMs) - scrubStartPositionMs;
                    rotationDegrees = scrubStartAngle
                            + PlaybackGesture.scrubRotationDegrees(
                                    deltaY, density, scrubStartedOnLeft);
                    invalidate();
                } else if (Math.hypot(deltaX, deltaY) > playbackTouchSlop) {
                    removeCallbacks(longPressAction);
                }
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPressAction);
                if (!playbackTouchActive) {
                    return true;
                }
                playbackTouchActive = false;
                if (playbackScrubbing) {
                    playbackScrubbing = false;
                    invalidate();
                    if (playbackGestureListener != null) {
                        playbackGestureListener.onScrubFinished(scrubDeltaMs);
                    }
                    return true;
                }
                float horizontal = event.getX() - playbackTouchX;
                float vertical = event.getY() - playbackTouchY;
                if (playbackGestureListener == null) {
                    return true;
                }
                if (PlaybackGesture.isExitSwipe(horizontal, vertical,
                        getResources().getDisplayMetrics().density)) {
                    playbackGestureListener.onExitRequested();
                } else if (Math.hypot(horizontal, vertical) <= playbackTouchSlop) {
                    playbackGestureListener.onPlaybackToggleRequested();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelPlaybackTouch();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                cancelPlaybackTouch();
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void cancelPlaybackTouch() {
        removeCallbacks(longPressAction);
        if (playbackScrubbing && playbackGestureListener != null) {
            playbackGestureListener.onScrubFinished(0L);
        }
        playbackScrubbing = false;
        playbackTouchActive = false;
        invalidate();
    }

    private float getDragStep() {
        return getHeight() * DRAG_STEP_FRACTION;
    }

    private void startFling(int velocityY) {
        lastFlingY = 0;
        flinging = true;
        flingScroller.fling(0, 0, 0, velocityY,
                0, 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
        postInvalidateOnAnimation();
    }

    private void startSnap() {
        float start = carouselState.getPosition();
        float target = carouselState.getSnapTarget();
        if (start == target) {
            notifySettledSong();
            return;
        }
        snapAnimator = ValueAnimator.ofFloat(start, target);
        snapAnimator.setDuration(SNAP_DURATION_MS);
        snapAnimator.setInterpolator(new DecelerateInterpolator());
        snapAnimator.addUpdateListener(animation -> {
            carouselState.setPosition((float) animation.getAnimatedValue());
            invalidate();
        });
        snapAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (snapAnimator != animation) {
                    return;
                }
                carouselState.setPosition(target);
                snapAnimator = null;
                invalidate();
                notifySettledSong();
            }
        });
        snapAnimator.start();
    }

    private void notifySettledSong() {
        int slot = carouselState.takeSettledSlot();
        if (slot >= 0 && slot < songs.size() && songSettledListener != null) {
            songSettledListener.onSongSettled(songs.get(slot));
        }
    }

    private void stopMotion() {
        //forceFinished 保留当前帧；abortAnimation 会跳到终点
        flingScroller.forceFinished(true);
        flinging = false;
        if (snapAnimator != null) {
            ValueAnimator oldAnimator = snapAnimator;
            snapAnimator = null;
            oldAnimator.cancel();
        }
    }

    private void recycleVelocityTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    private void requestCover(SongEntry song) {
        if (!isAttachedToWindow() || pendingCovers.contains(song.key)
                || missingCovers.contains(song.key)
                || pendingCovers.size() >= MAX_PENDING_COVERS) {
            return;
        }
        pendingCovers.add(song.key);
        coverExecutor.execute(() -> {
            Bitmap cover = AlbumArtwork.load(getContext(), song, COVER_SIZE_PX);
            post(() -> {
                pendingCovers.remove(song.key);
                if (cover == null) {
                    missingCovers.add(song.key);
                } else {
                    coverCache.put(song.key, cover);
                }
                if (playbackMode && playbackSong != null
                        && !playbackSong.key.equals(song.key)) {
                    capturePlaybackBackground();
                }
                invalidate();
            });
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (coverExecutor.isShutdown()) {
            coverExecutor = Executors.newSingleThreadExecutor();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopMotion();
        cancelPlaybackTouch();
        if (playbackAnimator != null) {
            ValueAnimator oldAnimator = playbackAnimator;
            playbackAnimator = null;
            oldAnimator.cancel();
        }
        setPlaybackRotating(false);
        playbackMode = false;
        playbackProgress = 0f;
        playbackBackground = null;
        recycleVelocityTracker();
        coverExecutor.shutdownNow();
        pendingCovers.clear();
        super.onDetachedFromWindow();
    }

    private void drawDisc(Canvas canvas, float centerX, float centerY,
                          float radius, int color, int opacity, Bitmap cover,
                          boolean drawShadow) {
        paint.setStyle(Paint.Style.FILL);
        if (drawShadow) {
            paint.setColor(Color.argb(opacity / 9, 30, 30, 30));
            canvas.drawCircle(centerX + dp(3), centerY + dp(7), radius + dp(3), paint);
        }

        paint.setColor(Color.WHITE);
        paint.setAlpha(opacity);
        canvas.drawCircle(centerX, centerY, radius + dp(2), paint);

        paint.setColor(cover == null ? color : Color.WHITE);
        paint.setAlpha(opacity);
        if (cover != null) {
            BitmapShader shader = new BitmapShader(cover,
                    Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float scale = radius * 2f / Math.min(cover.getWidth(), cover.getHeight());
            Matrix matrix = new Matrix();
            matrix.setScale(scale, scale);
            matrix.postTranslate(centerX - cover.getWidth() * scale / 2f,
                    centerY - cover.getHeight() * scale / 2f);
            shader.setLocalMatrix(matrix);
            paint.setShader(shader);
        }
        canvas.drawCircle(centerX, centerY, radius, paint);
        paint.setShader(null);

        //加几圈浅浅的纹路，纯色封面也能看出它是一张唱片
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(Color.WHITE);
        paint.setAlpha(opacity / 5);
        for (float ring = 0.72f; ring <= 0.9f; ring += 0.09f) {
            canvas.drawCircle(centerX, centerY, radius * ring, paint);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setAlpha(opacity * 3 / 4);
        canvas.drawCircle(centerX, centerY, radius * 0.22f, paint);
        paint.setColor(getContext().getColor(R.color.page_background));
        paint.setAlpha(opacity);
        canvas.drawCircle(centerX, centerY, radius * 0.15f, paint);
        paint.setColor(getContext().getColor(R.color.text_primary));
        paint.setAlpha(opacity);
        canvas.drawCircle(centerX, centerY, radius * 0.07f, paint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
