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
import java.util.concurrent.atomic.AtomicBoolean;

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

    private String serverUrl;
    // Client identity (clientId / headsetId) sent to the server AND passed to Content — now
    // the device IPv4 instead of the headset SN. Cached so every use is the exact same value.
    private String cachedClientId;

    private static final long RECONNECT_DELAY_MS = 3000;
    private static boolean processInitialized = false;

    static {
        System.loadLibrary("lobby_vr");
    }

    private native void nativeCreate();
    private native void nativeDestroy();
    // Hand the decoded 360 panorama (RGBA8, row 0 = top) to the native render thread.
    private native void nativeSetPanorama(int width, int height, java.nio.ByteBuffer pixels);
    // Wake-prompt image shown when the headset isn't woken (Quest 3S off-face boot). Optional asset.
    private native void nativeSetWakeImage(int width, int height, java.nio.ByteBuffer pixels);

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
    // Reset on disconnect, on check-reconnect timeout, and once on a fresh process start.
    // KioskService will only hold Content in the foreground while this is true — so a stale
    // content_should_run=true from a previous run can never make the watchdog jump to Content
    // before the server has re-confirmed. Shared literal key with KioskService.
    static final String PREF_CONTENT_SESSION_CONFIRMED = "content_session_confirmed";
    // Give up waiting for the server's connect reply after this long → clear stale state, stay in Lobby.
    private static final long CHECK_RECONNECT_TIMEOUT_MS = 8000;
    // Idle-poll session verify: the server answers check_reconnect with connect in ~4-44ms when a
    // game is active, so a short window is plenty — silence past this means the game is closed.
    private static final long SESSION_VERIFY_TIMEOUT_MS = 1500;
    // While idle in the Lobby (connected, screen on, no Content), poll the server this often to ask
    // whether a game is now active for this headset — so a game started while we were asleep / after
    // a reboot / after our local state was cleared still gets picked up without asking on every
    // single reconnect (which would race the server's game-close and relaunch a just-closed game).
    private static final long CHECK_RECONNECT_POLL_MS = 5000;
    // Longer settle used when we return to the Lobby straight from a Content session (game closed /
    // Content stopped). The server needs time to finish tearing the game down (closeServer is async
    // and only clears the room's ip/port when it completes); a longer first-poll delay makes sure
    // we don't ask while it's mid-close and get told to relaunch the game we just left.
    private static final long POST_CLOSE_POLL_DELAY_MS = 20000;
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
    // Written on the main thread (launch runnable / onResume / clearContentRuntimeState) and read by
    // the duplicate-launch guard; volatile so the guard always sees the latest value.
    private volatile boolean contentLaunched = false;
    // Guards the launchContent() → delayed startActivity() window. A single connect launches Content
    // after LAUNCH_DELAY_AFTER_DISCONNECT_MS; if the server (re)sends connect during that window, a
    // second launchContent() would queue a second delayed startActivity() and double-start Content —
    // hanging the Unreal activity on the 3-dot loading screen. AtomicBoolean.compareAndSet makes the
    // "test-then-set" a single atomic step, so two near-simultaneous connects can't both pass.
    private final AtomicBoolean contentLaunchInProgress = new AtomicBoolean(false);
    // The pending delayed startActivity() Runnable (main-thread only). Held so a disconnect / destroy
    // arriving inside the launch delay can cancel it before it fires and relaunches a closed game.
    private Runnable pendingContentLaunchRunnable;
    private boolean pendingCheckReconnect = false;
    private boolean usageStatsSettingsRequested = false;
    private boolean storageSettingsRequested = false;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private android.content.BroadcastReceiver screenReceiver;
    // True only while the Lobby Activity is in the foreground (between onResume and onPause). This
    // is the RELIABLE "should the Lobby hold a WebSocket" signal — when Content is running the Lobby
    // is paused, so lobbyResumed is false and no background reconnect fires. We must NOT rely on
    // pidof/contentOwnsClientId for this: pidof can't see other apps on Android 14, so it wrongly
    // reports a live Content as dead and the Lobby would reconnect and evict Content's clientId.
    private volatile boolean lobbyResumed = false;

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
        // pingInterval makes OkHttp send WS ping frames; if no pong comes back within the interval
        // it declares the socket failed → onFailure → our normal reconnect. Without this, a socket
        // that dies "half-open" (TCP dropped by an idle NAT/router with no close frame) is never
        // noticed — webSocket stays non-null, no callback fires, and the Lobby sits disconnected
        // forever. This is the "left it a while and it never reconnects" case.
        client = new OkHttpClient.Builder()
                .pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)
                .build();

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

        // Clear stale confirmation once per process, not on every Activity recreation. Content
        // remains server-confirmed when Android recreates Lobby while the game is still active.
        if (!processInitialized) {
            processInitialized = true;
            savedPrefs.edit().putBoolean(PREF_CONTENT_SESSION_CONFIRMED, false).apply();
        }

        enableLockTaskWhitelist();

        // enableLockTaskWhitelist() resets the allowlist to just the Lobby. If Android recreated the
        // Lobby while Content was legitimately running, that would drop Content from the allowlist and
        // break its pinning. Re-add Content — but gate on content_session_confirmed, NOT should_run:
        //   - Same-process Activity recreation: processInitialized is already true, so the confirmed
        //     flag was NOT cleared above → a live session stays confirmed → restore HellVR allowlist.
        //   - Fresh Lobby process: the !processInitialized block above just cleared confirmed=false →
        //     we do NOT trust a stale should_run/package from a dead session → skip, no stale package.
        boolean sessionConfirmed = savedPrefs.getBoolean(PREF_CONTENT_SESSION_CONFIRMED, false);
        if (sessionConfirmed && savedPkg != null && !savedPkg.isEmpty()) {
            Log.d(TAG, "Restoring Content Lock Task allowlist after Activity recreation (session confirmed): " + savedPkg);
            updateLockTaskAllowlist(savedPkg);
        }

        serverUrl = buildServerUrl();
        Log.d(TAG, "Server URL: " + serverUrl);

        loadImageAsset("scene.png", false);         // 360 panorama background
        loadImageAsset("wake_prompt.png", true);     // optional "please wake" prompt (may be absent)
        nativeCreate();
        registerNetworkCallback();
        registerScreenReceiver();
        connectWebSocket();
        // First poll fires after one interval (not immediately) so a reconnect right after a
        // game-close doesn't ask inside the server's close window and relaunch the closed game.
        mainHandler.postDelayed(checkReconnectPollRunnable, CHECK_RECONNECT_POLL_MS);
    }

    /**
     * Drop the WebSocket the moment the screen turns off and reopen it when it turns back on.
     * SCREEN_OFF/ON (not onPause/onResume) is the precise signal: onPause also fires when we
     * launch Content, which is NOT a sleep. During sleep Quest kills Wi-Fi, so a socket left open
     * dies half-open and the server keeps thinking the headset is connected; a clean close on
     * SCREEN_OFF + fresh connect on SCREEN_ON avoids that entirely.
     */
    private void registerScreenReceiver() {
        screenReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    Log.d(TAG, "SCREEN_OFF — closing WebSocket for sleep");
                    closeWebSocketForSleep();
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    Log.d(TAG, "SCREEN_ON — reconnecting WebSocket");
                    reconnectAfterWake();
                }
            }
        };
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);   // must be registered at runtime, not in manifest
        registerReceiver(screenReceiver, filter);
    }

    /** Cancel pending reconnects and tear down the current socket so sleep holds no connection. */
    private void closeWebSocketForSleep() {
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.removeCallbacks(networkReconnectRunnable);
        WebSocket ws = webSocket;
        if (ws != null) {
            webSocket = null;                       // null FIRST so late callbacks are stale
            socketState = SocketState.DISCONNECTED;
            connectedSocketIpv4 = null;
            ws.cancel();                            // immediate TCP teardown (server sees us drop)
        }
    }

    /**
     * True if the display is currently interactive (on). Used as a LIVE gate for background
     * reconnects instead of a sticky "screenOff" flag: a sticky flag that only SCREEN_ON clears
     * would wedge the Lobby offline forever if a single SCREEN_ON broadcast were ever missed. With
     * a live query, any reconnect trigger (onResume, a network callback) self-heals once the screen
     * is actually on, so a missed broadcast can never leave us permanently disconnected.
     */
    private boolean isScreenOn() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    /** Rebuild the URL from the current IPv4 and reconnect — unless Content owns the clientId. */
    private void reconnectAfterWake() {
        // Only the FOREGROUND Lobby may reconnect on wake. During a game the Lobby is paused
        // (lobbyResumed=false) and Content is in front; reconnecting then re-uses the shared clientId
        // (device IPv4) and evicts Content on the server. We do NOT reconnect and do NOT drag the Lobby
        // forward — Content stays in front and reconnects its OWN socket (its Wi-Fi died for sleep). The
        // watchdog keeps Content foregrounded via UsageStats (see KioskService), so the Lobby never
        // needs to intervene here; if Content actually exited, the Lobby becomes foreground on its own
        // and the normal onResume flow reconnects and re-checks with the server.
        if (!lobbyResumed) {
            Log.d(TAG, "SCREEN_ON but Lobby is backgrounded (game running); not reconnecting");
            return;
        }
        // A game may have started while we slept; the idle poll will ask the server after onOpen.
        if (contentOwnsClientId()) {
            Log.d(TAG, "SCREEN_ON but Content owns clientId; Lobby will not reconnect");
            return;
        }
        if (webSocket != null) return;              // already (re)connecting via onResume
        cachedClientId = null;                      // re-read IPv4 in case DHCP handed a new one
        serverUrl = buildServerUrl();
        connectWebSocket();
    }

    @Override
    protected void onResume() {
        super.onResume();
        lobbyResumed = true;

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

        // The Lobby is in the foreground now, so it should hold a WebSocket. Reconnect if needed.
        // We do NOT ask the server here — all game-discovery goes through the periodic poll, whose
        // first tick we (re)arm CHECK_RECONNECT_POLL_MS out. That settle window is what stops the
        // "operator closed the game, Lobby comes forward, immediately asks, server is still mid-close
        // and replies connect → game relaunches" bug: by the time the poll asks, the close is done.
        // The long post-close settle is ONLY to avoid racing the SERVER's own game-close (disconnect
        // → should_run=false, its room teardown is async). Use it only when the game was actually
        // closed. If content_should_run is still true, we came back because Content crashed / was
        // killed (e.g. adb) while the server still wants the game — there is no close window to wait
        // out, so poll fast and let the server re-confirm → relaunch quickly (was wrongly waiting the
        // full 20s here). contentLaunched distinguishes "returned from a Content session" from a
        // plain idle resume.
        boolean gameStillActive = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(PREF_CONTENT_SHOULD_RUN, false);
        long firstPollDelay = (contentLaunched && !gameStillActive)
                ? POST_CLOSE_POLL_DELAY_MS : CHECK_RECONNECT_POLL_MS;
        contentLaunched = false;
        if (webSocket == null && !contentOwnsClientId()) {
            connectWebSocket();
        }
        mainHandler.removeCallbacks(checkReconnectPollRunnable);
        mainHandler.postDelayed(checkReconnectPollRunnable, firstPollDelay);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Lobby is no longer in the foreground (Content launched, screen off, or backgrounded). Mark
        // it so no background reconnect fires — that is what stops the Lobby from reconnecting during
        // a game and evicting Content's clientId. Watchdog stays solely in KioskService (see onCreate).
        lobbyResumed = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelPendingContentLaunch("activity-destroyed");
        mainHandler.removeCallbacks(networkReconnectRunnable);
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.removeCallbacks(checkReconnectTimeoutRunnable);
        mainHandler.removeCallbacks(checkReconnectPollRunnable);
        if (networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
            networkCallback = null;
        }
        if (screenReceiver != null) {
            try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
            screenReceiver = null;
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
            // Only reconnect on a network change while the Lobby itself is in the foreground. During
            // a game the Lobby is paused — reconnecting then would evict Content's clientId (and
            // pidof can't be trusted to detect that on Android 14, so lobbyResumed is the gate).
            if (!lobbyResumed) return;
            if (!isScreenOn()) return;   // screen off: hold no socket until it wakes
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
        // The Lobby connects WITHOUT a roomId on purpose. fiveg-local uses `isContent = !!roomId`
        // (onVrHeadsetOpen) to tell Lobby from Content — a roomId here makes the server treat the
        // Lobby as Content and broadcast a game-backend "connect" to operators, lighting the
        // GameServerIndicator on a mere Lobby connect. Only Content carries a roomId. Reconnect
        // discovery uses headsetId (headset_check_reconnect), not this URL, so nothing is lost.
        return "ws://" + SERVER_HOST + ":" + SERVER_PORT
                + "/?type=" + CLIENT_TYPE
                + "&clientId=" + clientId;
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
                // Do NOT ask the server on connect — the periodic poll (first tick armed a settle
                // window out) is the single game-discovery path. Asking on every connect used to
                // race the server's game-close and relaunch a just-closed game.
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

        // Defensive: only act on a command addressed to THIS headset. The server targets a headset by
        // headsetId (the device IPv4). If a command carrying a DIFFERENT headsetId reaches us (server
        // broadcast / mis-route), ignore it — otherwise several headsets launch a game meant for one.
        // A command with no headsetId is treated as addressed to us (server routed it to our socket).
        String cmdHeadsetId = json.optString("headsetId", "");
        String myHeadsetId = getClientId();
        if (!cmdHeadsetId.isEmpty() && !cmdHeadsetId.equals(myHeadsetId)) {
            Log.w(TAG, "Ignoring '" + command + "' addressed to a different headset: headsetId="
                    + cmdHeadsetId + " (mine=" + myHeadsetId + ")");
            return;
        }

        if ("connect".equals(command)) {
            JSONObject connectData = json.optJSONObject("connectData");
            if (connectData == null) {
                Log.e(TAG, "connect command missing connectData");
                return;
            }
            // Server may send "packageName" (new fiveg-local) or "execPath" (legacy)
            String parsedPkg = connectData.optString("packageName", "");
            if (parsedPkg.isEmpty()) parsedPkg = connectData.optString("execPath", "");
            if (parsedPkg.isEmpty()) {
                Log.e(TAG, "connectData has neither packageName nor execPath: " + connectData);
                parsedPkg = DEFAULT_CONTENT_PACKAGE;
            }
            final String pkg = parsedPkg;
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

            final JSONObject launchExtras = extras;
            // handleGameBackendCommand runs on the OkHttp WebSocket callback thread; hop to the main
            // thread so all launch/disconnect state (flags, prefs, handler callbacks) is mutated on
            // one thread only — no cross-thread races on contentLaunched / pendingContentLaunchRunnable.
            mainHandler.post(() -> {
                // Server authoritatively confirmed this session → cancel the check-reconnect timeout
                // and clear the pending flag so it can't later clear our now-valid state.
                mainHandler.removeCallbacks(checkReconnectTimeoutRunnable);
                pendingCheckReconnect = false;
                launchContent(pkg, launchExtras);
            });
        } else if ("disconnect".equals(command)) {
            // Also on the WebSocket thread — serialize onto main (see connect above).
            mainHandler.post(() -> {
                Log.d(TAG, "Disconnect command received — stopping Content");
                // Kill any delayed launch still waiting out its window BEFORE stopContent(), so a
                // just-closed game can't be relaunched by a runnable that was already queued.
                cancelPendingContentLaunch("disconnect");
                // Also clear contentLaunched here — launchContent() sets it true BEFORE the delayed
                // startActivity(), so a disconnect inside the launch delay would otherwise leave it
                // true and the duplicate guard would block every future connect. Do NOT rely on
                // onResume() to clear it: the Lobby may already be foreground, and stopContent()'s
                // startActivity() is not guaranteed to re-fire onResume().
                contentLaunched = false;
                Log.d(TAG, "Content launch state reset by server disconnect");
                // Clear the "should be running" flag AND the session-confirmed flag so neither the
                // watchdog nor a later onResume treats the old session as still valid.
                mainHandler.removeCallbacks(checkReconnectTimeoutRunnable);
                pendingCheckReconnect = false;
                // Hold off the idle poll for the longer post-close settle so we don't ask again while
                // the server is still tearing the game down (its room ip/port aren't cleared until
                // closeServer finishes) — otherwise the next poll relaunches the closed game.
                mainHandler.removeCallbacks(checkReconnectPollRunnable);
                mainHandler.postDelayed(checkReconnectPollRunnable, POST_CLOSE_POLL_DELAY_MS);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(PREF_CONTENT_SHOULD_RUN, false)
                    .putBoolean(PREF_CONTENT_SESSION_CONFIRMED, false)
                    .apply();
                stopContent();
            });
        }
    }

    private static final long LAUNCH_DELAY_AFTER_DISCONNECT_MS = 3000;

    private void launchContent(String pkg, JSONObject extras) {
        Log.d(TAG, "Try launch apk: " + pkg);

        // Duplicate-launch guard. contentLaunched means a live Content session is already up
        // (cleared in onResume when we return to the Lobby, so a connect after Content closes still
        // launches). compareAndSet atomically claims the in-progress slot: if it's already true, a
        // launch is mid-flight and this connect is a duplicate → ignore. From here every exit path
        // MUST release contentLaunchInProgress (clearContentRuntimeState does so via cancel).
        if (contentLaunched) {
            Log.w(TAG, "Ignoring duplicate Content launch request: " + pkg + " (already launched)");
            return;
        }
        if (!contentLaunchInProgress.compareAndSet(false, true)) {
            Log.w(TAG, "Ignoring duplicate Content launch request: " + pkg + " (launch already in progress)");
            return;
        }

        if (!isAppInstalled(pkg)) {
            Log.e(TAG, "Can't find application or not installed: " + pkg);
            sendStatus("launch_failed_not_installed");
            // Don't leave should_run=true / confirmed=true pointing at an uninstalled package;
            // clearContentRuntimeState also releases contentLaunchInProgress.
            clearContentRuntimeState("launch-not-installed");
            return;
        }

        // Defensive: the guard above blocks a concurrent launch, but make sure no stray delayed
        // runnable survives from a prior cycle. Only drop the handle — keep our claimed in-progress.
        if (pendingContentLaunchRunnable != null) {
            mainHandler.removeCallbacks(pendingContentLaunchRunnable);
            pendingContentLaunchRunnable = null;
        }

        // Whitelist Content for Lock Task now that we know it's installed, before we start it.
        updateLockTaskAllowlist(pkg);

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
        pendingContentLaunchRunnable = () -> {
            pendingContentLaunchRunnable = null;   // executing now — no longer "pending" to cancel
            Log.d(TAG, "Delayed launch runnable running now @" + System.currentTimeMillis()
                    + " for " + pkg + " (waited " + LAUNCH_DELAY_AFTER_DISCONNECT_MS + "ms)");

            // Second-layer guard: a disconnect during the delay may have cleared the session even if
            // the cancel raced this execution. Never launch a session the server already tore down.
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            boolean shouldRun = prefs.getBoolean(PREF_CONTENT_SHOULD_RUN, false);
            boolean sessionConfirmed = prefs.getBoolean(PREF_CONTENT_SESSION_CONFIRMED, false);
            if (!shouldRun || !sessionConfirmed) {
                Log.w(TAG, "Skipping delayed Content launch because session is no longer active"
                        + " (shouldRun=" + shouldRun
                        + ", sessionConfirmed=" + sessionConfirmed + ")");
                contentLaunchInProgress.set(false);
                contentLaunched = false;   // let the next server connect start Content again
                return;
            }

            try {
                PackageManager pm = getPackageManager();
                Intent intent = pm.getLaunchIntentForPackage(pkg);
                if (intent == null) {
                    Log.e(TAG, "No launch intent for: " + pkg);
                    clearContentRuntimeState("launch-intent-null");   // also releases inProgress
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
                contentLaunchInProgress.set(false);   // launch dispatched; contentLaunched stays true
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
                clearContentRuntimeState("startActivity-failed");   // also releases inProgress
            }
        };
        mainHandler.postDelayed(pendingContentLaunchRunnable, LAUNCH_DELAY_AFTER_DISCONNECT_MS);
    }

    /**
     * Cancel a delayed Content launch that hasn't fired yet and release the in-progress guard, so a
     * disconnect / destroy inside the launch window can't later relaunch a game we've already left.
     * Safe to call when nothing is pending. Logs only when it actually cancelled something.
     */
    private void cancelPendingContentLaunch(String reason) {
        boolean had = pendingContentLaunchRunnable != null || contentLaunchInProgress.get();
        if (pendingContentLaunchRunnable != null) {
            mainHandler.removeCallbacks(pendingContentLaunchRunnable);
            pendingContentLaunchRunnable = null;
        }
        contentLaunchInProgress.set(false);
        if (had) Log.d(TAG, "Cancelled pending Content launch: " + reason);
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
        if (!sendCheckReconnectMessage()) return;
        // Arm the server-response timeout. If no connect command arrives within
        // CHECK_RECONNECT_TIMEOUT_MS, the runnable clears the stale state and we stay in Lobby.
        // Only the pendingCheckReconnect path (verifying a remembered session) uses this timeout —
        // the periodic idle poll does NOT, so a silent poll never clears anything.
        mainHandler.removeCallbacks(checkReconnectTimeoutRunnable);
        mainHandler.postDelayed(checkReconnectTimeoutRunnable, CHECK_RECONNECT_TIMEOUT_MS);
    }

    /** Send the check_reconnect query only (no response timeout). Returns false if no live socket. */
    private boolean sendCheckReconnectMessage() {
        if (webSocket == null) return false;
        try {
            JSONObject json = new JSONObject();
            json.put("type", "headset_check_reconnect");
            json.put("headsetId", getClientId());
            webSocket.send(json.toString());
            Log.d(TAG, "Sent headset_check_reconnect: " + json);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "headset_check_reconnect send failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Periodic idle poll: while the Lobby is connected, awake, and Content is NOT running, ask the
     * server every CHECK_RECONNECT_POLL_MS whether a game is now active. Server replies connect → we
     * jump; silent → we stay and poll again. Self-reschedules forever and self-gates, so it does
     * nothing (but keep ticking) during a game or during sleep.
     *
     * <p>If a Content session is still marked active ({@code content_should_run==true}) while the
     * Lobby is the foreground app, that session was almost certainly closed by the server — the
     * server closes a game by telling Content (HellVR) only, never the Lobby, so should_run stays
     * stale-true and the watchdog would resurrect the dead Content on the next wake. Since the Lobby
     * is genuinely in front here (Content is gone → no live connection to evict), we verify with the
     * clearing timeout: no connect within CHECK_RECONNECT_TIMEOUT_MS → clear the stale session now,
     * while still awake, so a later sleep/wake has nothing to resurrect. The timeout is armed once
     * (guarded by pendingCheckReconnect) so repeated 5s polls don't keep resetting it.
     */
    private final Runnable checkReconnectPollRunnable = new Runnable() {
        @Override
        public void run() {
            // Only when the Lobby itself is in the foreground (not during a game — Content is then in
            // front and owns the clientId) and not inside Content's launch grace window.
            if (lobbyResumed && !contentOwnsClientId()) {
                if (webSocket != null && socketState == SocketState.CONNECTED) {
                    boolean staleSession = getSharedPreferences(PREFS, MODE_PRIVATE)
                            .getBoolean(PREF_CONTENT_SHOULD_RUN, false);
                    if (staleSession && !pendingCheckReconnect) {
                        Log.d(TAG, "Idle poll: Lobby foreground but content_should_run still true — "
                                + "verifying with server (clear if no connect)");
                        pendingCheckReconnect = true;
                        sendCheckReconnectMessage();
                        mainHandler.removeCallbacks(checkReconnectTimeoutRunnable);
                        mainHandler.postDelayed(checkReconnectTimeoutRunnable, SESSION_VERIFY_TIMEOUT_MS);
                    } else if (!staleSession) {
                        sendCheckReconnectMessage();   // normal idle discovery (no clear)
                    }
                    // staleSession && pendingCheckReconnect → verification already in flight; wait.
                } else if (webSocket == null) {
                    // Connection watchdog: we're a foreground Lobby with no socket — make sure a
                    // reconnect is queued (covers a drop that no callback re-armed).
                    Log.d(TAG, "Idle poll: WebSocket is down while Lobby is foreground — ensuring reconnect");
                    scheduleReconnect();
                }
            }
            mainHandler.postDelayed(this, CHECK_RECONNECT_POLL_MS);
        }
    };

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
        // Drop any delayed launch still queued and release the in-progress guard, so clearing state
        // never leaves a Runnable that would later launch (or a stuck contentLaunchInProgress=true).
        cancelPendingContentLaunch(reason);
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
            if (webSocket == null && lobbyResumed && !contentOwnsClientId()) {
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
        if (!lobbyResumed) return;   // only the foreground Lobby reconnects (never during a game)
        if (!isScreenOn()) return;   // screen off: don't retry until it wakes
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
        // After the startup window: is Content still the active app? pidof can't tell (Android 14
        // hides other apps' PIDs — it always reads dead from here), so use UsageStats last-used. This
        // keeps the Lobby from reconnecting on the shared clientId while Content is alive; when Content
        // exits / is adb-killed the Lobby becomes the newer app and this flips false so the Lobby can
        // reconnect and take over.
        return contentUsedMoreRecentlyThanLobby(this, currentContentPackage);
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

    /**
     * True when Content looks like the active app, judged by UsageStats "last time used" instead of
     * pidof. An untrusted app on Android 14 CANNOT see another package's PID (pidof / ps both return
     * nothing, so Content always reads "dead" from inside this app) — but usage-stats last-used IS
     * visible and is how the watchdog already detects the foreground. Content is the owner when it
     * was used more recently than the Lobby: while Content plays (or a Meta-menu panel overlays it)
     * Content stays the newer of the two; only Content actually exiting / being adb-killed hands the
     * foreground to the Lobby and flips this false — which is exactly when the Lobby SHOULD take over.
     * Shared (static) so KioskService uses the identical signal. Returns false if usage access is off.
     */
    public static boolean contentUsedMoreRecentlyThanLobby(Context ctx, String contentPkg) {
        if (contentPkg == null || contentPkg.isEmpty()) return false;
        AppOpsManager appOps = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.getPackageName());
        if (mode != AppOpsManager.MODE_ALLOWED) return false;
        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        long end = System.currentTimeMillis();
        // 12h window comfortably spans any single game session; last-used timestamps persist within it.
        long begin = end - 1000L * 60 * 60 * 12;
        java.util.List<android.app.usage.UsageStats> stats =
                usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, begin, end);
        if (stats == null) return false;
        String lobbyPkg = ctx.getPackageName();
        long tContent = 0, tLobby = 0;
        for (android.app.usage.UsageStats s : stats) {
            String p = s.getPackageName();
            if (contentPkg.equals(p)) tContent = Math.max(tContent, s.getLastTimeUsed());
            else if (lobbyPkg.equals(p)) tLobby = Math.max(tLobby, s.getLastTimeUsed());
        }
        return tContent > 0 && tContent >= tLobby;
    }

    /**
     * Decode an assets PNG and hand its pixels to native. {@code wake=false} → 360 panorama
     * background; {@code wake=true} → the optional "please wake the headset" prompt image. A missing
     * wake_prompt.png is not an error — native falls back to a plain colour when it isn't woken.
     */
    private void loadImageAsset(String asset, boolean wake) {
        try (java.io.InputStream is = getAssets().open(asset)) {
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(is);
            if (bmp == null) {
                Log.e(TAG, "Image decode failed: " + asset);
                return;
            }
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            // ARGB_8888 copyPixelsToBuffer yields R,G,B,A byte order — matches GL_RGBA.
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(w * h * 4);
            bmp.copyPixelsToBuffer(buf);
            buf.rewind();
            if (wake) nativeSetWakeImage(w, h, buf);   // native copies synchronously
            else      nativeSetPanorama(w, h, buf);
            bmp.recycle();
            Log.d(TAG, "Image loaded: " + asset + " " + w + "x" + h);
        } catch (java.io.FileNotFoundException e) {
            if (wake) Log.d(TAG, "No wake_prompt.png asset — not-woken screen uses a plain colour");
            else      Log.e(TAG, "Missing required asset: " + asset);
        } catch (Exception e) {
            Log.e(TAG, "loadImageAsset(" + asset + ") failed: " + e.getMessage());
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

    /**
     * Add the Content package to the Lock Task allowlist alongside the Lobby, so a pinned kiosk
     * can hand the foreground to Content without Lock Task blocking it. No-op (with a diagnostic
     * log) when we are not device owner — existing behavior is preserved, nothing is thrown.
     */
    private void updateLockTaskAllowlist(String contentPackage) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                ComponentName admin = new ComponentName(this, LobbyAdminReceiver.class);
                String[] allow = (contentPackage != null && !contentPackage.isEmpty()
                        && !contentPackage.equals(getPackageName()))
                        ? new String[]{ getPackageName(), contentPackage }
                        : new String[]{ getPackageName() };
                dpm.setLockTaskPackages(admin, allow);
                Log.d(TAG, "Lock task allowlist updated: " + java.util.Arrays.toString(allow)
                        + " | isLockTaskPermitted(" + contentPackage + ")="
                        + dpm.isLockTaskPermitted(contentPackage));
            } else {
                Log.w(TAG, "Lock task allowlist: NOT device owner — leaving current behavior (allow "
                        + contentPackage + " via manifest/none)");
            }
        } catch (Exception e) {
            Log.e(TAG, "updateLockTaskAllowlist failed: " + e.getMessage());
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
