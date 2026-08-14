package com.codex.uvcrecorder;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.net.Uri;

import androidx.annotation.Nullable;

import com.serenegiant.usb.Size;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AppSettings {
    private static final String PREFS = "recorder_settings";
    private static final String KEY_CONTAINER = "container";
    private static final String KEY_VIDEO_CODEC = "video_codec";
    private static final String KEY_USB_AUDIO = "usb_audio";
    private static final String KEY_BITRATE_MBPS = "bitrate_mbps";
    private static final String KEY_SEGMENT_MINUTES = "segment_minutes";
    private static final String KEY_DUAL = "dual_recording";
    private static final String KEY_TREE_URI = "tree_uri";
    private static final String KEY_TREE_LABEL = "tree_label";
    private static final String KEY_SIGNAL_MODE_PREFIX = "signal_mode_";
    private static final String KEY_PREFERRED_DEVICE = "preferred_uvc_device";
    private static final String KEY_MULTI_DEVICES = "multi_uvc_devices";
    private static final String KEY_BUTTON_OPACITY = "button_opacity";
    private static final String KEY_MULTI_AUDIO_SHARE = "multi_audio_share";
    private static final String KEY_MULTI_AUDIO_PRODUCT = "multi_audio_product";
    private static final String KEY_MULTI_AUDIO_DEVICE_ID = "multi_audio_device_id";
    private static final String KEY_MULTI_AUDIO_SOURCE_KEY = "multi_audio_source_key";
    private static final String KEY_MULTI_AUDIO_SOURCE_LABEL = "multi_audio_source_label";
    private static final String KEY_RTMP_ENABLED = "rtmp_enabled";
    private static final String KEY_RTMP_URL = "rtmp_url";
    private static final String KEY_RTMP_BITRATE_MBPS = "rtmp_bitrate_mbps";
    private static final String KEY_RTMP_MAX_HEIGHT = "rtmp_max_height";
    private static final String KEY_RTMP_AUDIO = "rtmp_audio";
    private static final String KEY_PULL_ENABLED = "pull_enabled";
    private static final String KEY_PULL_URL = "pull_url";
    private static final String KEY_NETWORK_INPUT_SELECTED = "network_input_selected";
    private static final String KEY_INPUT_MODE = "input_mode";
    private static final String KEY_PHONE_CAMERA_ID = "phone_camera_id";
    private static final String KEY_PHONE_CAMERA_MODE = "phone_camera_mode";
    private static final String KEY_PHONE_AUDIO_INPUT = "phone_audio_input";
    private static final String KEY_VIDEO_ROTATION_UVC = "video_rotation_uvc";
    private static final String KEY_VIDEO_ROTATION_CAMERA = "video_rotation_camera";
    private static final String KEY_VIDEO_ROTATION_NETWORK = "video_rotation_network";

    private AppSettings() {
    }

    public enum Container {
        MP4("MP4", "mp4"),
        MOV("MOV", "mov"),
        M4V("M4V", "m4v"),
        AVI("AVI", "avi");

        public final String label;
        public final String extension;

        Container(String label, String extension) {
            this.label = label;
            this.extension = extension;
        }

        public static Container from(String value) {
            try {
                return Container.valueOf(value);
            } catch (Exception ignored) {
                return MP4;
            }
        }
    }

    public enum VideoCodec {
        H264("H.264", "avc"),
        H265("H.265 / HEVC", "hevc");

        public final String label;
        public final String shortName;

        VideoCodec(String label, String shortName) {
            this.label = label;
            this.shortName = shortName;
        }

        public static VideoCodec from(String value) {
            try {
                return VideoCodec.valueOf(value);
            } catch (Exception ignored) {
                return H264;
            }
        }
    }

    public enum InputMode {
        UVC,
        CAMERA;

        static InputMode from(String value) {
            try {
                return InputMode.valueOf(value);
            } catch (Exception ignored) {
                return UVC;
            }
        }
    }

    public enum VideoRotationProfile {
        UVC,
        CAMERA,
        NETWORK
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Container getContainer(Context context) {
        return Container.from(prefs(context).getString(KEY_CONTAINER, Container.MP4.name()));
    }

    public static void setContainer(Context context, Container container) {
        prefs(context).edit().putString(KEY_CONTAINER, container.name()).apply();
    }

    public static VideoCodec getVideoCodec(Context context) {
        return VideoCodec.from(prefs(context).getString(KEY_VIDEO_CODEC, VideoCodec.H264.name()));
    }

    public static void setVideoCodec(Context context, VideoCodec codec) {
        prefs(context).edit().putString(KEY_VIDEO_CODEC, codec.name()).apply();
    }

    public static boolean isUsbAudioEnabled(Context context) {
        return prefs(context).getBoolean(KEY_USB_AUDIO, true);
    }

    public static void setUsbAudioEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_USB_AUDIO, enabled).apply();
    }

    /** Zero means select an automatic rate from resolution and frame rate. */
    public static int getBitrateMbps(Context context) {
        return prefs(context).getInt(KEY_BITRATE_MBPS, 0);
    }

    public static void setBitrateMbps(Context context, int value) {
        prefs(context).edit().putInt(KEY_BITRATE_MBPS, value).apply();
    }

    public static int getSegmentMinutes(Context context) {
        return prefs(context).getInt(KEY_SEGMENT_MINUTES, 0);
    }

    public static void setSegmentMinutes(Context context, int value) {
        prefs(context).edit().putInt(KEY_SEGMENT_MINUTES, value).apply();
    }

    public static boolean isDualEnabled(Context context) {
        return prefs(context).getBoolean(KEY_DUAL, false);
    }

    public static void setDualEnabled(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_DUAL, value).apply();
    }

    public static int getButtonOpacity(Context context) {
        return Math.max(20, Math.min(100,
                prefs(context).getInt(KEY_BUTTON_OPACITY, 65)));
    }

    public static void setButtonOpacity(Context context, int percent) {
        prefs(context).edit().putInt(KEY_BUTTON_OPACITY,
                Math.max(20, Math.min(100, percent))).apply();
    }

    /** Disabled by default: every multi-device file then uses only its own matched UAC. */
    public static boolean isMultiAudioShareEnabled(Context context) {
        return prefs(context).getBoolean(KEY_MULTI_AUDIO_SHARE, false);
    }

    public static String getMultiAudioSourceKey(Context context) {
        return prefs(context).getString(KEY_MULTI_AUDIO_SOURCE_KEY, "");
    }

    public static String getMultiAudioSourceLabel(Context context) {
        return prefs(context).getString(KEY_MULTI_AUDIO_SOURCE_LABEL, "");
    }

    public static void setMultiAudioShare(Context context, String videoDeviceKey,
                                          String videoDeviceLabel) {
        String safeKey = videoDeviceKey == null ? "" : videoDeviceKey.trim();
        String safeLabel = videoDeviceLabel == null ? "" : videoDeviceLabel.trim();
        if (safeKey.isEmpty() || safeLabel.isEmpty()) {
            disableMultiAudioShare(context);
            return;
        }
        prefs(context).edit()
                .putBoolean(KEY_MULTI_AUDIO_SHARE, true)
                .putString(KEY_MULTI_AUDIO_SOURCE_KEY, safeKey)
                .putString(KEY_MULTI_AUDIO_SOURCE_LABEL, safeLabel)
                .remove(KEY_MULTI_AUDIO_PRODUCT)
                .remove(KEY_MULTI_AUDIO_DEVICE_ID)
                .apply();
    }

    public static void disableMultiAudioShare(Context context) {
        prefs(context).edit()
                .putBoolean(KEY_MULTI_AUDIO_SHARE, false)
                .remove(KEY_MULTI_AUDIO_SOURCE_KEY)
                .remove(KEY_MULTI_AUDIO_SOURCE_LABEL)
                .remove(KEY_MULTI_AUDIO_PRODUCT)
                .remove(KEY_MULTI_AUDIO_DEVICE_ID)
                .apply();
    }

    public static boolean isRtmpEnabled(Context context) {
        return prefs(context).getBoolean(KEY_RTMP_ENABLED, false);
    }

    public static void setRtmpEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_RTMP_ENABLED, enabled).apply();
    }

    public static String getRtmpUrl(Context context) {
        return prefs(context).getString(KEY_RTMP_URL, "");
    }

    public static void setRtmpUrl(Context context, String url) {
        prefs(context).edit().putString(KEY_RTMP_URL,
                url == null ? "" : url.trim()).apply();
    }

    public static int getRtmpBitrateMbps(Context context) {
        return Math.max(2, Math.min(50,
                prefs(context).getInt(KEY_RTMP_BITRATE_MBPS, 8)));
    }

    public static void setRtmpBitrateMbps(Context context, int value) {
        prefs(context).edit().putInt(KEY_RTMP_BITRATE_MBPS,
                Math.max(2, Math.min(50, value))).apply();
    }

    /** Zero follows the input signal; other values cap the streaming height. */
    public static int getRtmpMaxHeight(Context context) {
        int value = prefs(context).getInt(KEY_RTMP_MAX_HEIGHT, 1080);
        return value == 0 || value == 720 || value == 1080 ? value : 1080;
    }

    public static void setRtmpMaxHeight(Context context, int value) {
        int safe = value == 0 || value == 720 || value == 1080 ? value : 1080;
        prefs(context).edit().putInt(KEY_RTMP_MAX_HEIGHT, safe).apply();
    }

    public static boolean isRtmpAudioEnabled(Context context) {
        return prefs(context).getBoolean(KEY_RTMP_AUDIO, true);
    }

    public static void setRtmpAudioEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_RTMP_AUDIO, enabled).apply();
    }

    public static boolean isPullEnabled(Context context) {
        return prefs(context).getBoolean(KEY_PULL_ENABLED, false);
    }

    public static void setPullEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_PULL_ENABLED, enabled).apply();
        if (!enabled) setNetworkInputSelected(context, false);
    }

    public static String getPullUrl(Context context) {
        return prefs(context).getString(KEY_PULL_URL, "");
    }

    public static void setPullUrl(Context context, String url) {
        prefs(context).edit().putString(KEY_PULL_URL,
                url == null ? "" : url.trim()).apply();
    }

    public static boolean isNetworkInputSelected(Context context) {
        return prefs(context).getBoolean(KEY_NETWORK_INPUT_SELECTED, false);
    }

    public static void setNetworkInputSelected(Context context, boolean selected) {
        prefs(context).edit().putBoolean(KEY_NETWORK_INPUT_SELECTED, selected).apply();
    }

    public static InputMode getInputMode(Context context) {
        return InputMode.from(prefs(context).getString(KEY_INPUT_MODE, InputMode.UVC.name()));
    }

    public static void setInputMode(Context context, InputMode mode) {
        InputMode safe = mode == null ? InputMode.UVC : mode;
        SharedPreferences.Editor editor = prefs(context).edit()
                .putString(KEY_INPUT_MODE, safe.name());
        if (safe == InputMode.CAMERA) {
            editor.putBoolean(KEY_NETWORK_INPUT_SELECTED, false);
        }
        editor.apply();
    }

    public static int getVideoRotation(Context context, VideoRotationProfile profile) {
        return normalizeRotation(prefs(context).getInt(rotationKey(profile), 0));
    }

    public static void setVideoRotation(Context context, VideoRotationProfile profile,
                                        int degrees) {
        prefs(context).edit().putInt(rotationKey(profile),
                normalizeRotation(degrees)).apply();
    }

    private static String rotationKey(VideoRotationProfile profile) {
        if (profile == VideoRotationProfile.CAMERA) return KEY_VIDEO_ROTATION_CAMERA;
        if (profile == VideoRotationProfile.NETWORK) return KEY_VIDEO_ROTATION_NETWORK;
        return KEY_VIDEO_ROTATION_UVC;
    }

    static int normalizeRotation(int degrees) {
        int normalized = Math.floorMod(degrees, 360);
        return normalized % 90 == 0 ? normalized : 0;
    }

    public static String getPhoneCameraId(Context context) {
        return prefs(context).getString(KEY_PHONE_CAMERA_ID, "");
    }

    public static void setPhoneCameraId(Context context, String cameraId) {
        prefs(context).edit().putString(KEY_PHONE_CAMERA_ID,
                cameraId == null ? "" : cameraId).apply();
    }

    public static String getPhoneAudioInput(Context context) {
        return prefs(context).getString(KEY_PHONE_AUDIO_INPUT,
                PhoneAudioInputCatalog.AUTO_KEY);
    }

    public static void setPhoneAudioInput(Context context, String key) {
        String safe = key == null || key.trim().isEmpty()
                ? PhoneAudioInputCatalog.AUTO_KEY : key;
        prefs(context).edit().putString(KEY_PHONE_AUDIO_INPUT, safe).apply();
    }

    public static void savePhoneCameraMode(Context context, String cameraId,
                                           int width, int height, int fps) {
        if (cameraId == null || cameraId.trim().isEmpty()) return;
        prefs(context).edit()
                .putString(KEY_PHONE_CAMERA_ID, cameraId)
                .putString(KEY_PHONE_CAMERA_MODE,
                        width + "," + height + "," + Math.max(1, fps))
                .apply();
    }

    @Nullable
    public static int[] getPhoneCameraMode(Context context) {
        String value = prefs(context).getString(KEY_PHONE_CAMERA_MODE, null);
        if (value == null) return null;
        String[] fields = value.split(",");
        if (fields.length != 3) return null;
        try {
            return new int[]{Integer.parseInt(fields[0]), Integer.parseInt(fields[1]),
                    Integer.parseInt(fields[2])};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Nullable
    public static Uri getTreeUri(Context context) {
        String value = prefs(context).getString(KEY_TREE_URI, null);
        return value == null || value.isEmpty() ? null : Uri.parse(value);
    }

    public static void setTree(Context context, Uri uri, String label) {
        prefs(context).edit()
                .putString(KEY_TREE_URI, uri.toString())
                .putString(KEY_TREE_LABEL, label)
                .apply();
    }

    public static void clearTree(Context context) {
        prefs(context).edit().remove(KEY_TREE_URI).remove(KEY_TREE_LABEL).apply();
    }

    public static String getStorageLabel(Context context) {
        Uri uri = getTreeUri(context);
        if (uri == null) {
            return context.getString(R.string.phone_storage);
        }
        return prefs(context).getString(KEY_TREE_LABEL, uri.getLastPathSegment());
    }

    public static void saveSignalMode(Context context, UsbDevice device, Size size,
                                      int bandwidthIndex) {
        if (device == null || size == null) return;
        String value = size.type + "," + size.width + "," + size.height + ","
                + size.fps + "," + Math.max(0, bandwidthIndex);
        prefs(context).edit().putString(signalModeKey(device), value).apply();
    }

    @Nullable
    public static SavedSignalMode getSignalMode(Context context, UsbDevice device) {
        if (device == null) return null;
        String value = prefs(context).getString(signalModeKey(device), null);
        if (value == null) return null;
        String[] fields = value.split(",");
        if (fields.length != 5) return null;
        try {
            return new SavedSignalMode(Integer.parseInt(fields[0]),
                    Integer.parseInt(fields[1]), Integer.parseInt(fields[2]),
                    Integer.parseInt(fields[3]), Integer.parseInt(fields[4]));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String signalModeKey(UsbDevice device) {
        return KEY_SIGNAL_MODE_PREFIX + device.getVendorId() + "_" + device.getProductId();
    }

    public static void savePreferredDevice(Context context, UsbDevice device) {
        if (device == null) return;
        prefs(context).edit().putString(KEY_PREFERRED_DEVICE,
                device.getVendorId() + ":" + device.getProductId()).apply();
    }

    public static boolean isPreferredDevice(Context context, UsbDevice device) {
        if (device == null) return false;
        String value = prefs(context).getString(KEY_PREFERRED_DEVICE, null);
        return value != null && value.equals(device.getVendorId() + ":" + device.getProductId());
    }

    public static void saveMultiDevices(Context context, List<UsbDevice> devices) {
        if (devices == null || devices.size() < 2) {
            clearMultiDevices(context);
            return;
        }
        StringBuilder value = new StringBuilder();
        for (UsbDevice device : devices) {
            if (value.length() > 0) value.append('\n');
            value.append(UsbDeviceCatalog.stableKey(device));
        }
        prefs(context).edit().putString(KEY_MULTI_DEVICES, value.toString()).apply();
    }

    public static List<String> getMultiDeviceKeys(Context context) {
        String value = prefs(context).getString(KEY_MULTI_DEVICES, null);
        if (value == null || value.trim().isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        Collections.addAll(result, value.split("\\n", -1));
        return result;
    }

    public static void clearMultiDevices(Context context) {
        prefs(context).edit().remove(KEY_MULTI_DEVICES).apply();
    }

    public static final class SavedSignalMode {
        public final int type;
        public final int width;
        public final int height;
        public final int fps;
        public final int bandwidthIndex;

        SavedSignalMode(int type, int width, int height, int fps, int bandwidthIndex) {
            this.type = type;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.bandwidthIndex = bandwidthIndex;
        }

        public boolean matches(Size size) {
            return size != null && type == size.type && width == size.width
                    && height == size.height && fps == size.fps;
        }
    }
}
