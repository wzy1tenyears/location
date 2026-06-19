package com.familylocation.admin;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

public class AdminApkProvider extends ContentProvider {
    private static final String APK_NAME = "location-admin-release.apk";
    private static final String MIME_TYPE = "application/vnd.android.package-archive";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return MIME_TYPE;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) && !"rt".equals(mode)) {
            throw new FileNotFoundException("Read only");
        }
        File file = resolveApkFile(uri);
        if (!file.isFile()) {
            throw new FileNotFoundException("APK not found");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file;
        try {
            file = resolveApkFile(uri);
        } catch (FileNotFoundException exception) {
            return null;
        }
        MatrixCursor cursor = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[] {APK_NAME, file.isFile() ? file.length() : 0L});
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    private File resolveApkFile(Uri uri) throws FileNotFoundException {
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 1 || !APK_NAME.equals(segments.get(0))) {
            throw new FileNotFoundException("Invalid APK path");
        }
        File directory = apkDirectory();
        File file = new File(directory, APK_NAME);
        try {
            String basePath = directory.getCanonicalPath() + File.separator;
            String filePath = file.getCanonicalPath();
            if (!filePath.startsWith(basePath)) {
                throw new FileNotFoundException("Invalid APK file");
            }
        } catch (IOException exception) {
            throw new FileNotFoundException("Invalid APK file");
        }
        return file;
    }

    private File apkDirectory() {
        Context context = getContext();
        File directory = context == null ? null : context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory == null && context != null) {
            directory = new File(context.getFilesDir(), "updates");
        }
        if (directory != null && !directory.exists()) {
            directory.mkdirs();
        }
        return directory == null ? new File(".") : directory;
    }
}
