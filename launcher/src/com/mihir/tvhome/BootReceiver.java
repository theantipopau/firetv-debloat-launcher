package com.mihir.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Fire OS boots Amazon's own launcher even when another home is installed and selected:
 * {@code cmd package set-home-activity} only makes the Home key reliable. So come back a few seconds
 * after boot, and look again later in case Amazon started after us.
 *
 * <p>Both steps are alarms rather than delayed callbacks: a broadcast receiver's process can be killed
 * long before a nine-second delay would have elapsed.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final long FIRST_MS = 9000;
    private static final long CHECK_MS = 26000;
    static final String CHECK = "com.mihir.tvhome.BOOT_CHECK";

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (CHECK.equals(action)) {
            // Ours is already on screen: leave it alone. Otherwise Amazon won the race after our first try.
            if (!context.getSharedPreferences("tvhome", 0).getBoolean("foreground", false)) start(context);
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) return;
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long now = System.currentTimeMillis();
        am.setExact(AlarmManager.RTC, now + FIRST_MS, activity(context, 1));
        Intent again = new Intent(context, BootReceiver.class).setAction(CHECK);
        am.setExact(AlarmManager.RTC, now + CHECK_MS, PendingIntent.getBroadcast(context, 2, again, flags()));
    }

    private static PendingIntent activity(Context context, int request) {
        return PendingIntent.getActivity(context, request,
                new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                flags());
    }

    private static int flags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }

    private static void start(Context context) {
        context.startActivity(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }
}
