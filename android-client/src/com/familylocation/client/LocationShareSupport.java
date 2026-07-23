package com.familylocation.client;

import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

final class LocationShareSupport {
    private static final String IMAGE_DIRECTORY = "location-share";
    private static final String IMAGE_PREFIX = "shared-location-map-";
    private static final String IMAGE_SUFFIX = ".png";
    private static final long STALE_IMAGE_AGE_MS = 24L * 60L * 60L * 1000L;

    private LocationShareSupport() {
    }

    static File imageDirectory(Context context) {
        File directory = new File(context.getCacheDir(), IMAGE_DIRECTORY);
        if (!directory.exists() && !directory.mkdirs()) {
            return context.getCacheDir();
        }
        return directory;
    }

    static Uri writeMapImage(Context context, Bitmap bitmap) throws IOException {
        File directory = imageDirectory(context);
        deleteStaleImages(directory, System.currentTimeMillis());
        String name = IMAGE_PREFIX + System.currentTimeMillis() + "-" + Long.toHexString(System.nanoTime()) + IMAGE_SUFFIX;
        File file = new File(directory, name);
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IOException("地图图片编码失败。");
            }
        }
        return new Uri.Builder()
            .scheme("content")
            .authority(authority(context))
            .appendPath(name)
            .build();
    }

    static Uri writeMapImageToGallery(Context context, Bitmap bitmap) throws IOException {
        String name = IMAGE_PREFIX + System.currentTimeMillis() + IMAGE_SUFFIX;
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/位置分享");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("无法创建系统相册图片。");
        }
        try {
            try (OutputStream output = context.getContentResolver().openOutputStream(uri, "w")) {
                if (output == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IOException("地图图片编码失败。");
                }
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Images.Media.IS_PENDING, 0);
            context.getContentResolver().update(uri, ready, null, null);
            return uri;
        } catch (Exception exception) {
            context.getContentResolver().delete(uri, null, null);
            if (exception instanceof IOException) {
                throw (IOException) exception;
            }
            throw new IOException("保存地图图片失败。", exception);
        }
    }

    static File resolveMapImage(Context context, Uri uri) throws IOException {
        if (uri == null || !authority(context).equals(uri.getAuthority()) || uri.getPathSegments().size() != 1) {
            throw new IOException("无效的地图图片地址。");
        }
        String name = uri.getLastPathSegment();
        if (!isMapImageName(name)) {
            throw new IOException("无效的地图图片名称。");
        }
        File directory = imageDirectory(context).getCanonicalFile();
        File file = new File(directory, name).getCanonicalFile();
        if (!directory.equals(file.getParentFile()) || !file.isFile()) {
            throw new IOException("地图图片不存在。");
        }
        return file;
    }

    static Intent imageIntent(Uri imageUri) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("image/png");
        intent.putExtra(Intent.EXTRA_STREAM, imageUri);
        intent.setClipData(ClipData.newRawUri("位置地图", imageUri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    static Intent linkIntent(String shareUrl, String accessCode, String expiresAt) {
        StringBuilder text = new StringBuilder();
        text.append("位置分享：").append(shareUrl);
        text.append("\n分享码：").append(accessCode);
        if (expiresAt != null && !expiresAt.trim().isEmpty()) {
            text.append("\n有效期至：").append(expiresAt.trim());
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, text.toString());
        return intent;
    }

    private static boolean isMapImageName(String name) {
        return name != null
            && name.startsWith(IMAGE_PREFIX)
            && name.endsWith(IMAGE_SUFFIX)
            && name.length() > IMAGE_PREFIX.length() + IMAGE_SUFFIX.length();
    }

    private static String authority(Context context) {
        return context.getPackageName() + ".shareprovider";
    }

    private static void deleteStaleImages(File directory, long now) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isFile() && isMapImageName(file.getName()) && now - file.lastModified() > STALE_IMAGE_AGE_MS) {
                file.delete();
            }
        }
    }
}
