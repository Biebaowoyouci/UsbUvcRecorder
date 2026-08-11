package com.codex.uvcrecorder;

import android.net.Uri;

public final class RecordingEntry {
    public enum Channel { MAIN, AUX }

    public final String name;
    public final Uri uri;
    public final long size;
    public final long modifiedMillis;
    public final String mimeType;
    public final Channel channel;

    public RecordingEntry(String name, Uri uri, long size, long modifiedMillis, String mimeType) {
        this.name = name;
        this.uri = uri;
        this.size = size;
        this.modifiedMillis = modifiedMillis;
        this.mimeType = mimeType;
        this.channel = name.startsWith("AUX_") ? Channel.AUX : Channel.MAIN;
    }
}
