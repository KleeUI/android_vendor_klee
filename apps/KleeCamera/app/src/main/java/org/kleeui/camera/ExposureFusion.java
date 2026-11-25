package org.kleeui.camera;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.util.Log;
import android.util.Size;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Aligns and fuses a short exposure bracket or a same-exposure night burst. */
public final class ExposureFusion {
    private static final String TAG = "ExposureFusion";
    private static final int MAX_SIDE = 2304;
    private static final int GUIDE_SIDE = 640;
    private static final int COARSE_SIDE = 160;
    private static final int REFERENCE = 1;
    private static final float MIN_ALIGNMENT = 0.43f;
    private static final int LINEAR_WHITE = 4096;
    private static final int[] LINEAR = new int[256];
    private static final int[] TONEMAPPED = new int[LINEAR_WHITE * 4 + 1];

    static {
        for (int value = 0; value < LINEAR.length; value++) {
            double encoded = value / 255.0;
            double linear = encoded <= 0.04045 ? encoded / 12.92 : Math.pow((encoded + 0.055) / 1.055, 2.4);
            LINEAR[value] = (int) Math.round(linear * LINEAR_WHITE);
        }
        for (int value = 0; value < TONEMAPPED.length; value++) {
            double linear = value / (double) LINEAR_WHITE;
            double mapped = linear <= 0.22 ? linear : 0.22 + 0.78 * (linear - 0.22) / (linear + 0.56);
            double encoded = mapped <= 0.0031308 ? mapped * 12.92 : 1.055 * Math.pow(mapped, 1 / 2.4) - 0.055;
            TONEMAPPED[value] = (int) Math.round(encoded * 255);
        }
    }

    private ExposureFusion() {}

    /** Returns the reference JPEG itself when the supporting frames cannot be used. */
    public static byte[] merge(List<byte[]> frames, boolean hdr) throws IOException {
        if (frames.size() != (hdr ? 3 : 4)) {
            throw new IOException(hdr ? "HDR needs three frames" : "Night mode needs four frames");
        }
        byte[] referenceJpeg = frames.get(REFERENCE);
        if (referenceJpeg.length == 0) {
            throw new IOException("Reference JPEG is empty");
        }
        Bitmap reference = decode(referenceJpeg);
        List<AlignedFrame> aligned = new ArrayList<>();
        try {
            Guide fine = new Guide(reference, GUIDE_SIDE);
            Guide coarse = new Guide(reference, COARSE_SIDE);
            for (int index = 0; index < frames.size(); index++) {
                if (index == REFERENCE || frames.get(index).length == 0) {
                    continue;
                }
                Bitmap source;
                try {
                    source = decode(frames.get(index));
                } catch (IOException e) {
                    // The reference remains available when one of the supporting JPEGs is invalid.
                    Log.w(TAG, "Could not decode supporting frame " + index, e);
                    continue;
                }
                boolean retained = false;
                try {
                    if (source.getWidth() == reference.getWidth() && source.getHeight() == reference.getHeight()) {
                        AlignedFrame frame = align(source, fine, coarse, hdr);
                        if (frame != null && (!hdr || Math.abs(frame.gain - 1f) >= 0.14f)) {
                            aligned.add(frame);
                            retained = true;
                        }
                    } else {
                        Log.i(TAG, "Supporting frame " + index + " has a different decoded size");
                    }
                } finally {
                    if (!retained) {
                        source.recycle();
                    }
                }
            }
            if (aligned.isEmpty()) {
                return referenceJpeg;
            }
            fuse(reference, aligned, hdr);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!reference.compress(Bitmap.CompressFormat.JPEG, 94, output)) {
                throw new IOException("Could not encode the fused JPEG");
            }
            return output.toByteArray();
        } finally {
            for (AlignedFrame frame : aligned) {
                frame.image.recycle();
            }
            reference.recycle();
        }
    }

    private static Bitmap decode(byte[] jpeg) throws IOException {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(jpeg)), (decoder, info, source) -> {
            Size original = info.getSize();
            int side = Math.max(original.getWidth(), original.getHeight());
            if (side > MAX_SIDE) {
                decoder.setTargetSize(Math.max(1, (int) ((long) original.getWidth() * MAX_SIDE / side)),
                        Math.max(1, (int) ((long) original.getHeight() * MAX_SIDE / side)));
            }
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setMutableRequired(true);
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
        });
    }

    private static AlignedFrame align(Bitmap image, Guide fine, Guide coarse, boolean hdr) {
        Guide small = new Guide(image, COARSE_SIDE);
        int range = Math.min(7, Math.min(coarse.width, coarse.height) / 12);
        Shift first = search(coarse, small, 0, 0, range, 2);
        Guide detailed = new Guide(image, GUIDE_SIDE);
        int centerX = Math.round((float) first.x * fine.width / coarse.width);
        int centerY = Math.round((float) first.y * fine.height / coarse.height);
        Shift shift = search(fine, detailed, centerX, centerY, 4, 5);
        float gain = hdr && shift.score >= MIN_ALIGNMENT ? exposureGain(fine, detailed, shift) : 1f;
        Log.i(TAG, (hdr ? "HDR" : "Night") + " alignment=" + shift.score
                + " coarse=" + first.score + " dx=" + shift.x + " dy=" + shift.y + " exposureGain=" + gain);
        if (shift.score < MIN_ALIGNMENT) {
            return null;
        }
        int x = Math.round((float) image.getWidth() * shift.x / fine.width);
        int y = Math.round((float) image.getHeight() * shift.y / fine.height);
        return new AlignedFrame(image, x, y, gain);
    }

    private static Shift search(Guide reference, Guide other, int centerX, int centerY, int range, int step) {
        int marginX = 2 + Math.abs(centerX) + range;
        int marginY = 2 + Math.abs(centerY) + range;
        Shift best = new Shift(0, 0, -1f);
        for (int dy = centerY - range; dy <= centerY + range; dy++) {
            for (int dx = centerX - range; dx <= centerX + range; dx++) {
                float score = correlation(reference, other, marginX, marginY, dx, dy, step);
                // Prefer less translation when two offsets describe nearly the same structure.
                score -= (Math.abs(dx) + Math.abs(dy)) * 0.0007f;
                if (score > best.score) {
                    best = new Shift(dx, dy, score);
                }
            }
        }
        return best;
    }

    private static float correlation(Guide reference, Guide other, int marginX, int marginY, int dx, int dy, int step) {
        long dot = 0;
        long energyReference = 0;
        long energyOther = 0;
        for (int y = marginY; y < reference.height - marginY; y += step) {
            int index = y * reference.width + marginX;
            int shifted = (y + dy) * other.width + marginX + dx;
            for (int x = marginX; x < reference.width - marginX; x += step, index += step, shifted += step) {
                int ax = reference.luma[index + 2] - reference.luma[index - 2];
                int ay = reference.luma[index + 2 * reference.width] - reference.luma[index - 2 * reference.width];
                int bx = other.luma[shifted + 2] - other.luma[shifted - 2];
                int by = other.luma[shifted + 2 * other.width] - other.luma[shifted - 2 * other.width];
                dot += ax * bx + ay * by;
                energyReference += ax * ax + ay * ay;
                energyOther += bx * bx + by * by;
            }
        }
        return energyReference < 100 || energyOther < 100 ? -1f
                : (float) (dot / Math.sqrt((double) energyReference * energyOther));
    }

    private static float exposureGain(Guide reference, Guide image, Shift shift) {
        long original = 0;
        long bracket = 0;
        for (int y = Math.max(0, -shift.y); y < Math.min(reference.height, image.height - shift.y); y += 5) {
            for (int x = Math.max(0, -shift.x); x < Math.min(reference.width, image.width - shift.x); x += 5) {
                int first = reference.luma[y * reference.width + x];
                int second = image.luma[(y + shift.y) * image.width + x + shift.x];
                if (first >= 46 && first <= 221 && second >= 46 && second <= 221) {
                    original += LINEAR[first];
                    bracket += LINEAR[second];
                }
            }
        }
        return bracket > 0 ? Math.max(0.25f, Math.min(4f, (float) original / bracket)) : 1f;
    }

    private static void fuse(Bitmap reference, List<AlignedFrame> frames, boolean hdr) {
        int width = reference.getWidth();
        int height = reference.getHeight();
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            reference.getPixels(row, 0, width, 0, y, width, 1);
            for (AlignedFrame frame : frames) {
                int sourceY = y + frame.dy;
                frame.rowAvailable = sourceY >= 0 && sourceY < height;
                if (frame.rowAvailable) {
                    frame.image.getPixels(frame.row, 0, width, 0, sourceY, width, 1);
                }
            }
            for (int x = 0; x < width; x++) {
                row[x] = hdr ? hdrPixel(row[x], frames, x) : nightPixel(row[x], frames, x);
            }
            reference.setPixels(row, 0, width, 0, y, width, 1);
        }
    }

    private static int hdrPixel(int pixel, List<AlignedFrame> frames, int x) {
        int baseLuma = luminance(pixel);
        float weight = exposureWeight(baseLuma) * 1.3f;
        float red = LINEAR[red(pixel)] * weight;
        float green = LINEAR[green(pixel)] * weight;
        float blue = LINEAR[blue(pixel)] * weight;
        float total = weight;
        for (int index = 0; index < frames.size(); index++) {
            AlignedFrame frame = frames.get(index);
            if (!frame.availableAt(x)) {
                continue;
            }
            int sample = frame.row[x + frame.dx];
            int sampleLuma = luminance(sample);
            float normalized = LINEAR[sampleLuma] * frame.gain;
            float difference = Math.abs(normalized - LINEAR[baseLuma]);
            float tolerance = 90f + LINEAR[baseLuma] * 0.35f;
            if (baseLuma >= 235 && frame.gain > 1f && normalized >= LINEAR[225]) {
                difference = 0;
            }
            float confidence = 1f - Math.min(1f, difference / tolerance);
            weight = exposureWeight(sampleLuma) * confidence;
            float scaled = frame.gain * weight;
            red += LINEAR[red(sample)] * scaled;
            green += LINEAR[green(sample)] * scaled;
            blue += LINEAR[blue(sample)] * scaled;
            total += weight;
        }
        return pack(tone(red / total), tone(green / total), tone(blue / total));
    }

    private static float exposureWeight(int luma) {
        return 0.025f + Math.min(0.9f, Math.min(luma, 255 - luma) / 110f);
    }

    private static int tone(float value) {
        return TONEMAPPED[Math.min(TONEMAPPED.length - 1, Math.max(0, Math.round(value)))];
    }

    private static int nightPixel(int pixel, List<AlignedFrame> frames, int x) {
        int baseLuma = luminance(pixel);
        int red = red(pixel) * 32;
        int green = green(pixel) * 32;
        int blue = blue(pixel) * 32;
        int total = 32;
        for (int index = 0; index < frames.size(); index++) {
            AlignedFrame frame = frames.get(index);
            if (!frame.availableAt(x)) {
                continue;
            }
            int sample = frame.row[x + frame.dx];
            int difference = Math.abs(baseLuma - luminance(sample));
            int weight = Math.max(0, 24 - difference);
            red += red(sample) * weight;
            green += green(sample) * weight;
            blue += blue(sample) * weight;
            total += weight;
        }
        return pack(nightTone(red / total), nightTone(green / total), nightTone(blue / total));
    }

    private static int nightTone(int value) {
        return value + value * (255 - value) / 1100;
    }

    private static int red(int pixel) { return (pixel >> 16) & 255; }
    private static int green(int pixel) { return (pixel >> 8) & 255; }
    private static int blue(int pixel) { return pixel & 255; }
    private static int pack(int red, int green, int blue) { return 0xff000000 | red << 16 | green << 8 | blue; }

    private static int luminance(int pixel) {
        return (77 * red(pixel) + 150 * green(pixel) + 29 * blue(pixel) + 128) >> 8;
    }

    private static final class Shift {
        final int x;
        final int y;
        final float score;

        Shift(int x, int y, float score) {
            this.x = x;
            this.y = y;
            this.score = score;
        }
    }

    private static final class Guide {
        final int width;
        final int height;
        final int[] luma;

        Guide(Bitmap image, int maximumSide) {
            int longest = Math.max(image.getWidth(), image.getHeight());
            width = Math.max(1, image.getWidth() * Math.min(longest, maximumSide) / longest);
            height = Math.max(1, image.getHeight() * Math.min(longest, maximumSide) / longest);
            Bitmap resized = Bitmap.createScaledBitmap(image, width, height, true);
            try {
                luma = new int[width * height];
                resized.getPixels(luma, 0, width, 0, 0, width, height);
                for (int i = 0; i < luma.length; i++) {
                    luma[i] = luminance(luma[i]);
                }
                softenGuideNoise();
            } finally {
                if (resized != image) {
                    resized.recycle();
                }
            }
        }

        private void softenGuideNoise() {
            // A separable 1-2-1 filter keeps dark-scene sensor noise out of the motion estimate.
            int[] horizontal = luma.clone();
            for (int y = 0; y < height; y++) {
                for (int x = 1; x < width - 1; x++) {
                    int i = y * width + x;
                    horizontal[i] = (luma[i - 1] + 2 * luma[i] + luma[i + 1] + 2) / 4;
                }
            }
            for (int y = 1; y < height - 1; y++) {
                for (int x = 0; x < width; x++) {
                    int i = y * width + x;
                    luma[i] = (horizontal[i - width] + 2 * horizontal[i] + horizontal[i + width] + 2) / 4;
                }
            }
        }
    }

    private static final class AlignedFrame {
        final Bitmap image;
        final int dx;
        final int dy;
        final float gain;
        final int[] row;
        boolean rowAvailable;

        AlignedFrame(Bitmap image, int dx, int dy, float gain) {
            this.image = image;
            this.dx = dx;
            this.dy = dy;
            this.gain = gain;
            row = new int[image.getWidth()];
        }

        boolean availableAt(int x) {
            return rowAvailable && x + dx >= 0 && x + dx < row.length;
        }
    }
}
