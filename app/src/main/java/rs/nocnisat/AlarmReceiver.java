package rs.nocnisat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Woken by the system at alarm time; starts the ringing service. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Intent s = new Intent(ctx, AlarmService.class).setAction(AlarmService.ACTION_START);
        if (intent.getExtras() != null) s.putExtras(intent.getExtras());
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(s);
        else ctx.startService(s);
        if (AlarmStore.ACTION_FIRE.equals(intent.getAction())) {
            AlarmStore.afterFire(ctx, intent.getLongExtra("id", 0));
        }
    }
}
