package com.codex.uvcrecorder;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Enumerates selectable phone, wired, Bluetooth and USB microphone inputs. */
final class PhoneAudioInputCatalog {
    static final String AUTO_KEY = "auto";

    private PhoneAudioInputCatalog() {
    }

    static final class Input {
        final String key;
        final int id;
        final int type;
        final String label;
        final AudioDeviceInfo device;

        Input(String key, int id, int type, String label, AudioDeviceInfo device) {
            this.key = key;
            this.id = id;
            this.type = type;
            this.label = label;
            this.device = device;
        }

        boolean automatic() {
            return device == null;
        }
    }

    static List<Input> list(Context context) {
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        List<Input> result = new ArrayList<>();
        result.add(new Input(AUTO_KEY, -1, 0, "自动选择（优先外接麦克风）", null));
        if (manager == null) return result;
        AudioDeviceInfo[] devices = manager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        if (devices == null) return result;
        List<Input> hardware = new ArrayList<>();
        for (AudioDeviceInfo device : devices) {
            if (device == null || !device.isSource()) continue;
            String product = device.getProductName() == null ? ""
                    : device.getProductName().toString().trim();
            String type = typeLabel(device.getType());
            String label = product.isEmpty() || product.equalsIgnoreCase(type)
                    ? type : product + "（" + type + "）";
            hardware.add(new Input(stableKey(device), device.getId(), device.getType(),
                    label, device));
        }
        hardware.sort(Comparator
                .comparingInt((Input input) -> rank(input.type))
                .thenComparing(input -> input.label));
        result.addAll(hardware);
        return result;
    }

    static Input selected(Context context) {
        return find(list(context), AppSettings.getPhoneAudioInput(context));
    }

    static Input find(List<Input> inputs, String key) {
        if (inputs == null || inputs.isEmpty()) return null;
        if (key != null && !key.trim().isEmpty()) {
            for (Input input : inputs) {
                if (input.key.equals(key)) return input;
            }
        }
        return inputs.get(0);
    }

    static String stableKey(AudioDeviceInfo device) {
        String product = device.getProductName() == null ? ""
                : device.getProductName().toString().trim().toLowerCase(Locale.ROOT);
        String address = device.getAddress() == null ? ""
                : device.getAddress().trim().toLowerCase(Locale.ROOT);
        return device.getType() + "|" + product + "|" + address;
    }

    static boolean isExternal(int type) {
        return type == AudioDeviceInfo.TYPE_USB_DEVICE
                || type == AudioDeviceInfo.TYPE_USB_HEADSET
                || type == AudioDeviceInfo.TYPE_USB_ACCESSORY
                || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || type == AudioDeviceInfo.TYPE_HDMI;
    }

    private static int rank(int type) {
        if (isExternal(type)) return 0;
        if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC) return 1;
        return 2;
    }

    private static String typeLabel(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return "USB 麦克风";
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return "USB 耳麦";
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                return "USB 音频";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return "有线耳麦";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "蓝牙麦克风";
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
                return "蓝牙 LE 麦克风";
            case AudioDeviceInfo.TYPE_HDMI:
                return "HDMI 音频输入";
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                return "手机麦克风";
            case AudioDeviceInfo.TYPE_TELEPHONY:
                return "通话音频";
            default:
                return "音频输入 " + type;
        }
    }
}
