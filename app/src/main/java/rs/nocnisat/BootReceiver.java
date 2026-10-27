package rs.nocnisat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores the alarm after a restart, an app update or a clock change. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        AlarmStore.scheduleNext(ctx);
    }
}
