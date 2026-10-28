package com.mihir.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Android turns a package's accessibility services off when the package is updated, and that silently stops the
 * Home key reaching us: the first press after an update lands on Amazon's launcher instead of the cover. This
 * runs as soon as the update lands, so the service is back before anyone presses Home.
 */
public class UpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) HomeKeyService.rearm(context);
    }
}
