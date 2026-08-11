package com.codex.uvcrecorder;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class RecordingListAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final Context context;
    private final List<RecordingEntry> entries = new ArrayList<>();

    RecordingListAdapter(Context context) {
        this.context = context;
        inflater = LayoutInflater.from(context);
    }

    void replace(List<RecordingEntry> values) {
        entries.clear();
        entries.addAll(values);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return entries.size();
    }

    @Override
    public RecordingEntry getItem(int position) {
        return entries.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_recording, parent, false);
            holder = new Holder();
            holder.badge = convertView.findViewById(R.id.channel_badge);
            holder.name = convertView.findViewById(R.id.file_name);
            holder.meta = convertView.findViewById(R.id.file_meta);
            convertView.setTag(holder);
        } else {
            holder = (Holder) convertView.getTag();
        }
        RecordingEntry entry = getItem(position);
        boolean auxiliary = entry.channel == RecordingEntry.Channel.AUX;
        holder.badge.setText(auxiliary ? R.string.aux_channel : R.string.main_channel);
        holder.badge.setTextColor(Color.parseColor(auxiliary ? "#FB8C00" : "#4FC3F7"));
        holder.name.setText(entry.name);
        String date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                .format(new Date(entry.modifiedMillis));
        holder.meta.setText(context.getString(R.string.recording_meta, humanSize(entry.size), date));
        return convertView;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb);
        return String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0);
    }

    private static final class Holder {
        TextView badge;
        TextView name;
        TextView meta;
    }
}
