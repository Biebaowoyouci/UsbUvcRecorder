package com.codex.uvcrecorder;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Shares the phone microphone between preview meters and one or more camera recordings. */
final class MicrophoneAudioHub {
    private static final Object LOCK = new Object();
    private static final int SAMPLE_RATE = 48_000;
    private static Session session;

    private MicrophoneAudioHub() {
    }

    static PcmAudioSubscription subscribe(Context context) throws Exception {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("未授予手机麦克风权限");
        }
        synchronized (LOCK) {
            if (session == null) {
                session = new Session();
                session.start();
            }
            Session active = session;
            PcmAudioSubscription subscription = new PcmAudioSubscription(
                    SAMPLE_RATE, 1, 2, active.bufferSize,
                    "手机麦克风", -1, null, item -> unsubscribe(active, item));
            active.subscriptions.add(subscription);
            return subscription;
        }
    }

    private static void unsubscribe(Session owner, PcmAudioSubscription subscription) {
        boolean stop = false;
        synchronized (LOCK) {
            owner.subscriptions.remove(subscription);
            if (owner.subscriptions.isEmpty() && session == owner) {
                session = null;
                stop = true;
            }
        }
        if (stop) owner.stop();
    }

    private static final class Session {
        final List<PcmAudioSubscription> subscriptions = new ArrayList<>();
        final int bufferSize;
        final AudioRecord record;
        volatile boolean running = true;
        Thread thread;

        Session() {
            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            bufferSize = Math.max(16 * 1024, minimum * 2);
            record = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(bufferSize)
                    .build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release();
                throw new IllegalStateException("手机麦克风初始化失败");
            }
        }

        void start() {
            record.startRecording();
            thread = new Thread(this::capture, "phone-microphone-shared-capture");
            thread.start();
        }

        void capture() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            byte[] buffer = new byte[Math.min(bufferSize, 32 * 1024)];
            Throwable failure = null;
            try {
                while (running) {
                    int read = record.read(buffer, 0, buffer.length,
                            AudioRecord.READ_BLOCKING);
                    if (read > 0) {
                        byte[] sample = Arrays.copyOf(buffer, read);
                        synchronized (LOCK) {
                            for (PcmAudioSubscription subscription : subscriptions) {
                                subscription.offer(sample);
                            }
                        }
                    } else if (read < 0 && running) {
                        throw new IllegalStateException("手机麦克风读取失败：" + read);
                    }
                }
            } catch (Throwable error) {
                if (running) failure = error;
            } finally {
                try { record.stop(); } catch (Throwable ignored) { }
                record.release();
                if (failure != null) {
                    synchronized (LOCK) {
                        for (PcmAudioSubscription subscription : subscriptions) {
                            subscription.failure = failure;
                        }
                    }
                }
            }
        }

        void stop() {
            running = false;
            try { record.stop(); } catch (Throwable ignored) { }
            if (thread != null && thread != Thread.currentThread()) {
                try { thread.join(500); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
