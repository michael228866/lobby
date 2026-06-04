package com.quest.lobby;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Foreground service that runs the kiosk watchdog independently of Activity lifecycle.
 * This bypasses Android's background Handler throttling — the watchdog stays responsive
 * even when Lobby's Activity has been backgrounded for a long time (which is the normal
 * state while Content is running).
 *
 * Without this service, the second/third Meta-menu invocations would feel sluggish
 * because Android's background process throttling makes the Activity's Handler fire
 * less frequently over time.
 */
public class KioskService extends Service {

    private static final String TAG = "Lobby";
    private static final String CHANNEL_ID = "lobby_kiosk_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final long CHECK_INTERVAL_MS = 100;
    private static final long LAUNCH_GRACE_MS = 8000;

    private Handler handler;
    private volatile boolean running = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        handler = new Handler(Looper.getMainLooper());
        Log.d(TAG, "KioskService started (foreground)");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            handler.postDelayed(this::tick, CHECK_INTERVAL_MS);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        Log.d(TAG, "KioskService stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void tick() {
        if (!running) return;
        try {
            doCheck();
        } catch (Exception e) {
            Log.e(TAG, "KioskService tick error: " + e.getMessage());
        }
        handler.postDelayed(this::tick, CHECK_INTERVAL_MS);
    }

    private void doCheck() {
        SharedPreferences prefs = getSharedPreferences("lobby_state", MODE_PRIVATE);
        boolean shouldRun = prefs.getBoolean("content_should_run", false);
        if (!shouldRun) return; // No Content launched, nothing to monitor

        long launchedAt = prefs.getLong("content_launched_at", 0);
        if (System.currentTimeMillis() - launchedAt < LAUNCH_GRACE_MS) return;

        String contentPkg = prefs.getString("content_package", "com.moondream.HellVR");
        String fg = MainActivity.queryForegroundPackage(this);
        if (fg == null) return;
        if (fg.equals(contentPkg)) return;
        if (fg.equals(getPackageName())) return;

        // Foreground is something else (Meta menu, Quest home, etc.)
        if (isAlive(contentPkg)) {
            Log.d(TAG, "KioskService: fg=" + fg + ", Content alive — bringing back");
            try {
                PackageManager pm = getPackageManager();
                Intent intent = pm.getLaunchIntentForPackage(contentPkg);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                }
            } catch (Exception e) {
                Log.e(TAG, "KioskService start Content failed: " + e.getMessage());
            }
        } else {
            // Content dead — bring Lobby back to handle reconnect logic
            Log.d(TAG, "KioskService: fg=" + fg + ", Content DEAD — bringing Lobby back");
            try {
                Intent intent = new Intent(this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "KioskService start Lobby failed: " + e.getMessage());
            }
        }
    }

    private boolean isAlive(String pkg) {
        try {
            java.lang.Process proc = Runtime.getRuntime().exec(new String[]{"sh", "-c", "pidof " + pkg});
            proc.waitFor();
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String pid = reader.readLine();
            return pid != null && !pid.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Lobby Kiosk",
                NotificationManager.IMPORTANCE_MIN  // silent, no sound/vibration
            );
            channel.setDescription("Keeps Lobby kiosk monitoring active");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("Lobby")
                .setContentText("Kiosk mode active")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build();
    }
}
