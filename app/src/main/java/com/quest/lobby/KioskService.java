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

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Iterator;

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
    // Kept short (300ms) so a just-closed/crashed Content is detected as dead almost immediately
    // and we relaunch it, instead of treating it as alive for up to 2s and only REORDER-ing.
    private static final long PID_CACHE_MS = 300;
    private long lastPidCheckTime = 0;
    private boolean lastPidAlive = false;

    // "Handle each intrusion once" state. We push the target back only when the situation is
    // genuinely NEW, judged by three things together:
    //   - fgEvent.time : each Meta-menu open is a fresh event with a newer timestamp
    //   - target       : who should own the foreground now (Content vs Lobby) — changes on
    //                     connect/disconnect, so a target switch must re-trigger a pushback
    //   - contentAlive : Content alive->dead means a REORDER is no longer enough and we must
    //                     do a full relaunch, so this transition must re-trigger too
    // While the same menu stays open over an unchanged (time, target, alive) tuple we do NOT
    // keep fighting, so there is no continuous flashing ("一直閃"). Relying on fgEvent.time
    // alone was the bug: if the first pushback saw Content alive but failed, a later tick with
    // Content now dead had the same timestamp and was skipped forever — Content never restarted.
    private long lastHandledFgTime = 0;
    private String lastHandledTarget = null;
    private boolean lastHandledContentAlive = false;
    private long lastInterventionMs = 0;
    private static final long MIN_INTERVENTION_INTERVAL_MS = 500;  // safety cap between pushes

    // Content-dead recovery: relaunch a dead Content even when NO foreground event fires (e.g. a
    // silent crash that doesn't bring the launcher forward). Gated by a cooldown so a Content that
    // crashes on startup is retried at most once per window, never every 100ms tick (no crash-loop).
    private static final long CONTENT_RESTART_COOLDOWN_MS = 8000;
    private long lastContentRestartMs = 0;

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
        String contentPkg = prefs.getString("content_package", MainActivity.DEFAULT_CONTENT_PACKAGE);

        // While Content is legitimately starting up (or was just relaunched by us), don't fight it.
        if (shouldRun) {
            long launchedAt = prefs.getLong("content_launched_at", 0);
            if (System.currentTimeMillis() - launchedAt < LAUNCH_GRACE_MS) return;
        }

        // Decide who SHOULD own the foreground right now:
        //  - Content, ALWAYS, as long as content_should_run == true. If Content's process died
        //    we do NOT fall back to the Lobby — the target stays Content and we relaunch it.
        //  - the Lobby itself only once the server has told us to stop (content_should_run=false).
        boolean contentAlive = shouldRun && isAliveCached(contentPkg);
        String target = shouldRun ? contentPkg : getPackageName();

        // ── Content-dead recovery (independent of foreground events) ──────────────────
        // If Content should be running but its process is dead and we're past the launch grace
        // period, relaunch it PROACTIVELY — even when queryForeground() returns null (a silent
        // crash that never brings the launcher forward wouldn't produce a foreground event, so
        // relying on the menu path below would leave Content dead forever). The cooldown plus the
        // content_launched_at stamp written by bringContentToFront() ensure a crash-looping
        // Content is retried at most once per ~8s, not every 100ms tick.
        if (shouldRun && !contentAlive) {
            long now = System.currentTimeMillis();
            if (now - lastContentRestartMs >= CONTENT_RESTART_COOLDOWN_MS) {
                Log.d(TAG, "Content-dead recovery: relaunching (no foreground event required)");
                lastContentRestartMs = now;
                bringContentToFront(contentPkg, prefs, false);  // restores extras + stamps launch time
            }
            return;  // relaunch in flight or cooling down — don't run the menu/pushback logic
        }

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
            Log.d(TAG, "foreground -> " + fg + " @" + fgEvent.time
                    + "  (target=" + target + ", contentAlive=" + contentAlive + ")");
            lastLoggedFg = fg;
        }

        // Handle each intrusion once. The situation is "new" (worth another pushback) if ANY of
        // the foreground event, the target, or Content's alive state changed since last handled.
        // Same (time, target, alive) => same unresolved intrusion => skip, so no continuous flash.
        boolean sameSituation = fgEvent.time <= lastHandledFgTime
                && target.equals(lastHandledTarget)
                && contentAlive == lastHandledContentAlive;
        if (sameSituation) return;

        long now = System.currentTimeMillis();
        if (now - lastInterventionMs < MIN_INTERVENTION_INTERVAL_MS) return;  // safety cap
        lastHandledFgTime = fgEvent.time;
        lastHandledTarget = target;
        lastHandledContentAlive = contentAlive;
        lastInterventionMs = now;

        if (shouldRun) {
            // Target is Content: REORDER if alive, full relaunch (with extras) if dead.
            bringContentToFront(contentPkg, prefs, contentAlive);
        } else {
            // Target is Lobby: server has disconnected us, keep the kiosk home in front.
            try {
                Log.d(TAG, "bringing Lobby back (fg=" + fg + ")");
                Intent intent = new Intent(this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "KioskService Lobby bring-back failed: " + e.getMessage());
            }
        }
    }

    /**
     * Bring Content to the foreground.
     *
     * <p>Content alive: cheap {@code REORDER_TO_FRONT} — the existing task is raised, no
     * recreate, so a Meta-menu open is dismissed with a single flash.
     *
     * <p>Content dead (user closed it, or it crashed): a full relaunch. We re-attach every
     * Intent extra that {@code MainActivity.launchContent()} originally passed (persisted as
     * {@code content_extras_json}) so Content reconnects exactly as it did the first time —
     * no need to wait for the server to re-send a connect command. We stamp
     * {@code content_launched_at} first so the watchdog's launch grace period covers the
     * startup window and doesn't fight the coming-up Content.
     *
     * @return true if a launch intent was dispatched.
     */
    private boolean bringContentToFront(String contentPkg, SharedPreferences prefs, boolean contentAlive) {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(contentPkg);
            if (intent == null) {
                Log.e(TAG, "bringContentToFront: no launch intent for " + contentPkg);
                return false;
            }
            if (contentAlive) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                Log.d(TAG, "Content alive -> REORDER_TO_FRONT: " + contentPkg);
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                restoreExtras(intent, prefs);
                // Mark relaunch time so the LAUNCH_GRACE_MS window covers Content's startup.
                prefs.edit()
                        .putLong("content_launched_at", System.currentTimeMillis())
                        .apply();
                Log.d(TAG, "Content dead -> full relaunch with extras: " + contentPkg);
            }
            startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "bringContentToFront failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Re-attach the original Content Intent extras persisted by MainActivity.launchContent().
     * All values were stored as Strings (Websocket / cmdLine / userId / fromApp / serverip /
     * wsserverip / wsport / roomId / useMultiVRGis / mapName / ip / port / playerheight), so we
     * re-put them as Strings to keep Content's command-line parsing identical to the first launch.
     */
    private void restoreExtras(Intent intent, SharedPreferences prefs) {
        String json = prefs.getString("content_extras_json", "{}");
        try {
            JSONObject extras = new JSONObject(json);
            Iterator<String> keys = extras.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                intent.putExtra(key, extras.optString(key));
            }
        } catch (Exception e) {
            Log.e(TAG, "restoreExtras parse error: " + e.getMessage());
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
