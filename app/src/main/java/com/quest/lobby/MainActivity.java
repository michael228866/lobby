package com.quest.lobby;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.UUID;

import org.json.JSONObject;

import okio.ByteString;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class MainActivity extends Activity {

    private static final String TAG = "Lobby";

    private static final String SERVER_HOST = "192.168.99.200";
    private static final int SERVER_PORT = 5000;
    private static final String CLIENT_TYPE = "vr_headset";
    private static final String DEFAULT_ROOM_ID = "1234";

    private String serverUrl;
    private String roomId = DEFAULT_ROOM_ID;

    private static final long RECONNECT_DELAY_MS = 3000;

    static {
        System.loadLibrary("lobby_vr");
    }

    private native void nativeCreate();
    private native void nativeDestroy();

    private static final String DEFAULT_CONTENT_PACKAGE = "com.quest.content";
    // Ultra-aggressive check interval — user wants instant Meta menu → Content
    private static final long KIOSK_CHECK_INTERVAL_MS = 100;
    // Skip watchdog checks during Content's initial startup so we don't fight with it
    private static final long KIOSK_LAUNCH_GRACE_MS = 8000;
    private static final String PREFS = "lobby_state";
    private static final String PREF_CONTENT_LAUNCHED_AT = "content_launched_at";
    private static final String PREF_CONTENT_SHOULD_RUN = "content_should_run";
    private static final String PREF_CONTENT_PACKAGE = "content_package";
    private static final String PREF_CONTENT_EXTRAS_JSON = "content_extras_json";
    // Skip reconnecting WebSocket for this long after launching Content
    // (Content owns the clientId during this window — Lobby reconnecting would evict it)
    private static final long CONTENT_GRACE_PERIOD_MS = 60000;
    // Auto re-launch Content if Lobby comes to foreground within this window
    // and Content was supposed to still be running
    private static final long CONTENT_AUTO_RELAUNCH_WINDOW_MS = 3600000;  // 1 hour

    private String currentContentPackage = DEFAULT_CONTENT_PACKAGE;

    private OkHttpClient client;
    private WebSocket webSocket;
    private Handler mainHandler;
    private boolean isRunning = false;
    private boolean contentLaunched = false;
    private boolean pendingCheckReconnect = false;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // No setContentView - VR rendering is handled by native OpenXR

        mainHandler = new Handler(Looper.getMainLooper());
        client = new OkHttpClient();

        FileLogger.start(this);

        // Start foreground service that runs the kiosk watchdog independently of Activity lifecycle.
        // Bypasses Android's background Handler throttling, keeping Meta-menu detection snappy.
        Intent kioskIntent = new Intent(this, KioskService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(kioskIntent);
        } else {
            startService(kioskIntent);
        }

        // Restore Content state across Activity recreation
        // (system may kill+recreate Lobby while Content is running)
        SharedPreferences savedPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedPkg = savedPrefs.getString(PREF_CONTENT_PACKAGE, null);
        if (savedPkg != null) {
            currentContentPackage = savedPkg;
            Log.d(TAG, "Restored currentContentPackage from prefs: " + savedPkg);
        }

        if (hasUsageStatsPermission()) {
            Log.d(TAG, "Kiosk mode: PACKAGE_USAGE_STATS granted ✓");
        } else {
            Log.w(TAG, "Kiosk mode: PACKAGE_USAGE_STATS NOT granted. Run: adb shell appops set " + getPackageName() + " GET_USAGE_STATS allow");
        }

        if (Environment.isExternalStorageManager()) {
            Log.d(TAG, "Storage: MANAGE_EXTERNAL_STORAGE granted ✓ (can read /sdcard/Pictures/...)");
        } else {
            Log.w(TAG, "Storage: MANAGE_EXTERNAL_STORAGE NOT granted. Run: adb shell appops set --uid " + getPackageName() + " MANAGE_EXTERNAL_STORAGE allow");
        }

        serverUrl = buildServerUrl();
        Log.d(TAG, "Server URL: " + serverUrl);

        nativeCreate();
        registerNetworkCallback();
        connectWebSocket();
    }

    @Override
    protected void onResume() {
        super.onResume();
        isRunning = true;

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean shouldRun = prefs.getBoolean(PREF_CONTENT_SHOULD_RUN, false);
        long launchedAt = prefs.getLong(PREF_CONTENT_LAUNCHED_AT, 0);
        long sinceLaunch = System.currentTimeMillis() - launchedAt;

        // Content was supposed to be running — try fast path first, fall back to server
        if (shouldRun && sinceLaunch < CONTENT_AUTO_RELAUNCH_WINDOW_MS) {
            String fg = getForegroundPackage();

            // (a) Content is already in foreground — Lobby brought up by mistake, leave alone
            if (fg != null && fg.equals(currentContentPackage)) {
                Log.d(TAG, "Content is in foreground (" + fg + "), Lobby sitting tight");
                contentLaunched = true;
                return;
            }

            // (b) Content not yet in foreground but recently launched — give it time
            if (sinceLaunch < KIOSK_LAUNCH_GRACE_MS) {
                Log.d(TAG, "Content still launching (" + sinceLaunch + "ms), waiting");
                contentLaunched = true;
                return;
            }

            // (c) FAST PATH: if Content process is still alive (Meta menu opened/dismissed,
            //     or Content just paused), instantly bring it back to foreground.
            //     No need to ask server — process exists, just resume it.
            if (isContentProcessAlive(currentContentPackage)) {
                String pkg = prefs.getString(PREF_CONTENT_PACKAGE, null);
                String extrasJson = prefs.getString(PREF_CONTENT_EXTRAS_JSON, null);
                if (pkg != null && extrasJson != null) {
                    Log.d(TAG, "Fast path: Content process alive, switching back to: " + pkg);
                    try {
                        JSONObject extras = new JSONObject(extrasJson);
                        relaunchContent(pkg, extras);
                        return;
                    } catch (Exception e) {
                        Log.e(TAG, "Fast path failed: " + e.getMessage());
                    }
                }
            }

            // (d) SLOW PATH: process is dead. Ask server if the room is still active.
            //     Server responds with headset_game_backend_command if active,
            //     silent if Lobby should stay idle.
            Log.d(TAG, "Content process dead — will query server (headset_check_reconnect)");
            pendingCheckReconnect = true;
        }

        // Connect WebSocket (also triggers headset_check_reconnect in onOpen if flagged)
        contentLaunched = false;
        if (webSocket == null) {
            connectWebSocket();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isRunning = false;
        // Watchdog is now handled by KioskService (foreground service) — see onCreate.
        // No need to start Activity-based watchdog here.
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
            networkCallback = null;
        }
        if (webSocket != null) {
            webSocket.close(1000, "App destroyed");
            webSocket = null;
        }
        nativeDestroy();
        FileLogger.stop();
    }

    private void registerNetworkCallback() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.d(TAG, "Network available");
                mainHandler.post(() -> {
                    // CRITICAL: only reconnect if Lobby is actually in foreground.
                    // Otherwise Content is the active client; reconnecting would
                    // evict Content from the server (same clientId conflict).
                    if (!isRunning) {
                        Log.d(TAG, "Network available, but Lobby is background — NOT reconnecting (Content owns clientId)");
                        return;
                    }
                    if (webSocket == null) {
                        connectWebSocket();
                    }
                });
            }

            @Override
            public void onLost(Network network) {
                Log.d(TAG, "Network lost");
            }
        };

        connectivityManager.registerNetworkCallback(request, networkCallback);
    }

    private String buildServerUrl() {
        String clientId = getClientId();
        // Per spec, VR headset URL must include roomId (default "1234" matches OLD UE).
        return "ws://" + SERVER_HOST + ":" + SERVER_PORT
                + "/?type=" + CLIENT_TYPE
                + "&clientId=" + clientId
                + "&roomId=" + roomId;
    }

    private String getClientId() {
        // Try every plausible location for the SN/ClientID, log result of each attempt.
        String[] candidates = {
            // App's own external dir — accessible without any permission
            new File(getExternalFilesDir(null), "HMDSN.txt").getAbsolutePath(),
            new File(getExternalFilesDir(null), "ClientID.txt").getAbsolutePath(),
            // Pictures dir — needs READ_EXTERNAL_STORAGE (may fail on Android 11+ scoped storage)
            new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                     "moonshineslam/ClientID.txt").getAbsolutePath(),
            new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                     "moonshineslam/HMDSN.jpg").getAbsolutePath(),
            // Direct /sdcard path (Quest may allow direct access)
            "/sdcard/Pictures/moonshineslam/HMDSN.jpg",
        };

        for (String path : candidates) {
            String value = tryReadFile(path);
            if (value != null) {
                Log.d(TAG, "ClientID loaded from " + path + ": " + value);
                return value;
            }
        }

        String ip = getDeviceIpv4();
        if (ip != null) {
            Log.d(TAG, "ClientID fallback to IPv4: " + ip);
            return ip;
        }
        String fallback = "VR_RandomUID_" + UUID.randomUUID().toString();
        Log.d(TAG, "ClientID fallback to random: " + fallback);
        return fallback;
    }

    private String tryReadFile(String path) {
        File file = new File(path);
        boolean exists = file.exists();
        boolean canRead = exists && file.canRead();
        if (!exists) {
            Log.d(TAG, "  [" + path + "] not exists");
            return null;
        }
        if (!canRead) {
            Log.d(TAG, "  [" + path + "] exists but not readable (permission denied?)");
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            if (line != null && !line.trim().isEmpty()) {
                return line.trim();
            }
            Log.d(TAG, "  [" + path + "] empty");
        } catch (Exception e) {
            Log.e(TAG, "  [" + path + "] read failed: " + e.getMessage());
        }
        return null;
    }

    private String getDeviceIpv4() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get IPv4: " + e.getMessage());
        }
        return null;
    }

    private void connectWebSocket() {
        Log.d(TAG, "Connecting to " + serverUrl);

        Request request = new Request.Builder().url(serverUrl).build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                Log.d(TAG, "WebSocket connected (as Lobby)");
                // If we returned from a previous Content session, ask server whether
                // the game is still active. Server replies with headset_game_backend_command
                // if active, silent otherwise.
                if (pendingCheckReconnect) {
                    pendingCheckReconnect = false;
                    sendCheckReconnect();
                }
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                Log.d(TAG, "Received: " + text);
                handleMessage(text);
            }

            @Override
            public void onMessage(WebSocket ws, ByteString bytes) {
                String text = bytes.utf8();
                Log.d(TAG, "Received (binary): " + text);
                handleMessage(text);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                Log.d(TAG, "WebSocket closed: " + reason);
                webSocket = null;
                scheduleReconnect();
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                Log.e(TAG, "WebSocket failed: " + t.getMessage());
                webSocket = null;
                scheduleReconnect();
            }
        });
    }

    private void handleMessage(String text) {
        try {
            JSONObject json = new JSONObject(text);
            String type = json.optString("type", "");

            if ("headset_game_backend_command".equals(type)) {
                handleGameBackendCommand(json);
            } else if ("init_player_info".equals(type)) {
                Log.d(TAG, "Player info received (ignored in Lobby): " + json.optJSONObject("data"));
            } else if ("welcome".equals(type) || "echo".equals(type)
                    || "ping".equals(type) || "pong".equals(type)) {
                // Connection lifecycle messages — no action needed
            } else {
                Log.w(TAG, "Unhandled message type: " + type);
            }
        } catch (Exception e) {
            Log.e(TAG, "Parse error: " + e.getMessage());
        }
    }

    private void handleGameBackendCommand(JSONObject json) {
        String command = json.optString("command", "");
        Log.d(TAG, "headset_game_backend_command: " + command);

        if ("connect".equals(command)) {
            JSONObject connectData = json.optJSONObject("connectData");
            if (connectData == null) {
                Log.e(TAG, "connect command missing connectData");
                return;
            }
            // Server may send "packageName" (new fiveg-local) or "execPath" (legacy)
            String pkg = connectData.optString("packageName", "");
            if (pkg.isEmpty()) pkg = connectData.optString("execPath", "");
            if (pkg.isEmpty()) {
                Log.e(TAG, "connectData has neither packageName nor execPath: " + connectData);
                pkg = DEFAULT_CONTENT_PACKAGE;
            }
            // Build UE-style command-line string (matches OLD UE Blueprint behavior — see 跳轉帶入.png)
            String gameIp = connectData.optString("ip", "");
            int gamePort = connectData.optInt("port", 0);
            String connectRoomId = connectData.optString("roomId", "");
            String mapName = connectData.optString("mapName", "");
            String cmdLine = "-serverip=" + gameIp + ":" + gamePort
                    + " -wsserverip=" + SERVER_HOST
                    + " -wsport=" + SERVER_PORT
                    + " -roomId=" + connectRoomId
                    + " -useMultiVRGis=true"
                    + " -mapName=" + mapName;
            Log.d(TAG, "Content cmdLine: " + cmdLine);

            // Intent extras for Content. The OLD UE Lobby uses key "Websocket" for the
            // full cmdLine string — confirmed in VRTemplateMap.umap binary. Content reads
            // intent.getStringExtra("Websocket") to get connection info.
            // userId / fromApp are from CustomVRPawn.uasset.
            JSONObject extras = new JSONObject();
            try {
                extras.put("Websocket", cmdLine);   // ★ THE KEY OLD UE USES
                extras.put("cmdLine", cmdLine);      // also pass under standard UE name (fallback)
                extras.put("userId", "12345");
                extras.put("fromApp", "hellLobby");
                // Individual fields kept for non-UE apps that may want them separately
                extras.put("serverip", gameIp + ":" + gamePort);
                extras.put("wsserverip", SERVER_HOST);
                extras.put("wsport", String.valueOf(SERVER_PORT));
                extras.put("roomId", connectRoomId);
                extras.put("useMultiVRGis", "true");
                extras.put("mapName", mapName);
                extras.put("ip", gameIp);
                extras.put("port", String.valueOf(gamePort));
                extras.put("playerheight", String.valueOf(connectData.optDouble("playerheight", 0)));
            } catch (Exception ignored) {}

            // Sync roomId for future reconnects
            String newRoomId = connectData.optString("roomId", "");
            if (!newRoomId.isEmpty()) {
                roomId = newRoomId;
                serverUrl = buildServerUrl();
            }

            launchContent(pkg, extras);
        } else if ("disconnect".equals(command)) {
            Log.d(TAG, "Disconnect command received — stopping Content");
            // Clear the "should be running" flag so we don't auto re-launch Content
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_CONTENT_SHOULD_RUN, false)
                .apply();
            stopContent();
        }
    }

    private static final long LAUNCH_DELAY_AFTER_DISCONNECT_MS = 300;

    private void launchContent(String pkg, JSONObject extras) {
        Log.d(TAG, "Try launch apk: " + pkg);

        if (!isAppInstalled(pkg)) {
            Log.e(TAG, "Can't find application or not installed: " + pkg);
            sendStatus("launch_failed_not_installed");
            return;
        }

        sendStatus("content_launched");
        contentLaunched = true;
        currentContentPackage = pkg;

        // Persist launch state so Lobby can:
        //  (a) skip reconnecting WebSocket while Content owns the clientId (grace period)
        //  (b) auto re-launch Content if Lobby restarts while Content was supposed to be running
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putLong(PREF_CONTENT_LAUNCHED_AT, System.currentTimeMillis())
            .putBoolean(PREF_CONTENT_SHOULD_RUN, true)
            .putString(PREF_CONTENT_PACKAGE, pkg)
            .putString(PREF_CONTENT_EXTRAS_JSON, extras != null ? extras.toString() : "{}")
            .apply();

        // Force-disconnect WebSocket BEFORE launching Content.
        // Content uses the same clientId — server must release the old session
        // first or Content's connection will hang in "connecting" state.
        if (webSocket != null) {
            webSocket.close(1000, "Launching Content");  // polite close frame
            webSocket.cancel();                          // immediate TCP teardown
            webSocket = null;
            Log.d(TAG, "WebSocket force-disconnected, waiting " + LAUNCH_DELAY_AFTER_DISCONNECT_MS
                    + "ms before launch (let server release clientId)");
        }

        // Delay so server has time to:
        // 1. Detect our TCP close
        // 2. Run WebSocket onClose handler
        // 3. Remove our client from clientManager
        // 4. Free up the clientId slot for Content to claim
        mainHandler.postDelayed(() -> {
            try {
                PackageManager pm = getPackageManager();
                Intent intent = pm.getLaunchIntentForPackage(pkg);
                if (intent == null) {
                    Log.e(TAG, "No launch intent for: " + pkg);
                    contentLaunched = false;
                    return;
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

                if (extras != null) {
                    Iterator<String> keys = extras.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        intent.putExtra(key, extras.optString(key));
                    }
                }

                startActivity(intent);
                Log.d(TAG, "Application launch successfully: " + pkg);
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch " + pkg + ": " + e.getMessage());
                contentLaunched = false;
            }
        }, LAUNCH_DELAY_AFTER_DISCONNECT_MS);
    }

    private void relaunchContent(String pkg, JSONObject extras) {
        // Lighter version of launchContent for auto-relaunch after Lobby restart.
        // Skips WebSocket disconnect (Lobby may not even be connected yet) and skips
        // updating SharedPreferences (the original state is still valid).
        if (!isAppInstalled(pkg)) {
            Log.e(TAG, "Auto re-launch: " + pkg + " not installed, clearing flag");
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_CONTENT_SHOULD_RUN, false)
                .apply();
            return;
        }
        contentLaunched = true;
        currentContentPackage = pkg;
        try {
            PackageManager pm = getPackageManager();
            Intent intent = pm.getLaunchIntentForPackage(pkg);
            if (intent == null) {
                Log.e(TAG, "Auto re-launch: no launch intent for " + pkg);
                contentLaunched = false;
                return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            Iterator<String> keys = extras.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                intent.putExtra(key, extras.optString(key));
            }
            startActivity(intent);
            Log.d(TAG, "Auto re-launch successful: " + pkg);
        } catch (Exception e) {
            Log.e(TAG, "Auto re-launch failed: " + e.getMessage());
            contentLaunched = false;
        }
    }

    private void stopContent() {
        // Bring Lobby back to foreground — Content gets paused
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);

        // Per spec section 8: notify server that game is closed
        sendType("game_closed");
    }

    private void sendType(String type) {
        if (webSocket == null) return;
        try {
            JSONObject json = new JSONObject();
            json.put("type", type);
            webSocket.send(json.toString());
            Log.d(TAG, "Sent: " + json);
        } catch (Exception e) {
            Log.e(TAG, "Send failed: " + e.getMessage());
        }
    }

    /**
     * Ask server whether our headsetId is still in an active game room.
     * Server replies with headset_game_backend_command if game is still active
     * (Lobby will re-launch Content via handleGameBackendCommand).
     * Server stays silent if Lobby should remain idle.
     * Spec: fiveg-local/src/handlers/headset-check-reconnect.ts
     */
    private void sendCheckReconnect() {
        if (webSocket == null) return;
        try {
            JSONObject json = new JSONObject();
            json.put("type", "headset_check_reconnect");
            json.put("headsetId", getClientId());
            webSocket.send(json.toString());
            Log.d(TAG, "Sent headset_check_reconnect: " + json);
        } catch (Exception e) {
            Log.e(TAG, "headset_check_reconnect send failed: " + e.getMessage());
        }
    }

    private boolean isAppInstalled(String pkg) {
        PackageManager pm = getPackageManager();
        boolean installed = pm.getLaunchIntentForPackage(pkg) != null;
        Log.d(TAG, "Application " + pkg + " " + (installed ? "has installed" : "not installed"));
        return installed;
    }

    private void sendStatus(String status) {
        if (webSocket != null) {
            try {
                JSONObject json = new JSONObject();
                json.put("status", status);
                webSocket.send(json.toString());
            } catch (Exception e) {
                Log.e(TAG, "Send failed: " + e.getMessage());
            }
        }
    }

    private void scheduleReconnect() {
        if (!isRunning) return;
        mainHandler.postDelayed(() -> {
            if (isRunning && webSocket == null) {
                connectWebSocket();
            }
        }, RECONNECT_DELAY_MS);
    }

    private void startKioskWatchdog() {
        mainHandler.postDelayed(this::kioskCheck, KIOSK_CHECK_INTERVAL_MS);
    }

    private void kioskCheck() {
        if (isRunning) return; // Lobby is in foreground per internal flag, no action

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        long launchedAt = prefs.getLong(PREF_CONTENT_LAUNCHED_AT, 0);
        long sinceLaunch = System.currentTimeMillis() - launchedAt;

        // Grace period — don't interfere during Content's initial startup
        if (sinceLaunch < KIOSK_LAUNCH_GRACE_MS) {
            mainHandler.postDelayed(this::kioskCheck, KIOSK_CHECK_INTERVAL_MS);
            return;
        }

        String fg = getForegroundPackage();

        // Foreground unknown (null) — happens with UE native rendering apps that don't
        // trigger normal MOVE_TO_FOREGROUND events. Don't assume anything, just monitor.
        if (fg == null) {
            mainHandler.postDelayed(this::kioskCheck, KIOSK_CHECK_INTERVAL_MS);
            return;
        }

        // Content already in foreground → just keep monitoring
        if (fg.equals(currentContentPackage)) {
            mainHandler.postDelayed(this::kioskCheck, KIOSK_CHECK_INTERVAL_MS);
            return;
        }

        // Lobby already in foreground (state mismatch with isRunning flag) → just monitor
        if (fg.equals(getPackageName())) {
            mainHandler.postDelayed(this::kioskCheck, KIOSK_CHECK_INTERVAL_MS);
            return;
        }

        boolean shouldRun = prefs.getBoolean(PREF_CONTENT_SHOULD_RUN, false);

        // Process alive → bring Content directly back (no Lobby flash, no WS conflict)
        if (shouldRun && isContentProcessAlive(currentContentPackage)) {
            Log.d(TAG, "Kiosk FAST: fg=" + fg + ", Content alive, bringing Content back");
            try {
                PackageManager pm = getPackageManager();
                Intent intent = pm.getLaunchIntentForPackage(currentContentPackage);
                if (intent != null) {
                    // FLAG_ACTIVITY_REORDER_TO_FRONT: if Content's task exists, just bring it
                    //   to top (no recreate, faster, no flash)
                    // FLAG_ACTIVITY_SINGLE_TOP: don't create new instance if it's already on top
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                    mainHandler.postDelayed(this::kioskCheck, KIOSK_CHECK_INTERVAL_MS);
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "Kiosk FAST failed: " + e.getMessage());
            }
        }

        Log.d(TAG, "Kiosk SLOW: fg=" + fg + ", Content dead — bringing Lobby back");

        // SLOW PATH: process dead → bring Lobby back (Lobby asks server)
        Log.d(TAG, "Kiosk SLOW: bringing Lobby back");
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    /**
     * Check if Content's process is still alive (paused/background still counts as alive,
     * only really dead processes return false). Uses `pidof` via Runtime.exec.
     */
    private boolean isContentProcessAlive(String pkg) {
        // Try multiple approaches because pidof may have PATH or permission issues on some devices
        String pid = tryShell("pidof " + pkg);
        if (pid != null && !pid.isEmpty()) {
            Log.d(TAG, "Process check " + pkg + ": alive via pidof (pid=" + pid + ")");
            return true;
        }
        // Fallback: ps + grep
        String psResult = tryShell("ps -A 2>/dev/null | grep -F " + pkg);
        if (psResult != null && !psResult.isEmpty()) {
            Log.d(TAG, "Process check " + pkg + ": alive via ps");
            return true;
        }
        Log.d(TAG, "Process check " + pkg + ": dead (no pid found)");
        return false;
    }

    private String tryShell(String cmd) {
        try {
            java.lang.Process proc = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            proc.waitFor();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream())
            );
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (Exception e) {
            Log.e(TAG, "shell '" + cmd + "' failed: " + e.getMessage());
            return null;
        }
    }

    private boolean isContentRunning() {
        String fg = getForegroundPackage();
        if (fg == null) {
            // No permission or no data — assume content is running to avoid loop
            return true;
        }
        boolean isContent = currentContentPackage.equals(fg);
        Log.d(TAG, "Foreground package: " + fg + " (isContent=" + isContent + ")");
        return isContent;
    }

    private String getForegroundPackage() {
        return queryForegroundPackage(this);
    }

    /** Static so KioskService can share the same logic. */
    public static String queryForegroundPackage(Context ctx) {
        AppOpsManager appOps = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                ctx.getPackageName());
        if (mode != AppOpsManager.MODE_ALLOWED) return null;

        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        long end = System.currentTimeMillis();
        long begin = end - 1000L * 5;

        UsageEvents events = usm.queryEvents(begin, end);
        UsageEvents.Event ev = new UsageEvents.Event();
        String latestPackage = null;
        long latestTime = 0;
        while (events.hasNextEvent()) {
            events.getNextEvent(ev);
            if (ev.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND
                    && ev.getTimeStamp() > latestTime) {
                latestTime = ev.getTimeStamp();
                latestPackage = ev.getPackageName();
            }
        }
        return latestPackage;
    }

    private boolean hasUsageStatsPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }
}
