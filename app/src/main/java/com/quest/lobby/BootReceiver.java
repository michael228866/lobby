package com.quest.lobby;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    private static final long BOOT_DELAY_MS = 5000;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d("Lobby", "Boot completed — launching Lobby in " + BOOT_DELAY_MS + "ms");
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                Intent launchIntent = new Intent(context, MainActivity.class);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launchIntent);
            }, BOOT_DELAY_MS);
        }
    }
}
