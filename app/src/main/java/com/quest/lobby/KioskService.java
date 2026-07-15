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

        MainActivity.Foreground immediateFgEvent = MainActivity.queryForeground(this);
        String immediateFg = immediateFgEvent == null ? null : immediateFgEvent.pkg;
        if (shouldRun && immediateFg != null
                && !immediateFg.equals(getPackageName())
                && !immediateFg.equals(contentPkg)) {
            long now = System.currentTimeMillis();
            boolean alreadyHandled = immediateFgEvent.time <= lastHandledFgTime
                    && contentPkg.equals(lastHandledTarget);
            if (!alreadyHandled && now - lastInterventionMs >= MIN_INTERVENTION_INTERVAL_MS) {
                lastHandledFgTime = immediateFgEvent.time;
                lastHandledTarget = contentPkg;
                lastInterventionMs = now;
                Log.d(TAG, "Meta/other foreground -> immediate Content pullback: " + immediateFg);
                bringContentToFront(contentPkg, prefs);
            }
            return;
        }

        // While Content is legitimately starting up (or was just relaunched by us), don't fight it.
        if (shouldRun) {
            long launchedAt = prefs.getLong("content_launched_at", 0);
            if (System.currentTimeMillis() - launchedAt < LAUNCH_GRACE_MS) return;
        }

        // Content may only be the foreground target when ALL of these hold:
        //   content_should_run == true          — a session is meant to be active
        //   content_session_confirmed == true   — the SERVER confirmed THIS run's session
        //                                         (set only by launchContent after a connect cmd;
        //                                          reset on disconnect / check-reconnect timeout /
        //                                          fresh app start). This stops a stale
        //                                          content_should_run=true from a previous run
        //                                          making us jump to an unconfirmed Content.
        //   Content process is actually alive   — we only ever REORDER a live Content, never launch.
        // Otherwise the target is the Lobby. In particular, when Content is DEAD (crashed /
        // force-stopped / not started yet), KioskService does NOT launch it — it brings the Lobby
        // forward and lets MainActivity.onResume() ask the server (headset_check_reconnect) whether
        // Content should be (re)started. Only a server connect command may launch Content.
        boolean sessionConfirmed = prefs.getBoolean("content_session_confirmed", false);
        boolean contentAlive = shouldRun && isAliveCached(contentPkg);
        boolean contentIsTarget = shouldRun && sessionConfirmed && contentAlive;
        String target = contentIsTarget ? contentPkg : getPackageName();

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
                    + "  (target=" + target + ", contentAlive=" + contentAlive
                    + ", sessionConfirmed=" + sessionConfirmed + ")");
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

        if (contentIsTarget) {
            // Content is a server-confirmed, alive session that lost the foreground → just raise it
            // back (single flash, no relaunch, no extras).
            bringContentToFront(contentPkg, prefs);
        } else {
            // Target is the Lobby. Cases that land here:
            //  (a) content_should_run == false — server disconnected us; Lobby is the kiosk home.
            //  (b) content_should_run == true but session NOT server-confirmed — stale prefs from a
            //      previous run; must not jump to Content until the server re-confirms.
            //  (c) content_should_run == true but Content is DEAD — do NOT launch Content here.
            //  In (b)/(c) bringing the Lobby forward lets MainActivity.onResume() ask the server
            //  (headset_check_reconnect) whether Content should be started again.
            try {
                Log.d(TAG, "bringing Lobby back (fg=" + fg + ", shouldRun=" + shouldRun
                        + ", sessionConfirmed=" + sessionConfirmed
                        + ", contentAlive=" + contentAlive + ")");
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
     * Raise the ALREADY-RUNNING Content back to the foreground with a cheap
     * {@code REORDER_TO_FRONT} — the existing task is brought to top, no recreate, so a
     * Meta-menu open is dismissed with a single flash.
     *
     * The persisted launch extras are included in case Android must recreate Content instead of
     * merely reordering its existing task.
     *
     * @return true if a REORDER intent was dispatched.
     */
    private boolean bringContentToFront(String contentPkg, SharedPreferences prefs) {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(contentPkg);
            if (intent == null) {
                Log.e(TAG, "bringContentToFront: no launch intent for " + contentPkg);
                return false;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            restoreExtras(intent, prefs);
            Log.d(TAG, "Content -> REORDER_TO_FRONT: " + contentPkg);
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
     *
     * Used by the immediate Meta-menu pullback so a recreated Unreal activity receives the same
     * command line and connection parameters as its initial launch.
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
