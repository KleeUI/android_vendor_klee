package org.kleeui.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.Image;
import android.util.Log;
import android.view.View;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Displays a subsampled copy of the 4K recording stream. */
final class RecordingPreview extends View {
    private static final String TAG = "KleeRecordingPreview";
    private static final int WIDTH = 320;
    private static final int HEIGHT = 180;
    private static final long FRAME_INTERVAL_NS = 40_000_000L;

    private final int[] pixels = new int[WIDTH * HEIGHT];
    private final int[] lumaColumns = new int[WIDTH];
    private final int[] chromaColumns = new int[WIDTH];
    private final Bitmap frame = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF destination = new RectF(-WIDTH / 2f, -HEIGHT / 2f, WIDTH / 2f, HEIGHT / 2f);
    private final AtomicBoolean copying = new AtomicBoolean();
    private final AtomicInteger drawnFrames = new AtomicInteger();
    private volatile int activeToken;
    private int nextToken;
    private int bitmapToken;
    private int rotation;
    private boolean newFrame;
    private boolean loggedCopyFailure;
    private long nextTimestamp;

    RecordingPreview(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void begin() {
        drawnFrames.set(0);
        loggedCopyFailure = false;
        nextTimestamp = 0;
        activeToken = ++nextToken;
    }

    int end() {
        activeToken = 0;
        postInvalidate();
        return drawnFrames.get();
    }

    void offer(Image image, int sensorRotation) {
        int token = activeToken;
        long timestamp = image.getTimestamp();
        if (token == 0 || timestamp < nextTimestamp || !copying.compareAndSet(false, true)) return;
        nextTimestamp = timestamp + FRAME_INTERVAL_NS;
        try {
            copyPixels(image);
            if (post(() -> {
                try {
                    if (activeToken != token) return;
                    frame.setPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
                    rotation = sensorRotation;
                    bitmapToken = token;
                    newFrame = true;
                    invalidate();
                } finally {
                    copying.set(false);
                }
            })) return;
        } catch (RuntimeException error) {
            if (!loggedCopyFailure) {
                loggedCopyFailure = true;
                Log.w(TAG, "Could not copy recording frame for preview", error);
            }
        }
        copying.set(false);
    }

    private void copyPixels(Image image) {
        Rect crop = image.getCropRect();
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer luma = planes[0].getBuffer();
        ByteBuffer cb = planes[1].getBuffer();
        ByteBuffer cr = planes[2].getBuffer();
        int lumaStride = planes[0].getRowStride();
        int chromaStride = planes[1].getRowStride();
        int chromaStep = planes[1].getPixelStride();
        int lumaStart = luma.position();
        int cbStart = cb.position();
        int crStart = cr.position();

        for (int x = 0; x < WIDTH; x++) {
            int inputX = crop.left + (2 * x + 1) * crop.width() / (2 * WIDTH);
            lumaColumns[x] = inputX;
            chromaColumns[x] = inputX / 2 * chromaStep;
        }

        for (int y = 0; y < HEIGHT; y++) {
            int inputY = crop.top + (2 * y + 1) * crop.height() / (2 * HEIGHT);
            int lumaRow = lumaStart + inputY * lumaStride;
            int chromaRow = inputY / 2 * chromaStride;
            for (int x = 0; x < WIDTH; x++) {
                int chromaIndex = chromaRow + chromaColumns[x];
                int yy = Math.max(0, (luma.get(lumaRow + lumaColumns[x]) & 255) - 16) * 298;
                int u = (cb.get(cbStart + chromaIndex) & 255) - 128;
                int v = (cr.get(crStart + chromaIndex) & 255) - 128;
                int red = clamp((yy + 459 * v + 128) >> 8);
                int green = clamp((yy - 55 * u - 136 * v + 128) >> 8);
                int blue = clamp((yy + 541 * u + 128) >> 8);
                pixels[y * WIDTH + x] = 0xff000000 | (red << 16) | (green << 8) | blue;
            }
        }
    }

    private static int clamp(int channel) { return Math.max(0, Math.min(255, channel)); }

    @Override protected void onDraw(Canvas canvas) {
        int token = activeToken;
        if (token == 0 || bitmapToken != token) return;
        boolean sideways = rotation % 180 != 0;
        float scale = Math.max(getWidth() / (float) (sideways ? HEIGHT : WIDTH),
                getHeight() / (float) (sideways ? WIDTH : HEIGHT));
        canvas.save();
        canvas.translate(getWidth() / 2f, getHeight() / 2f);
        canvas.scale(scale, scale);
        canvas.rotate(rotation);
        canvas.drawBitmap(frame, null, destination, paint);
        canvas.restore();
        if (newFrame) {
            newFrame = false;
            drawnFrames.incrementAndGet();
        }
    }
}
