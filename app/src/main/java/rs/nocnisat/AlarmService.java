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

    private final java.util.ArrayList<String> queue = new java.util.ArrayList<>();
    private boolean fadeWanted;
    private int fadeSec = 60;
    private boolean fading;

    private void startPlayback(Intent src) {
        ensureAlarmVolume();
        queue.clear();
        String url = src.getStringExtra("url");
        if (url != null && !url.isEmpty()) queue.add(url);
        String backups = src.getStringExtra("backups");
        if (backups != null) {
            for (String u : backups.split("\n")) {
                u = u.trim();
                if (!u.isEmpty() && !queue.contains(u)) queue.add(u);
            }
        }
        fadeSec = src.getIntExtra("fadeSec", src.getBooleanExtra("fade", true) ? 60 : 0);
        fadeWanted = fadeSec > 0;
        fading = false;
        volume = fadeWanted ? 0.05f : 1f;
        streamStarted = false;
        tryNextStream();
    }

    /** Plays the next stream in line; when none is left, the alarm tone. */
    private void tryNextStream() {
        if (streamStarted || tone != null) return;
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {}
            player = null;
        }
        if (queue.isEmpty()) {
            playTone();
            return;
        }
        final String url = queue.remove(0);
        final MediaPlayer mp0 = new MediaPlayer();
        player = mp0;
        try {
            mp0.setAudioAttributes(alarmAttrs());
            mp0.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mp0.setDataSource(url);
            mp0.setOnPreparedListener(mp -> {
                if (mp != player) return;
                streamStarted = true;
                mp.setVolume(volume, volume);
                mp.start();
                if (fadeWanted) fadeIn();
            });
            mp0.setOnErrorListener((mp, what, extra) -> {
                if (mp == player) {
                    if (streamStarted) {
                        streamStarted = false;
                        queue.add(0, url); // dropped mid-way: reconnect once, then move on
                    }
                    handler.post(this::tryNextStream);
                }
                return true;
            });
            mp0.setOnCompletionListener(mp -> {
                if (mp == player) {
                    streamStarted = false;
                    handler.post(this::tryNextStream);
                }
            });
            mp0.prepareAsync();
        } catch (Exception e) {
            handler.post(this::tryNextStream);
            return;
        }
        handler.postDelayed(() -> {
            if (player == mp0 && !streamStarted) tryNextStream();
        }, 12_000L);
    }

    /** Raises the radio from almost silent to full over the chosen number of seconds. */
    private void fadeIn() {
        if (fading || volume >= 1f) {
            return;
        }
        fading = true;
        final float step = 0.95f / Math.max(1, fadeSec);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (player == null) {
                    fading = false;
                    return;
                }
                volume = Math.min(1f, volume + step);
                try {
                    player.setVolume(volume, volume);
                } catch (Exception ignored) {}
                if (volume < 1f) handler.postDelayed(this, 1000);
                else fading = false;
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
