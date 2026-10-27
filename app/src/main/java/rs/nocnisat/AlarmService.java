package rs.nocnisat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/** Plays the chosen station at alarm volume, with a built-in tone if the stream fails. */
public class AlarmService extends Service {
    static final String ACTION_START = "rs.nocnisat.START";
    static final String ACTION_STOP = "rs.nocnisat.STOP";
    static final String ACTION_SNOOZE = "rs.nocnisat.SNOOZE";
    static final String BROADCAST_CLOSE = "rs.nocnisat.CLOSE";
    private static final String CHANNEL = "alarm_ring";
    private static final int NOTIF_ID = 7;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private MediaPlayer tone;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private Intent current;
    private boolean streamStarted;
    private float volume = 1f;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopAll();
            return START_NOT_STICKY;
        }
        if (ACTION_SNOOZE.equals(action)) {
            AlarmStore.scheduleSnooze(this, current != null ? current : intent, 9);
            stopAll();
            return START_NOT_STICKY;
        }
        releasePlayers();
        handler.removeCallbacksAndMessages(null);
        current = intent;
        goForeground(intent);
        acquireLocks();
        startPlayback(intent);
        handler.postDelayed(this::stopAll, 60 * 60 * 1000L);
        return START_NOT_STICKY;
    }

    private PendingIntent servicePi(String action, int rc) {
        return PendingIntent.getService(this, rc, new Intent(this, AlarmService.class).setAction(action), AlarmStore.piFlags());
    }

    @SuppressWarnings("deprecation")
    private void goForeground(Intent src) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Alarm", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null);
            ch.setBypassDnd(true);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
        Intent full = new Intent(this, AlarmActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        if (src.getExtras() != null) full.putExtras(src.getExtras());
        PendingIntent fullPi = PendingIntent.getActivity(this, 200, full, AlarmStore.piFlags());

        String label = src.getStringExtra("label");
        if (label == null || label.isEmpty()) label = "Dobro jutro";
        String station = src.getStringExtra("station");

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this).setPriority(Notification.PRIORITY_MAX);
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(label)
                .setContentText(station == null || station.isEmpty() ? "Alarm" : station)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setContentIntent(fullPi)
                .setFullScreenIntent(fullPi, true)
                .addAction(0, "Odloži 9 min", servicePi(ACTION_SNOOZE, 202))
                .addAction(0, "Isključi", servicePi(ACTION_STOP, 201));
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIF_ID, n);

        // when the phone is unlocked and in use, open the alarm screen directly as well
        try {
            startActivity(full);
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nocnisat:alarm");
                wakeLock.acquire(60 * 60 * 1000L);
            }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wifiLock == null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nocnisat:radio");
                wifiLock.acquire();
            }
        } catch (Exception ignored) {}
    }

    private AudioAttributes alarmAttrs() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
    }

    private void ensureAlarmVolume() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, Math.max(1, (int) (max * 0.6f)), 0);
            }
        } catch (Exception ignored) {}
    }

    private void startPlayback(Intent src) {
        ensureAlarmVolume();
        String url = src.getStringExtra("url");
        boolean fade = src.getBooleanExtra("fade", true);
        streamStarted = false;
        if (url == null || url.isEmpty()) {
            playTone();
            return;
        }
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(alarmAttrs());
            player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            player.setDataSource(url);
            volume = fade ? 0.05f : 1f;
            player.setOnPreparedListener(mp -> {
                streamStarted = true;
                mp.setVolume(volume, volume);
                mp.start();
                if (fade) fadeIn();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                playTone();
                return true;
            });
            player.setOnCompletionListener(mp -> playTone());
            player.prepareAsync();
        } catch (Exception e) {
            playTone();
            return;
        }
        handler.postDelayed(() -> {
            if (!streamStarted) playTone();
        }, 15_000L);
    }

    private void fadeIn() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (player == null) return;
                volume = Math.min(1f, volume + 0.95f / 60f);
                try {
                    player.setVolume(volume, volume);
                } catch (Exception ignored) {}
                if (volume < 1f) handler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private void playTone() {
        if (tone != null) return;
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {}
            player = null;
        }
        try {
            Uri u = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            tone = new MediaPlayer();
            tone.setAudioAttributes(alarmAttrs());
            tone.setDataSource(this, u);
            tone.setLooping(true);
            tone.prepare();
            tone.start();
        } catch (Exception ignored) {}
    }

    private void releasePlayers() {
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {}
            player = null;
        }
        if (tone != null) {
            try {
                tone.release();
            } catch (Exception ignored) {}
            tone = null;
        }
    }

    @SuppressWarnings("deprecation")
    private void stopAll() {
        handler.removeCallbacksAndMessages(null);
        releasePlayers();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        wifiLock = null;
        sendBroadcast(new Intent(BROADCAST_CLOSE).setPackage(getPackageName()));
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releasePlayers();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }
}
