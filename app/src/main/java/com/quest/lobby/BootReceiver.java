package com.quest.lobby;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {

    // ponytail: calibration knob. 5s was too early on a cold boot — the Quest OpenXR runtime and
    // camera/tracking services aren't up yet, so the VR session fails to init. The native render
    // thread now retries init, but starting later avoids the retry churn (and a possibly poisoned
    // runtime). Tune per device if boot is slower.
    private static final long BOOT_DELAY_MS = 15000;

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
