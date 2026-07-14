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

    // Lock Task is unavailable on this Quest (Meta account blocks device owner), so we fall
    // back to the startActivity pullback — but push back only ONCE per intrusion (see doCheck)
    // so the user sees a single flash, not the continuous "畫面一直閃" fighting.
    private static final boolean FOREGROUND_PULLBACK_ENABLED = true;

    private Handler handler;
    private volatile boolean running = false;

    // Diagnostic: remember the last foreground package so we log only on change (not every 100ms).
    private String lastLoggedFg = null;

    // Cache pidof result for this long — running it every 100ms eats CPU and may
    // slow down Content's rendering, causing the "Meta menu dismiss slowly covers Content" issue.
    private static final long PID_CACHE_MS = 2000;
    private long lastPidCheckTime = 0;
    private boolean lastPidAlive = false;

    // "Flash once per menu-open" state: we push the target back only when we see a foreground
    // event NEWER than the one we last handled. Each Meta-menu open is a fresh event with a
    // new timestamp, so every open flashes exactly once; while the same menu stays open the
    // timestamp doesn't change, so we don't keep fighting (no "一直閃"). This works even though
    // REORDER_TO_FRONT produces no "Lobby returned" event to reset on.
    private long lastHandledFgTime = 0;
    private long lastInterventionMs = 0;
    private static final long MIN_INTERVENTION_INTERVAL_MS = 500;  // safety cap between pushes

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
        if (!FOREGROUND_PULLBACK_ENABLED) return;  // TEMP: Lock Task handles kiosk, no fighting

        SharedPreferences prefs = getSharedPreferences("lobby_state", MODE_PRIVATE);
        boolean shouldRun = prefs.getBoolean("content_should_run", false);
        String contentPkg = prefs.getString("content_package", "com.moondream.HellVR");

        // While Content is legitimately starting up, don't fight it.
        if (shouldRun) {
            long launchedAt = prefs.getLong("content_launched_at", 0);
            if (System.currentTimeMillis() - launchedAt < LAUNCH_GRACE_MS) return;
        }

        // Decide who SHOULD own the foreground right now:
        //  - Content, if it was launched and its process is still alive
        //  - otherwise the Lobby itself — it is the kiosk home and must stay in front
        boolean contentShouldOwn = shouldRun && isAliveCached(contentPkg);
        String target = contentShouldOwn ? contentPkg : getPackageName();

        MainActivity.Foreground fgEvent = MainActivity.queryForeground(this);
        String fg = (fgEvent == null) ? null : fgEvent.pkg;

        // Foreground is normal (target in front, or null = steady/no recent event) — nothing
        // to do. Do NOT reset lastHandledFgTime here: a later, newer event is what re-arms us.
        if (fg == null || fg.equals(target)) {
            lastLoggedFg = fg;  // may be null
            return;
        }

        // A different app grabbed the foreground (Meta menu / Quest home / other).
        if (!fg.equals(lastLoggedFg)) {
            Log.d(TAG, "foreground -> " + fg + " @" + fgEvent.time + "  (target=" + target + ")");
            lastLoggedFg = fg;
        }

        // Push the target back once per NEW foreground event. Each Meta-menu open produces a
        // newer timestamp, so every open flashes exactly once; while the same menu stays open
        // the timestamp is unchanged, so we don't keep fighting (no continuous flashing).
        if (fgEvent.time <= lastHandledFgTime) return;
        long now = System.currentTimeMillis();
        if (now - lastInterventionMs < MIN_INTERVENTION_INTERVAL_MS) return;  // safety cap
        lastHandledFgTime = fgEvent.time;
        lastInterventionMs = now;

        try {
            Intent intent;
            if (contentShouldOwn) {
                Log.d(TAG, "bringing Content back (fg=" + fg + ")");
                intent = getPackageManager().getLaunchIntentForPackage(contentPkg);
                if (intent == null) return;
            } else {
                Log.d(TAG, "bringing Lobby back (fg=" + fg + ")");
                intent = new Intent(this, MainActivity.class);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "KioskService bring-back failed: " + e.getMessage());
        }
    }

    private boolean isAliveCached(String pkg) {
        long now = System.currentTimeMillis();
        if (now - lastPidCheckTime < PID_CACHE_MS) {
            return lastPidAlive;
        }
        lastPidCheckTime = now;
        try {
            java.lang.Process proc = Runtime.getRuntime().exec(new String[]{"sh", "-c", "pidof " + pkg});
            proc.waitFor();
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String pid = reader.readLine();
            lastPidAlive = pid != null && !pid.trim().isEmpty();
        } catch (Exception e) {
            lastPidAlive = false;
        }
        return lastPidAlive;
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
