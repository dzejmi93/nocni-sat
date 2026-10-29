package rs.nocnisat;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/** Shows the clock page and lets it hand its alarms to the system. */
public class MainActivity extends Activity {
    private WebView web;
    private volatile boolean radioPlaying = false;
    private boolean pausedWeb = false;

    private final BroadcastReceiver battery = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            boolean plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            // on the charger the clock stays visible; on battery the phone may sleep and the alarm still wakes it
            if (plugged) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    };

    public class Bridge {
        @JavascriptInterface
        public void saveAlarms(String json) {
            AlarmStore.saveJson(getApplicationContext(), json);
        }

        @JavascriptInterface
        public String getAlarms() {
            return AlarmStore.getJson(getApplicationContext());
        }

        @JavascriptInterface
        public void setPlaying(boolean p) {
            radioPlaying = p;
        }

        @JavascriptInterface
        public void testAlarm(String url, String label, String station) {
            Intent i = new Intent(getApplicationContext(), AlarmService.class)
                    .setAction(AlarmService.ACTION_START)
                    .putExtra("url", url)
                    .putExtra("label", label)
                    .putExtra("station", station)
                    .putExtra("fade", false)
                    .putExtra("id", -2L);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        immersive();
        web = new WebView(this);
        web.setBackgroundColor(0xFFF2D3E3);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "NocniSat");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        AlarmStore.scheduleNext(this);
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        if (web != null && pausedWeb) {
            web.onResume();
            web.resumeTimers();
            pausedWeb = false;
        }
        registerReceiver(battery, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (web != null) web.evaluateJavascript("window.__nativeAlarmsChanged&&window.__nativeAlarmsChanged()", null);
        firstRunStep();
    }

    @Override
    protected void onPause() {
        try {
            unregisterReceiver(battery);
        } catch (Exception ignored) {}
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // the clock is not visible: stop its timers and drawing so it costs no battery
        if (web != null && !radioPlaying) {
            web.onPause();
            web.pauseTimers();
            pausedWeb = true;
        }
        super.onPause();
    }

    /** Asks, one at a time, for what a reliable alarm needs. Each is asked only once. */
    private void firstRunStep() {
        SharedPreferences p = AlarmStore.prefs(this);
        String pkg = getPackageName();
        try {
            if (Build.VERSION.SDK_INT >= 33 && !p.getBoolean("askedNotif", false)
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                p.edit().putBoolean("askedNotif", true).apply();
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
                return;
            }
            if (Build.VERSION.SDK_INT >= 34 && !p.getBoolean("askedFsi", false)) {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null && !nm.canUseFullScreenIntent()) {
                    p.edit().putBoolean("askedFsi", true).apply();
                    Toast.makeText(this, "Dozvoli Noćnom satu prikaz preko celog ekrana, da se alarm vidi na zaključanom ekranu.", Toast.LENGTH_LONG).show();
                    startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + pkg)));
                    return;
                }
            }
            if (Build.VERSION.SDK_INT >= 31 && Build.VERSION.SDK_INT < 33 && !p.getBoolean("askedExact", false)) {
                AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                if (am != null && !am.canScheduleExactAlarms()) {
                    p.edit().putBoolean("askedExact", true).apply();
                    Toast.makeText(this, "Uključi Alarme i podsetnike za Noćni sat.", Toast.LENGTH_LONG).show();
                    startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + pkg)));
                    return;
                }
            }
            if (Build.VERSION.SDK_INT >= 23 && !p.getBoolean("askedBattery", false)) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(pkg)) {
                    p.edit().putBoolean("askedBattery", true).apply();
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + pkg)));
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
