package com.quest.lobby;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
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
    // Client identity (clientId / headsetId) sent to the server AND passed to Content — now
    // the device IPv4 instead of the headset SN. Cached so every use is the exact same value.
    private String cachedClientId;

    private static final long RECONNECT_DELAY_MS = 3000;

    static {
        System.loadLibrary("lobby_vr");
    }

    private native void nativeCreate();
    private native void nativeDestroy();
    // Hand the decoded 360 panorama (RGBA8, row 0 = top) to the native render thread.
    private native void nativeSetPanorama(int width, int height, java.nio.ByteBuffer pixels);

    // Shared with KioskService so the watchdog's default target matches this Activity's
    // default before SharedPreferences (content_package) has been written by launchContent().
    static final String DEFAULT_CONTENT_PACKAGE = "com.quest.content";
    // Skip watchdog checks during Content's initial startup so we don't fight with it
    private static final long KIOSK_LAUNCH_GRACE_MS = 8000;
    private static final String PREFS = "lobby_state";
    private static final String PREF_CONTENT_LAUNCHED_AT = "content_launched_at";
    private static final String PREF_CONTENT_SHOULD_RUN = "content_should_run";
    private static final String PREF_CONTENT_PACKAGE = "content_package";
    private static final String PREF_CONTENT_EXTRAS_JSON = "content_extras_json";
    // Set true ONLY after the server confirms this run's session (connect command → launchContent).
    // Reset on disconnect, on check-reconnect timeout, and on a fresh Activity/process start.
    // KioskService will only hold Content in the foreground while this is true — so a stale
    // content_should_run=true from a previous run can never make the watchdog jump to Content
    // before the server has re-confirmed. Shared literal key with KioskService.
    static final String PREF_CONTENT_SESSION_CONFIRMED = "content_session_confirmed";
    // Give up waiting for the server's connect reply after this long → clear stale state, stay in Lobby.
    private static final long CHECK_RECONNECT_TIMEOUT_MS = 8000;
    // Skip reconnecting WebSocket for this long after launching Content
    // (Content owns the clientId during this window — Lobby reconnecting would evict it)
    private static final long CONTENT_GRACE_PERIOD_MS = 60000;
    // Auto re-launch Content if Lobby comes to foreground within this window
    // and Content was supposed to still be running
    private static final long CONTENT_AUTO_RELAUNCH_WINDOW_MS = 3600000;  // 1 hour

    private String currentContentPackage = DEFAULT_CONTENT_PACKAGE;

    private OkHttpClient client;
    private volatile WebSocket webSocket;
    private Handler mainHandler;
    private boolean contentLaunched = false;
    private boolean pendingCheckReconnect = false;
    private boolean usageStatsSettingsRequested = false;
    private boolean storageSettingsRequested = false;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    // ── WebSocket / network-migration state machine ─────────────────────────────
    // socketState tracks the ONE live socket; there is never more than one at a time.
    //   DISCONNECTED → CONNECTING (connectWebSocket) → CONNECTED (onOpen)
    //   any state → DISCONNECTED (onClosed/onFailure/migration/manual close)
    private enum SocketState { DISCONNECTED, CONNECTING, CONNECTED }
    private volatile SocketState socketState = SocketState.DISCONNECTED;
    // IPv4 the in-flight connect is based on; promoted to connectedSocketIpv4 only on onOpen.
    private volatile String pendingSocketIpv4;
    // IPv4 the currently-CONNECTED socket was actually established on (confirmed by onOpen).
    private volatile String connectedSocketIpv4;
    // Last IPv4 we started a (re)connection for — used to log migrations and detect IP changes.
    private String lastConnectedIpv4;
    // The network we currently believe is active, refreshed from getActiveNetwork().
    private Network activeNetwork;
    private int networkIpRetryCount = 0;
    private static final long NETWORK_DEBOUNCE_MS = 1000;
    private static final long NETWORK_IP_RETRY_MS = 1000;
    private static final int NETWORK_IP_MAX_RETRIES = 15;

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

        // Fresh process/Activity start: the session is NOT server-confirmed yet. Force the flag
        // false so the watchdog won't hold a stale Content in front before onResume has re-asked
        // the server. It is set true again only when a server connect command launches Content.
        savedPrefs.edit().putBoolean(PREF_CONTENT_SESSION_CONFIRMED, false).apply();

        enableLockTaskWhitelist();

        serverUrl = buildServerUrl();
        Log.d(TAG, "Server URL: " + serverUrl);

        loadPanorama();   // stash pixels before the render thread starts
        nativeCreate();
        registerNetworkCallback();
        connectWebSocket();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!hasUsageStatsPermission() && !usageStatsSettingsRequested) {
            usageStatsSettingsRequested = true;
            checkAndRequestUsageStats();
            return;
        }
        if (!hasAllFilesAccess() && !storageSettingsRequested) {
            storageSettingsRequested = true;
            checkAndRequestStoragePermission();
            return;
        }

        startKioskLockTask();

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean shouldRun = prefs.getBoolean(PREF_CONTENT_SHOULD_RUN, false);
        long launchedAt = prefs.getLong(PREF_CONTENT_LAUNCHED_AT, 0);
        long sinceLaunch = System.currentTimeMillis() - launchedAt;

        // A previous Content session was flagged "should be running". Content's start authority
        // belongs to the SERVER ONLY — never relaunch Content locally from SharedPreferences or
        // from "process is still alive". Ask the server (headset_check_reconnect) and let it
        // decide: it replies with a connect command if the game is still active (then we
        // launchContent), or stays silent → the check-reconnect timeout clears the stale state
        // and we stay in the Lobby.
        if (shouldRun && sinceLaunch < CONTENT_AUTO_RELAUNCH_WINDOW_MS) {
            String fg = getForegroundPackage();
            // Even if Content is already in front, do NOT relaunch — but still ask the server so
            // the session gets re-confirmed (content_session_confirmed) before the watchdog will
            // hold Content in front again.
            if (fg != null && fg.equals(currentContentPackage)) {
                Log.d(TAG, "Content already in foreground; leaving it alone, will confirm with server");
            }
            Log.d(TAG, "Previous Content session found — asking server before any relaunch (no local fast-path)");
            pendingCheckReconnect = true;
        }

        // Connect WebSocket (onOpen sends headset_check_reconnect when pendingCheckReconnect is set).
        contentLaunched = false;
        if (webSocket == null) {
            connectWebSocket();
        } else if (pendingCheckReconnect && socketState == SocketState.CONNECTED) {
            // Already connected — onOpen won't fire again, so ask the server right now.
            sendCheckReconnect();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Watchdog is handled solely by KioskService (foreground service) — see onCreate.
        // The Activity intentionally runs NO watchdog, so there is only one watchdog owner.
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacks(networkReconnectRunnable);
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.removeCallbacks(checkReconnectTimeoutRunnable);
        if (networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
            networkCallback = null;
        }
        if (webSocket != null) {
            webSocket.close(1000, "App destroyed");
            webSocket = null;
        }
        socketState = SocketState.DISCONNECTED;
        nativeDestroy();
        FileLogger.stop();
    }

    private void registerNetworkCallback() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            // A Wi-Fi / subnet migration rarely presents cleanly as a single onAvailable. We
            // therefore react to EVERY transport/link signal and let handleNetworkChanged()
            // debounce the flurry into one reconnect decision (networkReconnectRunnable), which
            // compares the CURRENT IPv4 against the connected one and only rebuilds when it moved.
            @Override
            public void onAvailable(Network network) {
                Log.d(TAG, "onAvailable: " + network);
                refreshActiveNetwork();
                handleNetworkChanged("onAvailable");
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                boolean wifi = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                boolean validated = caps != null
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                refreshActiveNetwork();
                handleNetworkChanged("onCapabilitiesChanged(wifi=" + wifi + ",validated=" + validated + ")");
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                // Route/interface/address changes (e.g. DHCP hands out a new IPv4 on the same
                // Network object) surface here even when no onAvailable fires.
                refreshActiveNetwork();
                handleNetworkChanged("onLinkPropertiesChanged");
            }

            @Override
            public void onLost(Network network) {
                // Do NOT blindly tear down: during a Wi-Fi switch the NEW network is often up and
                // active before the OLD one reports lost. Only react if the network that dropped
                // was our active one, or if nothing usable remains.
                Network current = (connectivityManager != null)
                        ? connectivityManager.getActiveNetwork() : null;
                boolean lostActive = network.equals(activeNetwork);
                Log.d(TAG, "onLost: " + network + " (active=" + activeNetwork
                        + ", nowActive=" + current + ", lostActive=" + lostActive + ")");
                if (current == null) {
                    activeNetwork = null;
                    handleNetworkChanged("onLost(no-active-remaining)");
                } else if (lostActive) {
                    activeNetwork = current;
                    handleNetworkChanged("onLost(active-migrated)");
                } else {
                    Log.d(TAG, "onLost: a non-active network dropped — keeping current socket");
                }
            }
        };

        connectivityManager.registerNetworkCallback(request, networkCallback);
    }

    private void refreshActiveNetwork() {
        if (connectivityManager != null) {
            activeNetwork = connectivityManager.getActiveNetwork();
        }
    }

    /** Debounce a burst of network callbacks into a single reconnect decision. */
    private void handleNetworkChanged(String reason) {
        Log.d(TAG, "Network changed (" + reason + ")");
        mainHandler.removeCallbacks(networkReconnectRunnable);
        mainHandler.postDelayed(networkReconnectRunnable, NETWORK_DEBOUNCE_MS);
    }

    /**
     * Settled network-change handler. Decides whether the current IPv4 warrants a full WebSocket
     * migration. Always runs on the main thread (posted via handleNetworkChanged).
     */
    private final Runnable networkReconnectRunnable = new Runnable() {
        @Override
        public void run() {
            // Content owns the shared clientId while it runs — the Lobby must not reconnect and
            // evict it, even across a network change. Re-evaluate once Content is gone.
            if (contentOwnsClientId()) {
                Log.d(TAG, "Network changed but Content owns clientId; Lobby will not reconnect");
                return;
            }

            String currentIpv4 = getDeviceIpv4();
            if (currentIpv4 == null || currentIpv4.isEmpty()) {
                Log.w(TAG, "Network changed but IPv4 is not ready yet");
                scheduleNetworkIpRetry();
                return;
            }
            networkIpRetryCount = 0;

            // Nothing actually moved and we already have a live socket → no-op (avoids the
            // needless teardown/reconnect that a stream of capability callbacks would cause).
            if (currentIpv4.equals(connectedSocketIpv4) && socketState == SocketState.CONNECTED) {
                Log.d(TAG, "Network callback received but IPv4 and WebSocket are unchanged ("
                        + currentIpv4 + ")");
                return;
            }

            reconnectForNewIpv4(currentIpv4);
        }
    };

    /** IPv4 not assigned yet after a network event — retry the settled handler a few times. */
    private void scheduleNetworkIpRetry() {
        if (networkIpRetryCount >= NETWORK_IP_MAX_RETRIES) {
            Log.w(TAG, "IPv4 still not ready after " + networkIpRetryCount
                    + " retries; waiting for next network event");
            networkIpRetryCount = 0;
            return;
        }
        networkIpRetryCount++;
        mainHandler.removeCallbacks(networkReconnectRunnable);
        mainHandler.postDelayed(networkReconnectRunnable, NETWORK_IP_RETRY_MS);
    }

    /**
     * Full network migration: drop the old socket, forget the cached clientId, rebuild the URL
     * from the NEW IPv4, and open a fresh socket. Old-socket callbacks that arrive after this are
     * ignored via the {@code webSocket != ws} stale check in the listener.
     */
    private void reconnectForNewIpv4(String newIpv4) {
        Log.d(TAG, "Network migration: " + lastConnectedIpv4 + " -> " + newIpv4);

        // Cancel any pending plain reconnect so it can't race the migration.
        mainHandler.removeCallbacks(reconnectRunnable);

        WebSocket oldSocket = webSocket;
        webSocket = null;
        socketState = SocketState.DISCONNECTED;
        connectedSocketIpv4 = null;
        if (oldSocket != null) {
            try {
                oldSocket.cancel();   // immediate teardown; its later callbacks are stale-checked
            } catch (Exception ignored) {
            }
        }

        // Force getClientId() to re-read the NEW IPv4 so buildServerUrl() can't reuse the old one.
        cachedClientId = null;
        serverUrl = buildServerUrl();
        lastConnectedIpv4 = newIpv4;

        connectWebSocket();
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
        if (cachedClientId != null) {
            return cachedClientId;
        }
        // Client identity = device IPv4 address (previously the headset SN).
        String ip = getDeviceIpv4();
        if (ip != null) {
            Log.d(TAG, "ClientID = IPv4: " + ip);
            cachedClientId = ip;
            return ip;
        }
        // No IPv4 yet (network down). Use a random id but DON'T cache it, so a later call
        // can still pick up the real IPv4 once the network is up.
        String fallback = "VR_RandomUID_" + UUID.randomUUID().toString();
        Log.d(TAG, "ClientID fallback to random (no IPv4 yet): " + fallback);
        return fallback;
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
        // The IPv4 this socket is being opened on. Held pending until onOpen confirms success —
        // we must NOT treat a not-yet-open IP as the connected one (see onOpen).
        pendingSocketIpv4 = getDeviceIpv4();
        socketState = SocketState.CONNECTING;

        // Diagnostics for cross-subnet reachability debugging (issue #8): if the current IPv4 is
        // on a subnet with no route to SERVER_HOST, the connect below will fail — this makes the
        // "why can't it reach 192.168.99.200" case obvious in logcat.
        Log.d(TAG, "Connecting WebSocket"
                + " | currentIPv4=" + pendingSocketIpv4
                + " | server=" + SERVER_HOST + ":" + SERVER_PORT
                + " | activeNetwork=" + activeNetwork
                + " | url=" + serverUrl);

        Request request = new Request.Builder().url(serverUrl).build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                if (webSocket != ws) { Log.d(TAG, "Ignoring onOpen from stale WebSocket"); return; }
                socketState = SocketState.CONNECTED;
                // Only NOW is the new IPv4 actually connected — promote pending → connected.
                connectedSocketIpv4 = pendingSocketIpv4;
                if (connectedSocketIpv4 != null) lastConnectedIpv4 = connectedSocketIpv4;
                Log.d(TAG, "WebSocket connected (as Lobby) on IPv4=" + connectedSocketIpv4);
                // If we returned from a previous Content session, ask server whether the game is
                // still active. Server replies with headset_game_backend_command + connect if
                // active, silent otherwise. Do NOT clear pendingCheckReconnect here — it stays set
                // until either the connect command arrives or the check-reconnect timeout fires
                // (sendCheckReconnect arms that timeout).
                if (pendingCheckReconnect) {
                    sendCheckReconnect();
                }
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                if (webSocket != ws) { Log.d(TAG, "Ignoring onMessage from stale WebSocket"); return; }
                Log.d(TAG, "Received: " + text);
                handleMessage(text);
            }

            @Override
            public void onMessage(WebSocket ws, ByteString bytes) {
                if (webSocket != ws) return;  // stale
                String text = bytes.utf8();
                Log.d(TAG, "Received (binary): " + text);
                handleMessage(text);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                // A migration/manual-close already replaced or nulled webSocket — ignore the old
                // socket's late callback so it can't clobber the new socket's state.
                if (webSocket != ws) { Log.d(TAG, "Ignoring onClosed from stale WebSocket"); return; }
                Log.d(TAG, "WebSocket closed: " + reason);
                webSocket = null;
                socketState = SocketState.DISCONNECTED;
                connectedSocketIpv4 = null;
                scheduleReconnect();
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                if (webSocket != ws) { Log.d(TAG, "Ignoring onFailure from stale WebSocket"); return; }
                // Log the exception TYPE (issue #8): ConnectException/SocketTimeoutException here
                // typically means the current subnet has no route to SERVER_HOST.
                Log.e(TAG, "WebSocket failed: " + t.getClass().getSimpleName() + ": " + t.getMessage()
                        + " | currentIPv4=" + getDeviceIpv4()
                        + " | server=" + SERVER_HOST + ":" + SERVER_PORT
                        + " | url=" + serverUrl);
                webSocket = null;
                socketState = SocketState.DISCONNECTED;
                connectedSocketIpv4 = null;
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
            // Server has authoritatively confirmed this session → cancel the check-reconnect
            // timeout and clear the pending flag so it can't later clear our now-valid state.
            mainHandler.removeCallbacks(checkReconnectTimeoutRunnable);
            pendingCheckReconnect = false;

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
            // NOTE: clientId is intentionally NOT passed to Content. Matching the OLD UE flow,
            // Content derives its own identity (device IPv4, formerly the SN) independently —
            // same device → same IPv4 → same clientId as the Lobby. Passing an extra -clientId
            // flag deviates from the UE format and can crash Content's command-line parsing.
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
                extras.put("CommandLine", cmdLine);  // compatibility with alternate UE builds
                extras.put("cmdLine", cmdLine);      // compatibility with previous Lobby builds
                extras.put("cmdline", cmdLine);      // this UE GameActivity / old Lobby (case-sensitive)
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
            // Clear the "should be running" flag AND the session-confirmed flag so neither the
            // watchdog nor a later onResume treats the old session as still valid.
            mainHandler.removeCallbacks(checkReconnectTimeoutRunnable);
            pendingCheckReconnect = false;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_CONTENT_SHOULD_RUN, false)
                .putBoolean(PREF_CONTENT_SESSION_CONFIRMED, false)
                .apply();
            stopContent();
        }
    }

    private static final long LAUNCH_DELAY_AFTER_DISCONNECT_MS = 3000;

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
            .putBoolean(PREF_CONTENT_SESSION_CONFIRMED, true)
            .putString(PREF_CONTENT_PACKAGE, pkg)
            .putString(PREF_CONTENT_EXTRAS_JSON, extras != null ? extras.toString() : "{}")
            .apply();

        // Diagnostics: everything needed to debug the "1st jump fails, 2nd succeeds" clientId issue.
        Log.d(TAG, "launchContent DIAG"
                + " | LobbyClientId=" + getClientId()
                + " | contentPackage=" + pkg
                + " | Websocket=" + (extras != null ? extras.optString("Websocket") : "")
                + " | cmdline=" + (extras != null ? extras.optString("cmdline") : "")
                + " | cmdLine=" + (extras != null ? extras.optString("cmdLine") : "")
                + " | serverip=" + (extras != null ? extras.optString("serverip") : "")
                + " | wsserverip=" + (extras != null ? extras.optString("wsserverip") : "")
                + " | wsport=" + (extras != null ? extras.optString("wsport") : "")
                + " | roomId=" + (extras != null ? extras.optString("roomId") : "")
                + " | mapName=" + (extras != null ? extras.optString("mapName") : "")
                + " | launchDelayMs=" + LAUNCH_DELAY_AFTER_DISCONNECT_MS);

        // Debug: launch target / delay / full extras JSON before the delayed startActivity.
        Log.d(TAG, "Launch target package: " + pkg);
        Log.d(TAG, "Launch delay ms: " + LAUNCH_DELAY_AFTER_DISCONNECT_MS);
        Log.d(TAG, "Launch extras json: " + (extras != null ? extras.toString() : "null"));

        // Force-disconnect WebSocket BEFORE launching Content. Content reuses the SAME clientId
        // (device IPv4), so the server MUST see the Lobby disconnect and free that clientId first,
        // otherwise Content's connect (same clientId) is rejected as a duplicate and never connects.
        //
        // We do close(1000) AND cancel(): close() sends the polite close frame, cancel() then
        // forcibly tears down the TCP (RST). The cancel() is essential — fiveg-local does not
        // reliably echo a close frame, so a graceful-only close() can leave the TCP ESTABLISHED,
        // the server keeps thinking the Lobby clientId is connected, and Content can never connect.
        // The delay below then gives the server time to run its onClose and free the clientId.
        if (webSocket != null) {
            WebSocket ws = webSocket;
            webSocket = null;                            // null FIRST so late callbacks are stale
            socketState = SocketState.DISCONNECTED;
            connectedSocketIpv4 = null;
            boolean closing = ws.close(1000, "Launching Content");   // polite close frame
            ws.cancel();                                             // force TCP teardown (RST)
            Log.d(TAG, "WebSocket close(initiated=" + closing + ")+cancel @" + System.currentTimeMillis()
                    + ", waiting " + LAUNCH_DELAY_AFTER_DISCONNECT_MS
                    + "ms before launch (let server release clientId)");
        } else {
            Log.d(TAG, "No active Lobby WebSocket to close before launch");
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

                // Debug: dump the ACTUAL extras attached to the Intent Content will receive.
                Bundle debugExtras = intent.getExtras();
                if (debugExtras != null) {
                    for (String key : debugExtras.keySet()) {
                        Log.d(TAG, "Intent extra to Content: " + key + "=" + debugExtras.get(key));
                    }
                } else {
                    Log.w(TAG, "Intent extras to Content are null");
                }

                startActivity(intent);
                Log.d(TAG, "Application launch successfully: " + pkg + " @" + System.currentTimeMillis()
                        + " (clientId released " + LAUNCH_DELAY_AFTER_DISCONNECT_MS + "ms earlier)");

                // Debug: confirm Content's process actually came up (helps diagnose the
                // "dead after ~1 minute" report — did it ever exist?). Check at +3s and +10s.
                mainHandler.postDelayed(() ->
                        Log.d(TAG, "Content process alive after launch (+3s)? " + isContentProcessAlive(pkg)), 3000);
                mainHandler.postDelayed(() ->
                        Log.d(TAG, "Content process alive after launch (+10s)? " + isContentProcessAlive(pkg)), 10000);
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch " + pkg + ": " + e.getMessage());
                contentLaunched = false;
            }
        }, LAUNCH_DELAY_AFTER_DISCONNECT_MS);
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
            // Arm the server-response timeout. If no connect command arrives within
            // CHECK_RECONNECT_TIMEOUT_MS, the runnable clears the stale state and we stay in Lobby.
            mainHandler.removeCallbacks(checkReconnectTimeoutRunnable);
            mainHandler.postDelayed(checkReconnectTimeoutRunnable, CHECK_RECONNECT_TIMEOUT_MS);
        } catch (Exception e) {
            Log.e(TAG, "headset_check_reconnect send failed: " + e.getMessage());
        }
    }

    /**
     * Fired when the server does not answer headset_check_reconnect with a connect command in time.
     * Server silence means "no active game" → drop the stale Content session and stay in the Lobby.
     */
    private final Runnable checkReconnectTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (pendingCheckReconnect) {
                Log.d(TAG, "headset_check_reconnect timeout — server did not confirm; staying in Lobby");
                clearContentRuntimeState("check-reconnect-timeout");
            }
        }
    };

    /**
     * Forget any locally-remembered Content session. After this, KioskService sees
     * content_should_run=false / content_session_confirmed=false and keeps the Lobby in front;
     * nothing relaunches Content until a fresh server connect command arrives.
     */
    private void clearContentRuntimeState(String reason) {
        Log.d(TAG, "Clearing Content runtime state: " + reason);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_CONTENT_SHOULD_RUN, false)
                .putBoolean(PREF_CONTENT_SESSION_CONFIRMED, false)
                .remove(PREF_CONTENT_PACKAGE)
                .remove(PREF_CONTENT_EXTRAS_JSON)
                .remove(PREF_CONTENT_LAUNCHED_AT)
                .apply();
        contentLaunched = false;
        currentContentPackage = DEFAULT_CONTENT_PACKAGE;
        pendingCheckReconnect = false;
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

    // Plain periodic reconnect (server unreachable / transient drop). Named so a network
    // migration can cancel a queued attempt before starting its own fresh connect.
    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (webSocket == null && !contentOwnsClientId()) {
                // Re-read IPv4 first: the drop may itself have been a network change, so rebuild
                // the URL from the current IPv4 rather than reconnecting on a stale one.
                String currentIpv4 = getDeviceIpv4();
                if (currentIpv4 != null && !currentIpv4.equals(lastConnectedIpv4)) {
                    cachedClientId = null;
                    serverUrl = buildServerUrl();
                    lastConnectedIpv4 = currentIpv4;
                }
                connectWebSocket();
            }
        }
    };

    private void scheduleReconnect() {
        // Keep retrying unless Content is genuinely running (it owns the clientId then).
        // When the server is simply unreachable (e.g. current Wi-Fi has no route to SERVER_HOST),
        // this keeps us in DISCONNECTED and retries; a later network migration to a reachable
        // subnet reconnects automatically.
        if (contentOwnsClientId()) return;
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS);
    }

    /**
     * True only when Content was launched AND its process is still alive — i.e. Content is the
     * legitimate owner of the shared clientId, so the Lobby must NOT reconnect and evict it.
     * When Content has died/crashed (or was never launched), the Lobby should reconnect freely.
     */
    private boolean contentOwnsClientId() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_CONTENT_SHOULD_RUN, false)) return false;
        // During Content's startup window it OWNS the clientId even before its process is
        // detectable — otherwise the Lobby would reconnect its WebSocket with the same clientId
        // and evict the Content that is still coming up (Content then fails to connect / crashes).
        long launchedAt = prefs.getLong(PREF_CONTENT_LAUNCHED_AT, 0);
        if (System.currentTimeMillis() - launchedAt < KIOSK_LAUNCH_GRACE_MS) return true;
        // After the startup window, only if the process is actually alive.
        return isContentProcessAlive(currentContentPackage);
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

    private String getForegroundPackage() {
        return queryForegroundPackage(this);
    }

    /** Latest foreground app together with the timestamp of its MOVE_TO_FOREGROUND event. */
    public static class Foreground {
        public final String pkg;
        public final long time;
        Foreground(String pkg, long time) { this.pkg = pkg; this.time = time; }
    }

    /** Static so KioskService can share the same logic. */
    public static String queryForegroundPackage(Context ctx) {
        Foreground fg = queryForeground(ctx);
        return fg == null ? null : fg.pkg;
    }

    /**
     * Latest foreground event (package + timestamp), or null if unavailable. The timestamp
     * lets callers tell one Meta-menu open from the next: each open is a fresh event, so a
     * newer timestamp means a new intrusion even if REORDER_TO_FRONT logged no return event.
     */
    public static Foreground queryForeground(Context ctx) {
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
        return latestPackage == null ? null : new Foreground(latestPackage, latestTime);
    }

    /** Decode assets/scene.png and hand its pixels to native as the VR background. */
    private void loadPanorama() {
        try (java.io.InputStream is = getAssets().open("scene.png")) {
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(is);
            if (bmp == null) {
                Log.e(TAG, "Panorama decode failed (scene.png)");
                return;
            }
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            // ARGB_8888 copyPixelsToBuffer yields R,G,B,A byte order — matches GL_RGBA.
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(w * h * 4);
            bmp.copyPixelsToBuffer(buf);
            buf.rewind();
            nativeSetPanorama(w, h, buf);   // native copies synchronously
            bmp.recycle();
            Log.d(TAG, "Panorama loaded: " + w + "x" + h);
        } catch (Exception e) {
            Log.e(TAG, "loadPanorama failed: " + e.getMessage());
        }
    }

    /**
     * If this app is a device owner, whitelist itself for Lock Task so startLockTask()
     * pins silently (no confirmation, user can't leave). Set device owner once via:
     *   adb shell dpm set-device-owner com.quest.lobby/.LobbyAdminReceiver
     */
    private void enableLockTaskWhitelist() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                ComponentName admin = new ComponentName(this, LobbyAdminReceiver.class);
                dpm.setLockTaskPackages(admin, new String[]{ getPackageName() });
                Log.d(TAG, "Lock task: device owner ✓, package whitelisted (silent kiosk)");
            } else {
                Log.w(TAG, "Lock task: NOT device owner — startLockTask() will prompt / be escapable. "
                        + "Run: adb shell dpm set-device-owner " + getPackageName() + "/.LobbyAdminReceiver");
            }
        } catch (Exception e) {
            Log.e(TAG, "enableLockTaskWhitelist failed: " + e.getMessage());
        }
    }

    /** Enter Lock Task (screen pinning / kiosk) if not already in it. */
    private void startKioskLockTask() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null && am.getLockTaskModeState() == ActivityManager.LOCK_TASK_MODE_NONE) {
                startLockTask();
                Log.d(TAG, "startLockTask() called (lockTaskState was NONE)");
            }
        } catch (Exception e) {
            Log.e(TAG, "startLockTask failed: " + e.getMessage());
        }
    }

    private boolean hasUsageStatsPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private void checkAndRequestUsageStats() {
        if (!hasUsageStatsPermission()) {
            Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
    }

    private boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || Environment.isExternalStorageManager();
    }

    private void checkAndRequestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess()) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
    }
}
