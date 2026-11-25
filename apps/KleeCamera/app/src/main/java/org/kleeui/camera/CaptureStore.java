package org.kleeui.camera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.media.ExifInterface;
import android.media.Image;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Writes captures to the device's DCIM/KleeCamera album. */
public final class CaptureStore {
    private static final String ALBUM = Environment.DIRECTORY_DCIM + "/KleeCamera";
    private static final DateTimeFormatter FILENAME_TIME =
            DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss_SSS", Locale.ROOT);
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private final ContentResolver resolver;
    private final File cacheDirectory;
    private final CaptureRequestResult captureRequest;

    public CaptureStore(Context context) {
        this(context, null);
    }

    CaptureStore(Context context, CaptureRequestResult captureRequest) {
        resolver = context.getContentResolver();
        cacheDirectory = context.getCacheDir();
        this.captureRequest = captureRequest;
    }

    /** The caller retains ownership of the image. */
    public Uri saveJpeg(Image image) throws IOException {
        if (image.getFormat() != ImageFormat.JPEG) {
            throw new IOException("Expected a JPEG image");
        }
        ByteBuffer buffer = image.getPlanes()[0].getBuffer().duplicate();
        if (!buffer.hasRemaining()) {
            throw new IOException("JPEG buffer is empty");
        }
        return writeImage("jpg", "image/jpeg", output -> {
            byte[] chunk = new byte[Math.min(buffer.remaining(), COPY_BUFFER_SIZE)];
            while (buffer.hasRemaining()) {
                int size = Math.min(buffer.remaining(), chunk.length);
                buffer.get(chunk, 0, size);
                output.write(chunk, 0, size);
            }
        });
    }

    public Uri saveJpeg(byte[] data) throws IOException {
        if (data.length == 0) {
            throw new IOException("JPEG is empty");
        }
        if (captureRequest != null && !captureRequest.video && captureRequest.output == null) {
            captureRequest.setThumbnail(data);
            return null;
        }
        return writeImage("jpg", "image/jpeg", output -> output.write(data));
    }

    public Uri saveDng(CameraCharacteristics characteristics, TotalCaptureResult result,
                       Image image) throws IOException {
        return saveDng(characteristics, result, image, ExifInterface.ORIENTATION_NORMAL);
    }

    /** The orientation uses EXIF values. The caller retains ownership of the image. */
    public Uri saveDng(CameraCharacteristics characteristics, TotalCaptureResult result,
                       Image image, int exifOrientation) throws IOException {
        if (image.getFormat() != ImageFormat.RAW_SENSOR) {
            throw new IOException("Expected a RAW_SENSOR image");
        }
        return writeImage("dng", "image/x-adobe-dng", output -> {
            try (DngCreator creator = new DngCreator(characteristics, result)) {
                creator.setOrientation(exifOrientation);
                creator.writeImage(output, image);
            }
        });
    }

    public File newVideoFile() throws IOException {
        if (cacheDirectory == null) {
            throw new IOException("App cache directory is unavailable");
        }
        return File.createTempFile("klee-recording-", ".mp4", cacheDirectory);
    }

    /** Copies a finished recording. The caller retains ownership of the temporary file. */
    public Uri publishVideo(File source) throws IOException {
        if (!source.isFile() || source.length() == 0) {
            throw new IOException("Recording is missing or empty");
        }
        Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        return writeMedia(collection, "VID", "mp4", "video/mp4",
                output -> Files.copy(source.toPath(), output));
    }

    private Uri writeImage(String extension, String mimeType, MediaWriter writer) throws IOException {
        Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        return writeMedia(collection, "IMG", extension, mimeType, writer);
    }

    private Uri writeMedia(Uri collection, String prefix, String extension, String mimeType,
                           MediaWriter writer) throws IOException {
        if (captureRequest != null && captureRequest.output != null) {
            writeCapture(captureRequest.output, writer);
            return captureRequest.output;
        }
        Instant now = Instant.now();
        String timestamp = FILENAME_TIME.format(now.atZone(ZoneId.systemDefault()));
        ContentValues metadata = new ContentValues();
        metadata.put(MediaStore.MediaColumns.DISPLAY_NAME, prefix + "_" + timestamp + "." + extension);
        metadata.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        metadata.put(MediaStore.MediaColumns.RELATIVE_PATH, ALBUM);
        metadata.put(MediaStore.MediaColumns.DATE_TAKEN, now.toEpochMilli());
        metadata.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri item = null;
        try {
            item = resolver.insert(collection, metadata);
            if (item == null) {
                throw new IOException("MediaStore could not create a capture");
            }
            writeCapture(item, writer);

            ContentValues published = new ContentValues();
            published.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(item, published, null, null) != 1) {
                throw new IOException("MediaStore could not publish the capture");
            }
            return item;
        } catch (IOException | RuntimeException error) {
            IOException failure = error instanceof IOException ? (IOException) error
                    : new IOException("Could not save the capture", error);
            if (item != null) {
                try {
                    resolver.delete(item, null, null);
                } catch (RuntimeException cleanupError) {
                    failure.addSuppressed(cleanupError);
                }
            }
            throw failure;
        }
    }

    private void writeCapture(Uri uri, MediaWriter writer) throws IOException {
        try (OutputStream output = resolver.openOutputStream(uri, "wt")) {
            if (output == null) throw new IOException("Could not open the capture for writing");
            writer.write(output);
        }
    }

    @FunctionalInterface
    private interface MediaWriter {
        void write(OutputStream output) throws IOException;
    }
}
