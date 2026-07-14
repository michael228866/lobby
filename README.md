# Lobby (com.quest.lobby) — 運作說明

Meta Quest 上的 **Kiosk 模式 Lobby App**，負責待命、接收伺服器指令、啟動指定的 Content (VR 遊戲)。

## 一句話描述

Lobby 連著 fiveg-local WebSocket server，**operator 從 dashboard 按啟動 → Lobby 收到指令 → 啟動 HellVR (或其他 Content)**。遊戲關掉或被切走時，**自動把 Content 拉回前景，使用者無法逃離**。

---

## 系統架構

```
 Cloud REST API (Google Cloud Run)
       ↑
       │
 Operator Dashboard (fiveg-operator, 瀏覽器)
       ↕  WebSocket
 fiveg-local Server (內網 192.168.99.200:5000)
       ↕  WebSocket          ↑ Game Backend 連這裡
 Lobby (這個 app, com.quest.lobby)
       ↓  Intent (跨 app 啟動)
 Content / HellVR (com.moondream.HellVR)
```

---

## 完整流程

### 1. 開機自啟
1. Quest 開機完成
2. `BootReceiver` 收到 `BOOT_COMPLETED` broadcast
3. 等 5 秒讓系統穩定
4. 啟動 `MainActivity`

> ⚠️ Lobby 必須先**手動啟動過一次**，`BOOT_COMPLETED` 才會送到（Android 安全機制）。

### 2. 連線 fiveg-local
1. 讀取 ClientID（優先序）：
   - `/sdcard/Pictures/moonshineslam/ClientID.txt`（自訂）
   - `/sdcard/Pictures/moonshineslam/HMDSN.jpg`（SN，如 `340YC20G8C0J4Y`）
   - 裝置 IPv4
   - 隨機 UUID（fallback）
2. 連 `ws://192.168.99.200:5000/?type=vr_headset&clientId=XXX&roomId=1234`
3. 收到 `welcome` 後就待命

### 3. 啟動 Content (operator 按啟動)
1. Server 送 `headset_game_backend_command + connect` 給 Lobby，含 `connectData`：
   ```json
   {
     "roomId": "20260514-M002",
     "ip": "192.168.99.203",
     "port": 10000,
     "packageName": "com.moondream.HellVR",
     "mapName": "openday"
   }
   ```
2. Lobby 組 cmdLine：
   ```
   -serverip=192.168.99.203:10000 -wsserverip=192.168.99.200 -wsport=5000
   -roomId=20260514-M002 -useMultiVRGis=true -mapName=openday
   ```
3. 強制斷開自己的 WebSocket（讓 server 釋放 clientId 給 Content 用）
4. 等 300ms
5. `startActivity` 啟動 Content APK，傳 cmdLine 等 extras 進去
6. SharedPreferences 存狀態：`content_should_run=true`、`content_package`、`content_extras_json`
7. Lobby 進背景，Content 跑起來

### 4. Kiosk 監控
Watchdog 由 **`KioskService`（前景 service）**負責，在 `onCreate` 啟動、每 100ms 檢查一次（獨立於 Activity 生命週期，避免背景 Handler 被限流）。`MainActivity` 本身**不再跑任何 watchdog**，確保只有一個 watchdog owner，不會重複 `startActivity`／閃爍。

判斷邏輯（Content 的啟動權**只屬於 server**）：

```
target 決定：
  content_should_run == true 且 content_session_confirmed == true 且 Content alive → target = Content
  其餘所有情況（未確認 / Content 死了 / should_run=false）                        → target = Lobby

前景 == target？或偵測不到前景？ → 不動
其他 app 搶前景（Meta Menu / Quest Home / 其他）→ 每個前景事件只處理一次：
  ├─ target = Content
  │   └─ ✅ REORDER_TO_FRONT 把 Content 拉回前景（不補 extras、不重建 Content）
  └─ target = Lobby
      └─ REORDER_TO_FRONT 把 Lobby 拉回前景
```

`content_session_confirmed`（session 是否經 server 確認）：

```
server connect command → launchContent()  → true
server disconnect                          → false
headset_check_reconnect 8 秒 timeout       → false
fresh app / Activity 啟動（onCreate）       → false
```

**重要**：
- `KioskService` **絕對不 launch Content**。Content 死掉／被 force-stop／crash 時，它把 Lobby 拉回前景，由 `MainActivity.onResume()` 問 server，**只有 server 回 `connect` 才會啟動 Content**。
- 就算 SharedPreferences 殘留舊的 `content_should_run=true`，因為 `content_session_confirmed` 在 `onCreate` 被重設為 false，watchdog 也不會把（可能還活著的）舊 Content 拉回前景，保證停在 Lobby。

### 5. Lobby 重新出現時（server 擁有啟動權）
`onResume` 觸發後 **不做任何本地 relaunch**，一律問 server：

```
should_run == true 且在 1 小時內？
├─ 否 → 正常連 WS 待命
└─ 是 →（不管 Content alive 或 dead）
    ├─ pendingCheckReconnect = true
    ├─ 連 WS → onOpen → sendCheckReconnect()（送 headset_check_reconnect）
    ├─ 同時啟動 8 秒 timeout
    ├─ Server 回 connect  → 取消 timeout、launchContent()（session_confirmed=true）
    └─ Server 8 秒沒回     → clearContentRuntimeState()：
                             content_should_run=false、content_session_confirmed=false、
                             清掉 package/extras/launched_at → 停在 Lobby
```

> ⚠️ 舊版的「Content process 活著就本地 `relaunchContent()`（FAST PATH）」已**移除**。本地 SharedPreferences 或 process alive 都**不能**啟動 Content——啟動權只屬於 server。

### 6. 結束遊戲
**Operator 按 dashboard 的 disconnect 按鈕**：
1. Server 送 `headset_game_backend_command + disconnect` 給 Lobby
2. Lobby 清掉 `content_should_run` 與 `content_session_confirmed` 旗標
3. Lobby 送 `game_closed` 回 server（規格要求）
4. 拉 Lobby 回前景，等下次啟動

---

## 主要程式碼位置

| 功能 | 檔案 |
|------|------|
| 主邏輯 | [app/src/main/java/com/quest/lobby/MainActivity.java](app/src/main/java/com/quest/lobby/MainActivity.java) |
| 開機 receiver | [app/src/main/java/com/quest/lobby/BootReceiver.java](app/src/main/java/com/quest/lobby/BootReceiver.java) |
| 檔案 log | [app/src/main/java/com/quest/lobby/FileLogger.java](app/src/main/java/com/quest/lobby/FileLogger.java) |
| OpenXR 場景 | [app/src/main/cpp/main.cpp](app/src/main/cpp/main.cpp) |
| Manifest | [app/src/main/AndroidManifest.xml](app/src/main/AndroidManifest.xml) |

### MainActivity 內的關鍵函式

| 函式 | 用途 |
|------|------|
| `onCreate` | 初始化、還原 SharedPrefs 狀態、連 WebSocket |
| `onResume` | 回前景時：should_run=true 就設 `pendingCheckReconnect` 問 server（**不本地 relaunch**） |
| `onPause` | 進背景（watchdog 由 `KioskService` 負責，這裡不做事） |
| `registerNetworkCallback` | 監聽 Wi-Fi／網段切換，觸發 WebSocket 遷移 |
| `handleNetworkChanged` / `reconnectForNewIpv4` | IPv4 改變時完整重建 WebSocket（換新 clientId） |
| `connectWebSocket` | 連 fiveg-local，註冊收訊息 listener（含 stale-socket 保護） |
| `handleMessage` | 分派 server 訊息到對應 handler |
| `handleGameBackendCommand` | 處理 `connect`（→ `launchContent`）/ `disconnect` 指令 |
| `launchContent` | 啟動 Content app（**只由 server connect 呼叫**；寫 SharedPrefs 並設 `content_session_confirmed=true`） |
| `sendCheckReconnect` | 送 `headset_check_reconnect` 問 server 房間是否還在，並啟動 8 秒 timeout |
| `checkReconnectTimeoutRunnable` | server 8 秒沒回 connect → `clearContentRuntimeState` 停在 Lobby |
| `clearContentRuntimeState` | 清掉本地 Content session（should_run / session_confirmed / package / extras / launched_at） |
| `getForegroundPackage` / `queryForeground` | 用 `UsageStatsManager` 查當前前景 app（`KioskService` 共用） |
| `isContentProcessAlive` | 用 `pidof` + `ps` 雙重檢查 Content 是否存活 |
| `getClientId` / `getDeviceIpv4` | 以裝置 IPv4 當 ClientID |

> Watchdog 邏輯已移至 [`KioskService.java`](app/src/main/java/com/quest/lobby/KioskService.java)（`doCheck` / `bringContentToFront`）。`MainActivity` 舊有的 `kioskCheck` / `startKioskWatchdog` / `isContentRunning` / `relaunchContent`（本地 fast-path relaunch）已移除——Content 啟動權只屬於 server。

---

## 設定 / 常數

[MainActivity.java](app/src/main/java/com/quest/lobby/MainActivity.java) 開頭：

| 常數 | 預設值 | 說明 |
|------|--------|------|
| `SERVER_HOST` | `192.168.99.200` | fiveg-local server IP |
| `SERVER_PORT` | `5000` | fiveg-local port |
| `CLIENT_TYPE` | `vr_headset` | 連線類型 |
| `DEFAULT_ROOM_ID` | `1234` | 初始 roomId（連上後 server 會給真實值） |
| `RECONNECT_DELAY_MS` | `3000` | WS 失敗後重連等待時間 |
| `KIOSK_LAUNCH_GRACE_MS` | `8000` | 啟動 Content 後不干預的時間 |
| `CHECK_RECONNECT_TIMEOUT_MS` | `8000` | 送出 `headset_check_reconnect` 後等 server 回 connect 的上限；逾時清狀態停 Lobby |
| `LAUNCH_DELAY_AFTER_DISCONNECT_MS` | `3000` | 斷 WS 後等多久才啟動 Content |
| `CONTENT_GRACE_PERIOD_MS` | `60000` | Lobby 不重連 WS 的緩衝期 |
| `CONTENT_AUTO_RELAUNCH_WINDOW_MS` | `3600000` | should_run=true 後仍會問 server 是否 reconnect 的時效（1 小時） |

[BootReceiver.java](app/src/main/java/com/quest/lobby/BootReceiver.java):

| 常數 | 預設值 |
|------|--------|
| `BOOT_DELAY_MS` | `5000` |

---

## WebSocket 協議

### Lobby 收的訊息

| `type` | 處理 |
|--------|------|
| `welcome` | 連線成功 (忽略) |
| `init_player_info` | 玩家資訊 (Content 用，Lobby 忽略) |
| `headset_game_backend_command` + `command:"connect"` | 啟動 Content |
| `headset_game_backend_command` + `command:"disconnect"` | 結束遊戲、Lobby 待命 |
| `echo` / `ping` / `pong` | 忽略 |

### Lobby 送的訊息

| `type` | 時機 |
|--------|------|
| `headset_check_reconnect` | Lobby 重新出現時，問 server 房間是否還活著 |
| `game_closed` | Content 被 disconnect 指令關閉時 |
| `{status: "content_launched"}` | 啟動 Content 前回報 |
| `{status: "launch_failed_not_installed"}` | Content APK 沒裝 |

---

## 部署 (第一次安裝)

```bash
# 編譯
cd f:\game\lobby
gradlew.bat assembleDebug

# 安裝
adb install -r app\build\outputs\apk\debug\app-debug.apk

# 一次性授權
adb shell appops set com.quest.lobby GET_USAGE_STATS allow
adb shell appops set --uid com.quest.lobby MANAGE_EXTERNAL_STORAGE allow
adb shell pm grant com.quest.lobby android.permission.READ_EXTERNAL_STORAGE

# 啟動
adb shell am force-stop com.quest.lobby
adb shell monkey -p com.quest.lobby -c android.intent.category.LAUNCHER 1
```

### 為什麼需要這些授權

| 權限 | 用途 |
|------|------|
| `PACKAGE_USAGE_STATS` | Kiosk watchdog 偵測前景 app（沒授權 → 偵測不到 → 不會自動拉 Lobby/Content） |
| `MANAGE_EXTERNAL_STORAGE` | 讀 `/sdcard/Pictures/moonshineslam/HMDSN.jpg` 取得 ClientID |
| `READ_EXTERNAL_STORAGE` | 同上備援 |
| `RECEIVE_BOOT_COMPLETED` | 開機自啟（manifest 宣告即可） |

---

## Log / 除錯

### 自動寫檔位置
```
/sdcard/Android/data/com.quest.lobby/files/log.txt          ← 目前正寫的
/sdcard/Android/data/com.quest.lobby/files/log.txt.1 ~ .5   ← 過去的 (每份 5MB)
```

### 拉 log
```bash
adb pull "//sdcard/Android/data/com.quest.lobby/files/log.txt" .
```

### 即時看 log
```bash
adb logcat -s Lobby:V
```

### 關鍵 log 訊息

| 訊息 | 意義 |
|------|------|
| `ClientID loaded from XXX: 340YC20G8C0J4Y` | ClientID 正常讀取 |
| `WebSocket connected (as Lobby)` | 連上 server |
| `Received: {"type":"welcome",...}` | server 確認連線 |
| `Received: {"type":"headset_game_backend_command",...}` | 收到啟動指令 |
| `Content cmdLine: -serverip=...` | 準備啟動 Content |
| `Application launch successfully: com.quest.content` | Content 啟動成功 |
| `Content alive -> REORDER_TO_FRONT: ...` | KioskService 把活著的 Content 拉回前景 |
| `bringing Lobby back (fg=..., shouldRun=..., contentAlive=false)` | Content 死了／已 disconnect，Lobby 接手（回 Lobby 問 server） |
| `Sent headset_check_reconnect: {...}` | Lobby 問 server 房間還在不在 |
| `Network migration: 192.168.8.x -> 192.168.99.x` | 偵測到 IPv4 改變，重建 WebSocket |
| `Network changed but Content owns clientId; Lobby will not reconnect` | 防 Lobby 搶 Content WS 連線 |
| `Ignoring onFailure from stale WebSocket` | 舊 socket 的延遲回呼被正確忽略 |

---

## 常見問題

### Lobby 連不上 server
- 確認 fiveg-local server 在 `192.168.99.200:5000` 跑著
- 看 log 是否出現 `Failed to connect`
- 確認 Quest 跟 server 在同網段

### Dashboard 顯示 OFFLINE
- Cloud DB 的 headset `id` 必須跟 Lobby 送的 `clientId` 完全一致
- 看 log 確認 ClientID 是什麼（通常是 `340YC20G8C0J4Y` 從 HMDSN.jpg 讀的）
- dashboard 上對應的 headset 紀錄 id 必須改成同一個

### Operator 按啟動沒反應
1. 確認 dashboard 顯示 H007（這台 Quest）是 ONLINE
2. 確認 operator 選的房間有把這台 Quest 加入 pairing
3. 看 log 找 `Received: {"type":"headset_game_backend_command"...}`
   - 有 → Lobby 有收到，問題在啟動 Content
   - 沒有 → server 沒送，問題在 dashboard / server / cloud DB 配置

### Meta menu 按出後 Content 不回來
- 確認 Lobby 是這版（kiosk watchdog 啟用）
- 確認 PACKAGE_USAGE_STATS 已授權（`adb shell appops get com.quest.lobby GET_USAGE_STATS` 回 `allow`）
- 看 log 是否有 `Kiosk FAST: ... bringing Content back`

### Content 玩到一半被踢
- 看 log 有沒有 `Network available, but Lobby is background — NOT reconnecting`
- 如果常常出現 `Kiosk SLOW: fg=null` → UE 渲染沒觸發前景事件，已 patch 過

### 重啟 Lobby 後沒自動跳回 Content
- 只在 `content_should_run=true` 且 Content process 還活著時跳回
- 如果 Content 也被結束 → Lobby 問 server，server 沒回應就停在 Lobby
- 觀察 log 有沒有 `Auto re-launch successful` 或 `headset_check_reconnect`

---

## 版本

- **2026-05-25 版本**（目前主版本）：用 server `headset_check_reconnect` 機制 + FAST PATH process 檢查
- 備份位置：`f:/game/lobby_backup_2026-05-25/`、`f:/game/lobby_backup_2026-05-20/`
