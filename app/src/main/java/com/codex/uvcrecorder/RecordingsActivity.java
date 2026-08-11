package com.codex.uvcrecorder;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RecordingsActivity extends AppCompatActivity {
    private enum Filter { ALL, MAIN, AUX }

    private final List<RecordingEntry> allEntries = new ArrayList<>();
    private RecordingListAdapter adapter;
    private Filter filter = Filter.ALL;
    private Button allButton;
    private Button mainButton;
    private Button auxButton;
    private TextView emptyView;
    private final ExecutorService fileLoader = Executors.newSingleThreadExecutor();
    private int loadGeneration;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recordings);
        SystemBarInsets.apply(this, findViewById(R.id.recordings_root));
        findViewById(R.id.back_button).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.storage_value)).setText(AppSettings.getStorageLabel(this));

        allButton = findViewById(R.id.filter_all);
        mainButton = findViewById(R.id.filter_main);
        auxButton = findViewById(R.id.filter_aux);
        allButton.setOnClickListener(v -> setFilter(Filter.ALL));
        mainButton.setOnClickListener(v -> setFilter(Filter.MAIN));
        auxButton.setOnClickListener(v -> setFilter(Filter.AUX));

        ListView list = findViewById(R.id.recording_list);
        emptyView = findViewById(R.id.empty_view);
        list.setEmptyView(emptyView);
        adapter = new RecordingListAdapter(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> open(adapter.getItem(position)));
        updateFilterButtons();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ButtonAppearance.apply(findViewById(R.id.recordings_root),
                AppSettings.getButtonOpacity(this));
        loadFilesAsync();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        loadGeneration++;
        fileLoader.shutdownNow();
        super.onDestroy();
    }

    private void loadFilesAsync() {
        int request = ++loadGeneration;
        emptyView.setText(R.string.loading_recordings);
        fileLoader.execute(() -> {
            List<RecordingEntry> entries = null;
            Throwable failure = null;
            try {
                entries = FileRepository.list(getApplicationContext());
            } catch (Throwable error) {
                failure = error;
            }
            List<RecordingEntry> result = entries;
            Throwable error = failure;
            runOnUiThread(() -> {
                if (destroyed || request != loadGeneration) return;
                emptyView.setText(R.string.empty_recordings);
                if (error != null) {
                    Toast.makeText(this, getString(R.string.load_recordings_failed,
                            readable(error)), Toast.LENGTH_LONG).show();
                    return;
                }
                allEntries.clear();
                if (result != null) allEntries.addAll(result);
                applyFilter();
            });
        });
    }

    private void setFilter(Filter value) {
        filter = value;
        updateFilterButtons();
        applyFilter();
    }

    private void updateFilterButtons() {
        allButton.setAlpha(filter == Filter.ALL ? 1f : 0.55f);
        mainButton.setAlpha(filter == Filter.MAIN ? 1f : 0.55f);
        auxButton.setAlpha(filter == Filter.AUX ? 1f : 0.55f);
    }

    private void applyFilter() {
        List<RecordingEntry> filtered = new ArrayList<>();
        for (RecordingEntry entry : allEntries) {
            if (filter == Filter.ALL
                    || (filter == Filter.MAIN && entry.channel == RecordingEntry.Channel.MAIN)
                    || (filter == Filter.AUX && entry.channel == RecordingEntry.Channel.AUX)) {
                filtered.add(entry);
            }
        }
        adapter.replace(filtered);
    }

    private void open(RecordingEntry entry) {
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(entry.uri, entry.mimeType == null ? "video/*" : entry.mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "没有可播放该文件的应用", Toast.LENGTH_LONG).show();
        }
    }

    private static String readable(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
