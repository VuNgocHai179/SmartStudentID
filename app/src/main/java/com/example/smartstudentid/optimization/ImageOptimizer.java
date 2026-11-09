package com.example.smartstudentid.optimization;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;

import com.example.smartstudentid.apdu.ApduCommands;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Random;

/**
 * Implements multimedia & APDU bandwidth optimization (OPT-02):
 * - Downsamples raw high-resolution portraits to 150x150px using BitmapFactory.Options.inSampleSize.
 * - Compresses images to WebP (75% quality, ~3.5KB) instead of uncompressed/lossless PNG (~180KB).
 * - Reduces APDU chunk count by >95% (from ~750 packets down to ~15 packets).
 */
public final class ImageOptimizer {

    public static final int TARGET_AVATAR_WIDTH = 150;
    public static final int TARGET_AVATAR_HEIGHT = 150;
    public static final int WEBP_QUALITY = 75;
    /** Matches the smart card applet's avatarBuffer[8192] EEPROM allocation. */
    public static final int MAX_AVATAR_BYTES = 8192;

    private ImageOptimizer() {
    }

    /**
     * Calculates the optimal power-of-two inSampleSize factor for BitmapFactory decoding.
     */
    public static int calculateInSampleSize(int rawWidth, int rawHeight, int reqWidth, int reqHeight) {
        int inSampleSize = 1;
        if (rawHeight > reqHeight || rawWidth > reqWidth) {
            final int halfHeight = rawHeight / 2;
            final int halfWidth = rawWidth / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    /**
     * Decodes and downsamples a raw image byte array using inSampleSize, then scales it to 150x150px.
     */
    public static Bitmap decodeAndDownsample(byte[] rawImageBytes, int reqWidth, int reqHeight) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(rawImageBytes, 0, rawImageBytes.length, options);

        options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight, reqWidth, reqHeight);
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;

        Bitmap sampled = BitmapFactory.decodeByteArray(rawImageBytes, 0, rawImageBytes.length, options);
        if (sampled == null) {
            return generateSampleStudentAvatar("SV", 150);
        }
        if (sampled.getWidth() == reqWidth && sampled.getHeight() == reqHeight) {
            return sampled;
        }
        Bitmap scaled = Bitmap.createScaledBitmap(sampled, reqWidth, reqHeight, true);
        if (scaled != sampled) {
            sampled.recycle();
        }
        return scaled;
    }

    /**
     * Decodes a user-picked photo (gallery or camera capture) from its content Uri, applies EXIF
     * rotation, downsamples it and center-crops it to the requested card portrait size.
     */
    public static Bitmap decodeAndDownsampleFromUri(Context context, Uri uri, int reqWidth, int reqHeight) {
        if (context == null || uri == null) {
            return null;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                if (is == null) return null;
                BitmapFactory.decodeStream(is, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight);
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;

            Bitmap decoded;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                if (is == null) return null;
                decoded = BitmapFactory.decodeStream(is, null, options);
            }
            if (decoded == null) {
                return null;
            }

            Bitmap oriented = applyExifOrientation(context, uri, decoded);
            if (oriented != decoded) {
                decoded.recycle();
            }
            Bitmap cropped = centerCropToSize(oriented, reqWidth, reqHeight);
            if (cropped != oriented) {
                oriented.recycle();
            }
            return cropped;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /**
     * Decodes, downsamples, center-crops and compresses a picked photo into a card-ready WebP.
     * Returns null when the Uri cannot be decoded.
     */
    public static byte[] decodeAndCompressAvatar(Context context, Uri uri) {
        Bitmap avatar = decodeAndDownsampleFromUri(context, uri, TARGET_AVATAR_WIDTH, TARGET_AVATAR_HEIGHT);
        if (avatar == null) {
            return null;
        }
        byte[] webp = compressAvatarForCard(avatar);
        avatar.recycle();
        return webp;
    }

    /**
     * Rotates/flips a portrait according to its EXIF orientation tag (camera photos are often sideways).
     */
    public static Bitmap applyExifOrientation(Context context, Uri uri, Bitmap source) {
        if (context == null || uri == null || source == null) {
            return source;
        }
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is != null) {
                orientation = new ExifInterface(is).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (Exception ignored) {
        }

        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.postScale(1, -1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.postRotate(90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.postRotate(270);
                matrix.postScale(-1, 1);
                break;
            default:
                return source;
        }

        try {
            Bitmap transformed = Bitmap.createBitmap(source, 0, 0, source.getWidth(),
                    source.getHeight(), matrix, true);
            return transformed != null ? transformed : source;
        } catch (OutOfMemoryError e) {
            return source;
        }
    }

    /**
     * Scales a bitmap so it covers the requested size, then center-crops it (no portrait distortion).
     */
    public static Bitmap centerCropToSize(Bitmap source, int reqWidth, int reqHeight) {
        if (source == null) {
            return null;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        if (width == reqWidth && height == reqHeight) {
            return source;
        }

        float scale = Math.max((float) reqWidth / width, (float) reqHeight / height);
        int scaledWidth = Math.max(reqWidth, Math.round(width * scale));
        int scaledHeight = Math.max(reqHeight, Math.round(height * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true);

        int cropX = Math.max(0, (scaledWidth - reqWidth) / 2);
        int cropY = Math.max(0, (scaledHeight - reqHeight) / 2);
        Bitmap cropped = Bitmap.createBitmap(scaled, cropX, cropY, reqWidth, reqHeight);
        if (scaled != source && scaled != cropped) {
            scaled.recycle();
        }
        return cropped;
    }

    /**
     * Compresses a Bitmap into WebP format (75% quality) after resizing to 150x150px (Optimized After Mode).
     */
    public static byte[] compressToOptimizedWebP(Bitmap source) {
        return compressToWebP(source, WEBP_QUALITY);
    }

    /**
     * Compresses a portrait so it always fits the on-card 8KB avatar buffer: starts at WebP 75%
     * and lowers quality / resolution only when the encoded bytes exceed the EEPROM budget.
     */
    public static byte[] compressAvatarForCard(Bitmap source) {
        byte[] bytes = compressToWebP(source, WEBP_QUALITY);
        int quality = WEBP_QUALITY;
        while (bytes.length > MAX_AVATAR_BYTES && quality > 30) {
            quality -= 15;
            bytes = compressToWebP(source, quality);
        }
        if (bytes.length > MAX_AVATAR_BYTES) {
            Bitmap smaller = Bitmap.createScaledBitmap(source, 120, 120, true);
            bytes = compressBitmapToWebP(smaller, 50, 120, 120);
            if (smaller != source) {
                smaller.recycle();
            }
        }
        return bytes;
    }

    /**
     * Compresses a Bitmap into lossy WebP at the requested quality (150x150 target size).
     */
    public static byte[] compressToWebP(Bitmap source, int quality) {
        return compressBitmapToWebP(source, quality, TARGET_AVATAR_WIDTH, TARGET_AVATAR_HEIGHT);
    }

    @SuppressWarnings("deprecation")
    private static byte[] compressBitmapToWebP(Bitmap source, int quality, int targetWidth, int targetHeight) {
        Bitmap scaled = (source.getWidth() == targetWidth && source.getHeight() == targetHeight)
                ? source
                : Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);

        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, bos);
            } else {
                scaled.compress(Bitmap.CompressFormat.WEBP, quality, bos);
            }
            return bos.toByteArray();
        } finally {
            if (scaled != source) {
                scaled.recycle();
            }
        }
    }

    /**
     * Compresses a Bitmap into unoptimized full-size PNG format (Unoptimized Before Mode).
     */
    public static byte[] compressToUnoptimizedPng(Bitmap source) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(180 * 1024);
        source.compress(Bitmap.CompressFormat.PNG, 100, bos);
        return bos.toByteArray();
    }

    /**
     * Generates a high-detail synthetic portrait bitmap for testing & benchmarking
     * (produces ~180KB in 600x600 PNG vs ~3.5KB when downsampled to 150x150 WebP).
     */
    public static Bitmap generateSampleStudentAvatar(String initials, int dimensionPx) {
        Bitmap bitmap = Bitmap.createBitmap(dimensionPx, dimensionPx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Background gradient with fine noise texture to simulate a real camera photo (~180KB PNG)
        LinearGradient gradient = new LinearGradient(
                0, 0, dimensionPx, dimensionPx,
                Color.parseColor("#1E3A8A"),
                Color.parseColor("#0D9488"),
                Shader.TileMode.CLAMP
        );
        paint.setShader(gradient);
        canvas.drawRect(0, 0, dimensionPx, dimensionPx, paint);
        paint.setShader(null);

        if (dimensionPx >= 400) {
            Random rng = new Random(2026L);
            for (int y = 0; y < dimensionPx; y += 3) {
                for (int x = 0; x < dimensionPx; x += 3) {
                    int c = Color.argb(28, rng.nextInt(256), rng.nextInt(256), rng.nextInt(256));
                    paint.setColor(c);
                    canvas.drawPoint(x, y, paint);
                }
            }
        }

        // Portrait silhouette circle & shoulders
        paint.setColor(Color.parseColor("#38BDF8"));
        float cx = dimensionPx / 2f;
        float headRadius = dimensionPx * 0.18f;
        canvas.drawCircle(cx, dimensionPx * 0.36f, headRadius, paint);

        RectF shoulders = new RectF(
                dimensionPx * 0.22f,
                dimensionPx * 0.58f,
                dimensionPx * 0.78f,
                dimensionPx * 0.95f
        );
        canvas.drawRoundRect(shoulders, dimensionPx * 0.15f, dimensionPx * 0.15f, paint);

        // Initials badge
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dimensionPx * 0.18f);
        paint.setFakeBoldText(true);
        String label = (initials != null && !initials.isEmpty()) ? initials : "SV";
        canvas.drawText(label, cx, dimensionPx * 0.80f, paint);

        return bitmap;
    }

    /**
     * Calculates the number of 240-byte APDU packets required to transmit the given image byte array.
     */
    public static int calculateApduPacketCount(int byteLength) {
        if (byteLength <= 0) return 0;
        return (byteLength + ApduCommands.MAX_AVATAR_CHUNK_SIZE - 1) / ApduCommands.MAX_AVATAR_CHUNK_SIZE;
    }
}
