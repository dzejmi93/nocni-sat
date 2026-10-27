package rs.nocnisat;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Locale;

/** Full-screen alarm shown over the lock screen, in the clock's pink and cream look. */
public class AlarmActivity extends Activity {
    private static final int PINK = 0xFFF2D3E3;
    private static final int CREAM = 0xFFF6F2E4;
    private static final int INK = 0xFF3D3B3E;
    private static final int DIM = 0xFF6E6669;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView timeView;
    private final BroadcastReceiver closer = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            finish();
        }
    };

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);

        String label = getIntent().getStringExtra("label");
        if (label == null || label.isEmpty()) label = "Dobro jutro";
        String station = getIntent().getStringExtra("station");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(PINK);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        timeView = new TextView(this);
        timeView.setTextColor(INK);
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 96);
        timeView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        timeView.setGravity(Gravity.CENTER);
        root.addView(timeView);

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(INK);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        l.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        l.setGravity(Gravity.CENTER);
        root.addView(l);

        if (station != null && !station.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(station);
            s.setTextColor(DIM);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            s.setGravity(Gravity.CENTER);
            s.setPadding(0, dp(4), 0, 0);
            root.addView(s);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(32), 0, 0);
        row.addView(button("Odloži 9 min", CREAM, INK, v -> send(AlarmService.ACTION_SNOOZE)));
        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(dp(14), 1));
        row.addView(button("Isključi", INK, CREAM, v -> send(AlarmService.ACTION_STOP)));
        root.addView(row);

        setContentView(root);
        tick();

        IntentFilter f = new IntentFilter(AlarmService.BROADCAST_CLOSE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(closer, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(closer, f);
    }

    private TextView button(String text, int bg, int fg, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(text.toUpperCase(Locale.ROOT));
        t.setTextColor(fg);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        t.setLetterSpacing(0.06f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(26), dp(18), dp(26), dp(18));
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setStroke(dp(2), INK);
        d.setCornerRadius(dp(12));
        t.setBackground(d);
        t.setClickable(true);
        t.setOnClickListener(v -> {
            v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).withEndAction(() ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()).start();
            click.onClick(v);
        });
        return t;
    }

    private void send(String action) {
        try {
            startService(new Intent(this, AlarmService.class).setAction(action));
        } catch (Exception ignored) {}
        finish();
    }

    private void tick() {
        Calendar c = Calendar.getInstance();
        timeView.setText(String.format(Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)));
        handler.postDelayed(this::tick, 10_000L);
    }

    @Override
    public void onBackPressed() {
        // keep the alarm on screen until Stop or Snooze is pressed
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(closer);
        } catch (Exception ignored) {}
        super.onDestroy();
    }
}
