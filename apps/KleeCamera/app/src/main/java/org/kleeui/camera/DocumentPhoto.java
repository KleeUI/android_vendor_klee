package org.kleeui.camera;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.util.Size;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Enhances printed or handwritten text by normalizing the local paper brightness. */
public final class DocumentPhoto {
    private static final int MAX_SIDE = 3200;
    private static final int TILE_SIZE = 96;
    private static final int SAMPLE_STEP = 3;
    private static final int HISTOGRAM_BINS = 64;

    private DocumentPhoto() {}

    public static byte[] enhance(byte[] jpeg) throws IOException {
        if (jpeg.length == 0) {
            throw new IOException("Document JPEG is empty");
        }
        ImageDecoder.Source input = ImageDecoder.createSource(ByteBuffer.wrap(jpeg));
        // ImageDecoder honors EXIF rotation and mirroring in the decoded pixels.
        Bitmap bitmap = ImageDecoder.decodeBitmap(input, (decoder, info, source) -> {
            Size size = info.getSize();
            int longestEdge = Math.max(size.getWidth(), size.getHeight());
            if (longestEdge > MAX_SIDE) {
                decoder.setTargetSize(Math.max(1, size.getWidth() * MAX_SIDE / longestEdge),
                        Math.max(1, size.getHeight() * MAX_SIDE / longestEdge));
            }
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setMutableRequired(true);
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
        });
        try {
            enhanceBitmap(bitmap);
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 93, encoded)) {
                throw new IOException("Could not encode document image");
            }
            return encoded.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    private static void enhanceBitmap(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int columns = (width + TILE_SIZE - 1) / TILE_SIZE;
        int rows = (height + TILE_SIZE - 1) / TILE_SIZE;
        int[] scanline = new int[width];
        int[][] paper = estimatePaper(bitmap, scanline, columns, rows);
        int[] left = new int[width];
        int[] right = new int[width];
        int[] horizontalWeight = new int[width];
        for (int x = 0; x < width; x++) {
            int coordinate = tileCoordinate(x, width, columns);
            left[x] = coordinate >> 8;
            right[x] = Math.min(left[x] + 1, columns - 1);
            horizontalWeight[x] = coordinate & 255;
        }
        int[] rowPaper = new int[columns];
        for (int y = 0; y < height; y++) {
            int coordinate = tileCoordinate(y, height, rows);
            int top = coordinate >> 8;
            int bottom = Math.min(top + 1, rows - 1);
            int verticalWeight = coordinate & 255;
            for (int x = 0; x < columns; x++) {
                rowPaper[x] = interpolate(paper[top][x], paper[bottom][x], verticalWeight);
            }
            bitmap.getPixels(scanline, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                int background = interpolate(rowPaper[left[x]], rowPaper[right[x]], horizontalWeight[x]);
                int range = Math.max(32, background * 2 / 5);
                int value = 255 + (luminance(scanline[x]) - background + 5) * 255 / range;
                int gray = Math.max(0, Math.min(255, value));
                scanline[x] = 0xff000000 | (gray << 16) | (gray << 8) | gray;
            }
            bitmap.setPixels(scanline, 0, width, 0, y, width, 1);
        }
    }

    private static int[][] estimatePaper(Bitmap bitmap, int[] scanline, int columns, int rows) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[][] paper = new int[rows][columns];
        for (int row = 0; row < rows; row++) {
            int[][] histograms = new int[columns][HISTOGRAM_BINS];
            int[] samples = new int[columns];
            for (int y = row * height / rows; y < (row + 1) * height / rows; y += SAMPLE_STEP) {
                bitmap.getPixels(scanline, 0, width, 0, y, width, 1);
                for (int x = 0; x < width; x += SAMPLE_STEP) {
                    int column = x * columns / width;
                    histograms[column][luminance(scanline[x]) >> 2]++;
                    samples[column]++;
                }
            }
            for (int column = 0; column < columns; column++) {
                // The 80th percentile discounts dark text and favours the paper behind it.
                int rank = (samples[column] * 4 + 4) / 5;
                int bin = 0;
                while (rank > histograms[column][bin] && bin < HISTOGRAM_BINS - 1) {
                    rank -= histograms[column][bin++];
                }
                paper[row][column] = Math.max(70, bin * 4 + 2);
            }
        }
        return paper;
    }

    private static int tileCoordinate(int position, int length, int count) {
        int centered = ((2 * position + 1) * count * 128) / length - 128;
        return Math.max(0, Math.min((count - 1) << 8, centered));
    }

    private static int interpolate(int start, int end, int weight) {
        return (start * (256 - weight) + end * weight) >> 8;
    }

    private static int luminance(int argb) {
        int red = (argb >> 16) & 255;
        int green = (argb >> 8) & 255;
        int blue = argb & 255;
        return (77 * red + 150 * green + 29 * blue + 128) >> 8;
    }
}
