package com.codex.uvcrecorder;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** One shared phone microphone capture used by preview meters, recording and RTMP. */
final class PhoneAudioHub {
    private static final Object LOCK = new Object();
    private static final int SAMPLE_RATE = 48_000;
    private static final int BYTES_PER_SAMPLE = 2;
    private static Session session;

    private PhoneAudioHub() {
    }

    static PcmAudioSubscription subscribe(Context context) throws Exception {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("未授予手机麦克风权限");
        }
        String selectedKey = AppSettings.getPhoneAudioInput(context);
        Session previous = null;
        synchronized (LOCK) {
            if (session != null && session.running
                    && !session.selectionKey.equals(selectedKey)) {
                previous = session;
                session = null;
            }
        }
        if (previous != null) previous.stop();
        synchronized (LOCK) {
            if (session == null || !session.running) {
                session = Session.open(context.getApplicationContext(), selectedKey);
            }
            Session active = session;
            PcmAudioSubscription subscription = new PcmAudioSubscription(
                    SAMPLE_RATE, active.channelCount, BYTES_PER_SAMPLE,
                    active.bufferSize, active.deviceLabel, active.deviceId, active.warning,
                    item -> unsubscribe(active, item));
            active.subscriptions.add(subscription);
            return subscription;
        }
    }

    private static void unsubscribe(Session active, PcmAudioSubscription subscription) {
        boolean stop = false;
        synchronized (LOCK) {
            active.subscriptions.remove(subscription);
            if (active.subscriptions.isEmpty()) {
                if (session == active) session = null;
                stop = true;
            }
        }
        if (stop) active.stop();
    }

    static void reset() {
        Session previous;
        synchronized (LOCK) {
            previous = session;
            session = null;
            if (previous != null) {
                IllegalStateException changed = new IllegalStateException("麦克风输入已切换");
                for (PcmAudioSubscription subscription : previous.subscriptions) {
                    subscription.failure = changed;
                }
            }
        }
        if (previous != null) previous.stop();
    }

    private static final class Session {
        final AudioRecord record;
        final int channelCount;
        final int bufferSize;
        final String selectionKey;
        final String deviceLabel;
        final int deviceId;
        final String warning;
        final List<PcmAudioSubscription> subscriptions = new ArrayList<>();
        volatile boolean running = true;
        Thread thread;

        Session(AudioRecord record, int channelCount, int bufferSize,
                String selectionKey, String deviceLabel, int deviceId, String warning) {
            this.record = record;
            this.channelCount = channelCount;
            this.bufferSize = bufferSize;
            this.selectionKey = selectionKey;
            this.deviceLabel = deviceLabel;
            this.deviceId = deviceId;
            this.warning = warning;
        }

        static Session open(Context context, String selectionKey) {
            RuntimeException last = null;
            PhoneAudioInputCatalog.Input selected = PhoneAudioInputCatalog.selected(context);
            PhoneAudioInputCatalog.Input preferred = selected;
            if (selected == null || selected.automatic()) {
                for (PhoneAudioInputCatalog.Input input :
                        PhoneAudioInputCatalog.list(context)) {
                    if (PhoneAudioInputCatalog.isExternal(input.type)) {
                        preferred = input;
                        break;
                    }
                }
            }
            int[] channels = {2, 1};
            for (int count : channels) {
                int mask = count == 2 ? AudioFormat.CHANNEL_IN_STEREO
                        : AudioFormat.CHANNEL_IN_MONO;
                int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, mask,
                        AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) continue;
                int buffer = Math.max(minimum * 2,
                        SAMPLE_RATE * count * BYTES_PER_SAMPLE / 5);
                AudioRecord candidate = null;
                try {
                    candidate = new AudioRecord.Builder()
                            .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setSampleRate(SAMPLE_RATE)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setChannelMask(mask)
                                    .build())
                            .setBufferSizeInBytes(buffer)
                            .build();
                    String routeWarning = null;
                    if (preferred != null && preferred.device != null
                            && !candidate.setPreferredDevice(preferred.device)) {
                        routeWarning = "系统未接受所选麦克风，已使用实际路由";
                    }
                    if (candidate.getState() != AudioRecord.STATE_INITIALIZED) {
                        throw new IllegalStateException("手机麦克风初始化失败");
                    }
                    candidate.startRecording();
                    if (candidate.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                        throw new IllegalStateException("手机麦克风无法开始采集");
                    }
                    AudioDeviceInfo routed = candidate.getRoutedDevice();
                    String routeLabel = routed == null
                            ? preferred == null ? "手机麦克风" : preferred.label
                            : label(routed);
                    int routedId = routed == null
                            ? preferred == null ? -2 : preferred.id : routed.getId();
                    if (preferred != null && preferred.device != null && routed != null
                            && routed.getId() != preferred.id) {
                        routeWarning = "所选麦克风暂不可用，实际使用：" + routeLabel;
                    }
                    Session result = new Session(candidate, count, buffer,
                            selectionKey, routeLabel, routedId, routeWarning);
                    result.start();
                    return result;
                } catch (RuntimeException error) {
                    last = error;
                    if (candidate != null) candidate.release();
                }
            }
            throw last == null ? new IllegalStateException("手机不支持 48 kHz PCM 录音") : last;
        }

        private static String label(AudioDeviceInfo device) {
            String product = device.getProductName() == null ? ""
                    : device.getProductName().toString().trim();
            return product.isEmpty() ? "麦克风输入 " + device.getId() : product;
        }

        void start() {
            thread = new Thread(this::capture, "phone-microphone-shared-capture");
            thread.start();
        }

        void capture() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            byte[] buffer = new byte[Math.min(Math.max(4096, bufferSize / 4), 32 * 1024)];
            Throwable failure = null;
            try {
                while (running) {
                    int read = record.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                    if (read > 0) {
                        byte[] pcm = Arrays.copyOf(buffer, read);
                        synchronized (LOCK) {
                            for (PcmAudioSubscription subscription : subscriptions) {
                                subscription.offer(pcm);
                            }
                        }
                    } else if (read < 0 && running) {
                        throw new IllegalStateException("手机麦克风读取失败：" + read);
                    }
                }
            } catch (Throwable error) {
                if (running) failure = error;
            } finally {
                running = false;
                try {
                    record.stop();
                } catch (Throwable ignored) {
                }
                record.release();
                synchronized (LOCK) {
                    if (session == this) session = null;
                    if (failure != null) {
                        for (PcmAudioSubscription subscription : subscriptions) {
                            subscription.failure = failure;
                        }
                    }
                }
            }
        }

        void stop() {
            running = false;
            try {
                record.stop();
            } catch (Throwable ignored) {
            }
            if (thread != null && thread != Thread.currentThread()) {
                try {
                    thread.join(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
