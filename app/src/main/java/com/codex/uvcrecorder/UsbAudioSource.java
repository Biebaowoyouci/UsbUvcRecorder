package com.codex.uvcrecorder;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Opens a PCM capture stream that is explicitly routed to a USB UAC input. */
final class UsbAudioSource {
    private static final String TAG = "UsbAudioSource";
    static final int SAMPLE_RATE = 48_000;
    static final int BYTES_PER_SAMPLE = 2;

    final AudioRecord record;
    final int channelCount;
    final int bufferSize;
    final String deviceName;
    final int deviceId;
    final String warning;
    private final AudioDeviceInfo requestedDevice;
    private final int audioSource;

    private UsbAudioSource(AudioRecord record, int channelCount, int bufferSize,
                           AudioDeviceInfo requestedDevice, int audioSource, String warning) {
        this.record = record;
        this.channelCount = channelCount;
        this.bufferSize = bufferSize;
        this.requestedDevice = requestedDevice;
        this.audioSource = audioSource;
        CharSequence name = requestedDevice.getProductName();
        this.deviceName = name == null ? "USB UAC" : name.toString();
        this.deviceId = requestedDevice.getId();
        this.warning = warning;
    }

    static UsbAudioSource open(Context context) throws Exception {
        return open(context, null, -1);
    }

    static UsbAudioSource open(Context context, String preferredProduct) throws Exception {
        return open(context, preferredProduct, -1);
    }

    static UsbAudioSource open(Context context, String preferredProduct,
                               int preferredDeviceId) throws Exception {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("未授予录音权限");
        }
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) throw new IllegalStateException("系统音频服务不可用");

        List<AudioDeviceInfo> usbInputs = listUsbInputs(context);
        AudioDeviceInfo usbInput = chooseInput(
                usbInputs, preferredProduct, preferredDeviceId);
        if (usbInput == null) {
            throw new IllegalStateException("未检测到 USB UAC 音频输入；请确认采集卡已启用 HDMI 嵌入音频");
        }

        boolean matched = (preferredDeviceId >= 0 && usbInput.getId() == preferredDeviceId)
                || preferredProduct == null || preferredProduct.trim().isEmpty()
                || matchScore(usbInput, preferredProduct) >= 500;
        String warning = matched ? null : "系统没有列出与 " + preferredProduct
                + " 对应的独立 UAC，已使用 " + usbInput.getProductName();

        Exception last = null;
        int[] audioSources = {MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.DEFAULT};
        int[] channelCounts = {2, 1};
        for (int source : audioSources) {
            for (int channels : channelCounts) {
                int mask = channels == 2 ? AudioFormat.CHANNEL_IN_STEREO
                        : AudioFormat.CHANNEL_IN_MONO;
                int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, mask,
                        AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) continue;
                int buffer = Math.max(minimum * 2,
                        SAMPLE_RATE * channels * BYTES_PER_SAMPLE / 5);
                AudioFormat format = new AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(mask)
                        .build();
                AudioRecord candidate = null;
                try {
                    candidate = new AudioRecord.Builder()
                            .setAudioSource(source)
                            .setAudioFormat(format)
                            .setBufferSizeInBytes(buffer)
                            .build();
                    if (candidate.getState() != AudioRecord.STATE_INITIALIZED) {
                        throw new IllegalStateException("UAC AudioRecord 初始化失败");
                    }
                    if (!candidate.setPreferredDevice(usbInput)) {
                        throw new IllegalStateException("无法将音频输入路由到 USB UAC 设备");
                    }
                    UsbAudioSource result = new UsbAudioSource(candidate, channels, buffer,
                            usbInput, source, warning);
                    result.start();
                    return result;
                } catch (Exception error) {
                    last = error;
                    if (candidate != null) candidate.release();
                    Log.w(TAG, "UAC open failed source=" + source + " channels=" + channels,
                            error);
                }
            }
        }
        throw last == null ? new IllegalStateException("UAC 音频不支持 48 kHz PCM") : last;
    }

    void start() {
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            record.startRecording();
        }
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            throw new IllegalStateException("USB UAC 音频无法开始采集");
        }
        AudioDeviceInfo routed = null;
        long deadline = SystemClock.elapsedRealtime() + 1_000;
        do {
            routed = record.getRoutedDevice();
            if (routed != null) break;
            SystemClock.sleep(25);
        } while (SystemClock.elapsedRealtime() < deadline);
        if (routed == null || !isUsbInput(routed)) {
            throw new IllegalStateException("录音未路由到 USB UAC，已阻止误录手机麦克风");
        }
        if (routed.getId() != requestedDevice.getId()) {
            throw new IllegalStateException("UAC 路由到了错误的 USB 音频设备");
        }
        Log.i(TAG, "routed id=" + routed.getId() + " name=" + routed.getProductName()
                + " source=" + sourceName(audioSource) + " channels=" + channelCount
                + " rate=" + SAMPLE_RATE);
    }

    void stop() {
        try {
            if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop();
        } catch (Exception ignored) {
        }
    }

    void release() {
        stop();
        record.release();
    }

    static List<AudioDeviceInfo> listUsbInputs(Context context) {
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        List<AudioDeviceInfo> result = new ArrayList<>();
        if (manager == null) return result;
        for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (!isUsbInput(device)) continue;
            result.add(device);
            Log.i(TAG, "UAC input id=" + device.getId() + " type=" + device.getType()
                    + " name=" + device.getProductName());
        }
        return result;
    }

    private static AudioDeviceInfo chooseInput(List<AudioDeviceInfo> inputs,
                                                String preferredProduct,
                                                int preferredDeviceId) {
        if (preferredDeviceId >= 0) {
            for (AudioDeviceInfo device : inputs) {
                if (device.getId() == preferredDeviceId) return device;
            }
        }
        AudioDeviceInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AudioDeviceInfo device : inputs) {
            int score = matchScore(device, preferredProduct);
            if (score > bestScore) {
                best = device;
                bestScore = score;
            }
        }
        return best;
    }

    static int matchScore(AudioDeviceInfo device, String preferredProduct) {
        String name = normalize(device.getProductName() == null
                ? "" : device.getProductName().toString());
        String preferred = normalize(preferredProduct);
        int score = 10;
        if (!preferred.isEmpty()) {
            if (name.equals(preferred)) score += 1_000;
            else if (name.contains(preferred) || preferred.contains(name)) score += 700;
            else {
                String stemName = roleNeutral(name);
                String stemPreferred = roleNeutral(preferred);
                if (!stemName.isEmpty() && !stemPreferred.isEmpty()
                        && (stemName.contains(stemPreferred)
                        || stemPreferred.contains(stemName))) score += 500;
            }
        }
        if (name.contains("digital") || name.contains("hdmi")) score += 30;
        if (name.contains("analog")) score -= 5;
        return score;
    }

    private static String roleNeutral(String value) {
        return value.replace("audio", "").replace("video", "")
                .replace("analog", "").replace("digital", "")
                .replace("capture", "").replace("playback", "")
                .replace("device", "").replace("usb", "");
    }

    private static String normalize(CharSequence value) {
        if (value == null) return "";
        return value.toString().toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "");
    }

    private static boolean isUsbInput(AudioDeviceInfo device) {
        int type = device.getType();
        return type == AudioDeviceInfo.TYPE_USB_DEVICE
                || type == AudioDeviceInfo.TYPE_USB_HEADSET
                || type == AudioDeviceInfo.TYPE_USB_ACCESSORY;
    }

    private static String sourceName(int source) {
        return source == MediaRecorder.AudioSource.UNPROCESSED ? "UNPROCESSED" : "DEFAULT";
    }
}
