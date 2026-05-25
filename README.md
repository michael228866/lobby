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
Lobby 進背景的瞬間，**watchdog 啟動**（每 100ms 檢查）：

```
Lobby 在前景？ → 不動
Content 在前景？ → 不動
前景偵測不到？ → 不動（避免誤判，UE app 可能不觸發 fg event）
其他 app 在前景？ →
  ├─ Content process 還活著？
  │   └─ ✅ 直接 startActivity(Content) — 拉 Content 回前景，不顯示 Lobby
  └─ Content process 死了
      └─ startActivity(Lobby) → 由 Lobby 詢問 server 下一步
```

### 5. Lobby 重新出現時
`onResume` 觸發後：

```
should_run == true 且在 1 小時內？
├─ 否 → 正常連 WS 待命
└─ 是 →
    ├─ Content 在前景 → 不動（誤觸發）
    ├─ Content 剛啟動 <8 秒 → 等
    ├─ Content process 活著 → 直接拉 Content（FAST PATH）
    └─ Content 死了 → 連 WS 並送 headset_check_reconnect 問 server
        ├─ Server 回 connect → 重新啟動 Content
        └─ Server 不回 → 留在 Lobby 待命
```

### 6. 結束遊戲
**Operator 按 dashboard 的 disconnect 按鈕**：
1. Server 送 `headset_game_backend_command + disconnect` 給 Lobby
2. Lobby 清掉 `content_should_run` 旗標
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
| `onResume` | 回前景時判斷要連 WS、跳回 Content、還是待命 |
| `onPause` | 進背景時啟動 Kiosk watchdog |
| `connectWebSocket` | 連 fiveg-local，註冊收訊息 listener |
| `handleMessage` | 分派 server 訊息到對應 handler |
| `handleGameBackendCommand` | 處理 `connect` / `disconnect` 指令 |
| `launchContent` | 啟動 Content app（含組 cmdLine、寫 SharedPrefs） |
| `relaunchContent` | 重啟 Content（用於 Lobby 重啟時的恢復） |
| `kioskCheck` | Watchdog 每 100ms 跑一次的檢查邏輯 |
| `getForegroundPackage` | 用 `UsageStatsManager` 查當前前景 app |
| `isContentProcessAlive` | 用 `pidof` + `ps` 雙重檢查 Content 是否存活 |
| `getClientId` | 多來源優先取 ClientID |
| `sendCheckReconnect` | 送 `headset_check_reconnect` 問 server 房間是否還在 |

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
| `KIOSK_CHECK_INTERVAL_MS` | `100` | Watchdog 檢查頻率 |
| `KIOSK_LAUNCH_GRACE_MS` | `8000` | 啟動 Content 後不干預的時間 |
| `LAUNCH_DELAY_AFTER_DISCONNECT_MS` | `300` | 斷 WS 後等多久才啟動 Content |
| `CONTENT_GRACE_PERIOD_MS` | `60000` | Lobby 不重連 WS 的緩衝期 |
| `CONTENT_AUTO_RELAUNCH_WINDOW_MS` | `3600000` | 自動重啟 Content 的時效（1 小時） |

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
| `Application launch successfully: com.moondream.HellVR` | Content 啟動成功 |
| `Kiosk FAST: fg=XXX, Content alive, bringing Content back` | watchdog 把 Content 拉回前景 |
| `Kiosk SLOW: fg=XXX, Content dead — bringing Lobby back` | Content 死了，Lobby 接手 |
| `Sent headset_check_reconnect: {...}` | Lobby 問 server 房間還在不在 |
| `Network available, but Lobby is background — NOT reconnecting` | 防 Lobby 搶 Content WS 連線 |

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
