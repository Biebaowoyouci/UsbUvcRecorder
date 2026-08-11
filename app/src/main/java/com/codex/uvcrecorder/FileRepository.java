package com.codex.uvcrecorder;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class FileRepository {
    private static final String RELATIVE_PATH = Environment.DIRECTORY_MOVIES + "/UVC Recorder/";
    private static final List<String> VIDEO_EXTENSIONS = Arrays.asList("mp4", "mov", "m4v", "avi");

    private FileRepository() {
    }

    public static RecordingTarget createTarget(Context context, RecordingEntry.Channel channel,
                                               AppSettings.Container container) throws Exception {
        return createTarget(context, channel, container, null);
    }

    public static RecordingTarget createTarget(Context context, RecordingEntry.Channel channel,
                                               AppSettings.Container container,
                                               String deviceTag) throws Exception {
        String prefix = channel == RecordingEntry.Channel.AUX ? "AUX_" : "MAIN_";
        if (deviceTag != null && !deviceTag.trim().isEmpty()) {
            prefix += deviceTag.replaceAll("[^A-Za-z0-9_]+", "_") + "_";
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        String displayName = prefix + stamp + "." + container.extension;
        String mime = mimeFor(container);
        Uri treeUri = AppSettings.getTreeUri(context);

        if (treeUri != null) {
            DocumentFile tree = DocumentFile.fromTreeUri(context, treeUri);
            if (tree == null || !tree.canWrite()) {
                throw new FileNotFoundException("所选保存位置不可写，请在设置中重新选择");
            }
            DocumentFile file = tree.createFile(mime, displayName);
            if (file == null) throw new FileNotFoundException("无法在所选位置创建文件");
            ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(file.getUri(), "rw");
            if (pfd == null) throw new FileNotFoundException("无法打开录制文件");
            return new RecordingTarget(displayName, file.getUri(), pfd, container, false, null);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
            values.put(MediaStore.Video.Media.MIME_TYPE, mime);
            values.put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH);
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri uri = context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new FileNotFoundException("无法在手机 Movies 文件夹创建文件");
            ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "rw");
            if (pfd == null) {
                context.getContentResolver().delete(uri, null, null);
                throw new FileNotFoundException("无法打开录制文件");
            }
            return new RecordingTarget(displayName, uri, pfd, container, true, null);
        }

        File root = new File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "UVC Recorder");
        if (!root.exists() && !root.mkdirs()) throw new FileNotFoundException("无法创建录制目录");
        File file = new File(root, displayName);
        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file,
                ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_READ_WRITE);
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".files", file);
        return new RecordingTarget(displayName, uri, pfd, container, false, file);
    }

    public static List<RecordingEntry> list(Context context) {
        Uri treeUri = AppSettings.getTreeUri(context);
        List<RecordingEntry> result = treeUri == null ? listPhone(context) : listTree(context, treeUri);
        result.sort((a, b) -> Long.compare(b.modifiedMillis, a.modifiedMillis));
        return result;
    }

    private static List<RecordingEntry> listTree(Context context, Uri treeUri) {
        DocumentFile tree = DocumentFile.fromTreeUri(context, treeUri);
        if (tree == null || !tree.canRead()) return new ArrayList<>();
        List<RecordingEntry> result = new ArrayList<>();
        for (DocumentFile file : tree.listFiles()) {
            String name = file.getName();
            if (file.isFile() && isRecordingName(name)) {
                result.add(new RecordingEntry(name, file.getUri(), file.length(), file.lastModified(),
                        file.getType() == null ? "video/*" : file.getType()));
            }
        }
        return result;
    }

    private static List<RecordingEntry> listPhone(Context context) {
        List<RecordingEntry> result = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String[] projection = {
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_MODIFIED,
                    MediaStore.Video.Media.MIME_TYPE
            };
            try (Cursor cursor = context.getContentResolver().query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    MediaStore.Video.Media.RELATIVE_PATH + "=?",
                    new String[]{RELATIVE_PATH},
                    MediaStore.Video.Media.DATE_MODIFIED + " DESC")) {
                if (cursor != null) {
                    int idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                    int nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
                    int sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
                    int dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED);
                    int mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE);
                    while (cursor.moveToNext()) {
                        String name = cursor.getString(nameIndex);
                        if (!isRecordingName(name)) continue;
                        Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                cursor.getLong(idIndex));
                        result.add(new RecordingEntry(name, uri, cursor.getLong(sizeIndex),
                                cursor.getLong(dateIndex) * 1000L, cursor.getString(mimeIndex)));
                    }
                }
            }
            return result;
        }

        File root = new File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "UVC Recorder");
        File[] files = root.listFiles();
        if (files == null) return result;
        for (File file : files) {
            if (!file.isFile() || !isRecordingName(file.getName())) continue;
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".files", file);
            result.add(new RecordingEntry(file.getName(), uri, file.length(), file.lastModified(), "video/*"));
        }
        return result;
    }

    private static boolean isRecordingName(String name) {
        if (name == null || !(name.startsWith("MAIN_") || name.startsWith("AUX_"))) return false;
        int dot = name.lastIndexOf('.');
        return dot > 0 && VIDEO_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.US));
    }

    public static String mimeFor(AppSettings.Container container) {
        switch (container) {
            case AVI:
                return "video/x-msvideo";
            case MOV:
                return "video/quicktime";
            case M4V:
                return "video/x-m4v";
            case MP4:
            default:
                return "video/mp4";
        }
    }
}
