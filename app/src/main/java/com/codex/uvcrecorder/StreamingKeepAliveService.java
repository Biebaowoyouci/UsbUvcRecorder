package com.codex.uvcrecorder;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

/**
 * Keeps the process, UVC host controller and RTMP socket active while the
 * display is off. The Activity continues to own the actual capture graph; this
 * foreground service supplies Android's required connected-device and
 * microphone background execution priority plus CPU/Wi-Fi locks.
 */
public final class StreamingKeepAliveService extends Service {
    private static final String ACTION_START =
            "com.codex.uvcrecorder.action.START_STREAM_KEEPALIVE";
    private static final String CHANNEL_ID = "uvc_background_stream";
    private static final int NOTIFICATION_ID = 0x555643;

    private PowerManager.WakeLock cpuWakeLock;
    private WifiManager.WifiLock wifiLock;

    static void start(Context context) {
        Intent intent = new Intent(context, StreamingKeepAliveService.class)
                .setAction(ACTION_START);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (RuntimeException ignored) {
            // Activity lifecycle can race a user-initiated stop. A later RTMP
            // state callback retries while the capture session is still wanted.
        }
    }

    static void stop(Context context) {
        try {
            context.stopService(new Intent(context, StreamingKeepAliveService.class));
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        acquireLocks();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int foregroundTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        if (AppSettings.isRtmpAudioEnabled(this)
                && AppSettings.isUsbAudioEnabled(this)
                && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            foregroundTypes |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(),
                foregroundTypes);
        acquireLocks();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        releaseLocks();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "UVC 后台推流", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("息屏时保持 UVC 采集和 RTMP 推流");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("UVC 正在后台推流")
                .setContentText("息屏保持视频、UAC 音频和网络连接；点击返回预览")
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        if (cpuWakeLock == null) {
            PowerManager powerManager =
                    (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                cpuWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "UsbUvcRecorder:BackgroundStreaming");
                cpuWakeLock.setReferenceCounted(false);
            }
        }
        if (cpuWakeLock != null && !cpuWakeLock.isHeld()) {
            cpuWakeLock.acquire();
        }

        if (wifiLock == null) {
            WifiManager wifiManager =
                    (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "UsbUvcRecorder:BackgroundStreaming");
                wifiLock.setReferenceCounted(false);
            }
        }
        if (wifiLock != null && !wifiLock.isHeld()) {
            wifiLock.acquire();
        }
    }

    private void releaseLocks() {
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        wifiLock = null;
        if (cpuWakeLock != null && cpuWakeLock.isHeld()) cpuWakeLock.release();
        cpuWakeLock = null;
    }
}
