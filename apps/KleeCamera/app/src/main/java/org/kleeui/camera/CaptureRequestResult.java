package org.kleeui.camera;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Process;
import android.provider.MediaStore;

import java.io.IOException;
import java.nio.ByteBuffer;

/** The output contract for an ordinary Android image or video capture request. */
final class CaptureRequestResult {
    final boolean video;
    final Uri output;
    private volatile Bitmap thumbnail;

    CaptureRequestResult(Activity activity, boolean video) {
        this.video = video;
        Intent request = activity.getIntent();
        Uri target = request.getParcelableExtra(MediaStore.EXTRA_OUTPUT);
        if (target == null && request.hasExtra(MediaStore.EXTRA_OUTPUT)) {
            throw new IllegalArgumentException("Invalid capture output");
        }
        ClipData clip = request.getClipData();
        if (target == null && clip != null && clip.getItemCount() > 0) {
            target = clip.getItemAt(0).getUri();
        }
        if (target != null && (!ContentResolver.SCHEME_CONTENT.equals(target.getScheme())
                || (request.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0
                || activity.checkUriPermission(target, Process.myPid(), Process.myUid(),
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED)) {
            throw new IllegalArgumentException("Capture output must be a writable content URI");
        }
        output = target;
    }

    void setThumbnail(byte[] jpeg) throws IOException {
        thumbnail = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(jpeg)),
                (decoder, info, source) -> {
                    int width = info.getSize().getWidth();
                    int height = info.getSize().getHeight();
                    float scale = Math.min(1f, 256f / Math.max(width, height));
                    decoder.setTargetSize(Math.max(1, Math.round(width * scale)),
                            Math.max(1, Math.round(height * scale)));
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
    }

    Intent resultIntent(Uri capture) {
        if (!video) return output == null ? new Intent().putExtra("data", thumbnail) : new Intent();
        Intent result = new Intent().setDataAndType(capture, "video/mp4");
        // The caller already owns its supplied destination. Grant read access only to our MediaStore item.
        if (output == null) {
            result.setClipData(ClipData.newRawUri("Video", capture));
            result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        return result;
    }
}
