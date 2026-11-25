package org.kleeui.camera;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.util.Size;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

/** Composites the bright parts of a fixed-camera sequence over its first frame. */
public final class LightTrails {
    private static final int MIN_FRAMES = 8;
    private static final int MAX_SIDE = 2304;
    private static final int MIN_LUMA_INCREASE = 12;

    private LightTrails() {}

    public static byte[] merge(List<byte[]> frames) throws IOException {
        if (frames.size() < MIN_FRAMES) {
            throw new IOException("Light trails need at least eight frames");
        }
        Bitmap composite = decode(frames.get(0), true);
        try {
            int width = composite.getWidth();
            int height = composite.getHeight();
            int[] outputRow = new int[width];
            int[] frameRow = new int[width];
            byte[] baseLuma = new byte[width * height];
            for (int y = 0; y < height; y++) {
                composite.getPixels(outputRow, 0, width, 0, y, width, 1);
                for (int x = 0; x < width; x++) {
                    baseLuma[y * width + x] = (byte) luminance(outputRow[x]);
                }
            }
            for (int index = 1; index < frames.size(); index++) {
                Bitmap frame = decode(frames.get(index), false);
                try {
                    if (frame.getWidth() != width || frame.getHeight() != height) {
                        throw new IOException("Light trail frame " + index + " has a different size");
                    }
                    for (int y = 0; y < height; y++) {
                        composite.getPixels(outputRow, 0, width, 0, y, width, 1);
                        frame.getPixels(frameRow, 0, width, 0, y, width, 1);
                        for (int x = 0; x < width; x++) {
                            int candidate = luminance(frameRow[x]);
                            int background = Byte.toUnsignedInt(baseLuma[y * width + x]);
                            if (candidate >= background + MIN_LUMA_INCREASE
                                    && candidate > luminance(outputRow[x])) {
                                outputRow[x] = frameRow[x];
                            }
                        }
                        composite.setPixels(outputRow, 0, width, 0, y, width, 1);
                    }
                } finally {
                    frame.recycle();
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!composite.compress(Bitmap.CompressFormat.JPEG, 94, output)) {
                throw new IOException("Could not encode the light trails JPEG");
            }
            return output.toByteArray();
        } finally {
            composite.recycle();
        }
    }

    private static Bitmap decode(byte[] jpeg, boolean mutable) throws IOException {
        if (jpeg.length == 0) {
            throw new IOException("Light trail frame is empty");
        }
        ImageDecoder.Source input = ImageDecoder.createSource(ByteBuffer.wrap(jpeg));
        // ImageDecoder applies the encoded orientation before the frames are compared.
        return ImageDecoder.decodeBitmap(input, (decoder, info, source) -> {
            Size size = info.getSize();
            int side = Math.max(size.getWidth(), size.getHeight());
            if (side > MAX_SIDE) {
                decoder.setTargetSize(Math.max(1, (int) ((long) size.getWidth() * MAX_SIDE / side)),
                        Math.max(1, (int) ((long) size.getHeight() * MAX_SIDE / side)));
            }
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
            decoder.setMutableRequired(mutable);
        });
    }

    private static int luminance(int pixel) {
        int red = (pixel >> 16) & 255;
        int green = (pixel >> 8) & 255;
        int blue = pixel & 255;
        return (77 * red + 150 * green + 29 * blue + 128) >> 8;
    }
}
