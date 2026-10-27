package rs.nocnisat;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/** Keeps the alarm list (written by the clock page) and schedules the next one with the system. */
public final class AlarmStore {
    static final String PREFS = "nocnisat";
    static final String ACTION_FIRE = "rs.nocnisat.FIRE";
    static final String ACTION_SNOOZE_FIRE = "rs.nocnisat.SNOOZE_FIRE";
    private static final int RC_ALARM = 100;
    private static final int RC_SHOW = 101;
    private static final int RC_SNOOZE = 102;

    private AlarmStore() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String getJson(Context c) {
        return prefs(c).getString("alarms", "");
    }

    static void saveJson(Context c, String json) {
        prefs(c).edit().putString("alarms", json).apply();
        scheduleNext(c);
    }

    static int piFlags() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
        return f;
    }

    /** Same rules as the page: days use 0 = Sunday ... 6 = Saturday, an empty list means once. */
    static long nextFire(JSONObject a, long now) {
        int h = 7, m = 0;
        try {
            String[] hm = a.optString("time", "07:00").split(":");
            h = Integer.parseInt(hm[0].trim());
            m = Integer.parseInt(hm[1].trim());
        } catch (Exception ignored) {}
        JSONArray days = a.optJSONArray("days");
        for (int i = 0; i < 8; i++) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(now);
            c.add(Calendar.DAY_OF_YEAR, i);
            c.set(Calendar.HOUR_OF_DAY, h);
            c.set(Calendar.MINUTE, m);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            if (c.getTimeInMillis() <= now) continue;
            if (days == null || days.length() == 0) return c.getTimeInMillis();
            int jsDay = c.get(Calendar.DAY_OF_WEEK) - 1;
            for (int k = 0; k < days.length(); k++) {
                if (days.optInt(k, -1) == jsDay) return c.getTimeInMillis();
            }
        }
        return -1;
    }

    static void putAlarmExtras(Intent i, JSONObject a) {
        JSONObject st = a.optJSONObject("station");
        i.putExtra("url", st != null ? st.optString("url", "") : "");
        i.putExtra("station", st != null ? st.optString("name", "") : "");
        i.putExtra("label", a.optString("label", ""));
        i.putExtra("fade", a.optBoolean("fade", true));
        i.putExtra("id", a.optLong("id", 0));
    }

    static void scheduleNext(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(c, AlarmReceiver.class).setAction(ACTION_FIRE);
        am.cancel(PendingIntent.getBroadcast(c, RC_ALARM, i, piFlags()));

        long best = -1;
        JSONObject bestA = null;
        try {
            String json = getJson(c);
            JSONArray arr = new JSONArray(json.isEmpty() ? "[]" : json);
            long now = System.currentTimeMillis();
            for (int k = 0; k < arr.length(); k++) {
                JSONObject a = arr.getJSONObject(k);
                if (!a.optBoolean("on", false)) continue;
                long n = nextFire(a, now);
                if (n > 0 && (best < 0 || n < best)) {
                    best = n;
                    bestA = a;
                }
            }
        } catch (Exception e) {
            return;
        }
        if (bestA == null) return;
        putAlarmExtras(i, bestA);
        setExact(c, am, best, PendingIntent.getBroadcast(c, RC_ALARM, i, piFlags()));
    }

    static void setExact(Context c, AlarmManager am, long when, PendingIntent pi) {
        PendingIntent show = PendingIntent.getActivity(c, RC_SHOW, new Intent(c, MainActivity.class), piFlags());
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(when, show), pi);
        } catch (SecurityException e) {
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            else am.set(AlarmManager.RTC_WAKEUP, when, pi);
        }
    }

    static void scheduleSnooze(Context c, Intent src, int minutes) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(c, AlarmReceiver.class).setAction(ACTION_SNOOZE_FIRE);
        if (src != null) {
            i.putExtra("url", src.getStringExtra("url"));
            i.putExtra("station", src.getStringExtra("station"));
            i.putExtra("label", src.getStringExtra("label"));
        }
        i.putExtra("fade", false);
        i.putExtra("id", -1L);
        long when = System.currentTimeMillis() + minutes * 60_000L;
        setExact(c, am, when, PendingIntent.getBroadcast(c, RC_SNOOZE, i, piFlags()));
    }

    /** After an alarm rings: switch off a one-time alarm and schedule the following one. */
    static void afterFire(Context c, long id) {
        try {
            String json = getJson(c);
            if (!json.isEmpty()) {
                JSONArray arr = new JSONArray(json);
                for (int k = 0; k < arr.length(); k++) {
                    JSONObject a = arr.getJSONObject(k);
                    JSONArray days = a.optJSONArray("days");
                    if (a.optLong("id", 0) == id && (days == null || days.length() == 0)) a.put("on", false);
                }
                prefs(c).edit().putString("alarms", arr.toString()).apply();
            }
        } catch (Exception ignored) {}
        scheduleNext(c);
    }
}
