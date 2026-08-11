package com.codex.uvcrecorder;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.IOException;

public final class RecordingTarget {
    public final String displayName;
    public final Uri uri;
    public final ParcelFileDescriptor descriptor;
    public final AppSettings.Container container;
    private final boolean mediaStorePending;
    private final File localFile;

    RecordingTarget(String displayName, Uri uri, ParcelFileDescriptor descriptor,
                    AppSettings.Container container, boolean mediaStorePending, File localFile) {
        this.displayName = displayName;
        this.uri = uri;
        this.descriptor = descriptor;
        this.container = container;
        this.mediaStorePending = mediaStorePending;
        this.localFile = localFile;
    }

    public void complete(Context context) {
        closeDescriptor();
        if (mediaStorePending && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);
        }
    }

    public void cancel(Context context) {
        closeDescriptor();
        try {
            if (localFile != null) {
                //noinspection ResultOfMethodCallIgnored
                localFile.delete();
            } else if ("content".equals(uri.getScheme())) {
                DocumentFile document = DocumentFile.fromSingleUri(context, uri);
                if (document == null || !document.delete()) {
                    context.getContentResolver().delete(uri, null, null);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void closeDescriptor() {
        try {
            descriptor.close();
        } catch (IOException ignored) {
        }
    }
}
