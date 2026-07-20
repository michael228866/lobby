# Lobby (`com.quest.lobby`)

Meta Quest VR Kiosk Lobby。Lobby 連接 `fiveg-local` WebSocket Server，接收 Operator 的遊戲啟動／關閉命令，並透過 Android Intent 啟動 Unreal Engine Content APK。

## 系統架構

```text
Operator Dashboard
        |
        | WebSocket command
        v
fiveg-local Server (192.168.99.200:5000)
        |
        | WebSocket
        v
Lobby (com.quest.lobby)
        |
        | Android Intent + extras
        v
Content / HellVR (例如 com.moondream.HellVR)
```

主要元件：

- [`MainActivity.java`](app/src/main/java/com/quest/lobby/MainActivity.java)：WebSocket、遊戲啟動、session 狀態及 OpenXR Lobby。
- [`KioskService.java`](app/src/main/java/com/quest/lobby/KioskService.java)：100ms 前景監控與 Meta Menu 拉回。
- [`BootReceiver.java`](app/src/main/java/com/quest/lobby/BootReceiver.java)：開機 5 秒後啟動 Lobby。
- [`FileLogger.java`](app/src/main/java/com/quest/lobby/FileLogger.java)：將 Lobby log 寫入頭盔儲存空間。
- [`install-quest.bat`](install-quest.bat)：安裝 APK 並設定每台 Quest 所需的 AppOps。

## 啟動流程

### Lobby 啟動

1. `BootReceiver` 收到 `BOOT_COMPLETED`，等待 5 秒後啟動 `MainActivity`。
2. Lobby 以裝置 IPv4 作為 `clientId`；網路尚未就緒時暫時使用隨機 UUID，取得 IPv4 後重建連線 URL。
3. Lobby 連線：

```text
ws://192.168.99.200:5000/?type=vr_headset&clientId=<IPv4>
```

Lobby 連線刻意**不帶** `roomId`：

- Server（`fiveg-local`）以 `isContent = !!roomId` 判斷連線是 Content 還是 Lobby，只有 Content 的連線與啟動參數會帶遊戲房間的 `roomId`。
- 若 Lobby URL 帶上 `roomId`，Server 會誤把 Lobby 當成 Content 並對 Operator 廣播 game-backend `connect`，讓 GameServerIndicator 亮起。
- reconnect discovery 使用 `headsetId`（`headset_check_reconnect`），不依賴 Lobby URL 的 `roomId`，因此拿掉 `roomId` 不影響重連判斷。

4. `KioskService` 以前景服務啟動，獨立於 Activity lifecycle 執行 watchdog。

### Server 啟動 Content

Server 傳入：

```json
{
  "type": "headset_game_backend_command",
  "command": "connect",
  "connectData": {
    "roomId": "20260713-M001",
    "ip": "192.168.99.203",
    "port": 10000,
    "packageName": "com.moondream.HellVR",
    "mapName": "M5"
  }
}
```

Lobby 組成 Unreal command line：

```text
-serverip=192.168.99.203:10000 -wsserverip=192.168.99.200 -wsport=5000 -roomId=20260713-M001 -useMultiVRGis=true -mapName=M5
```

重要：目前這個 Unreal `GameActivity` 使用大小寫敏感的全小寫 Intent Extra key：

```java
extras.put("cmdline", cmdLine);
```

不要改成 `CommandLine` 或 `cmdLine`。如果 key 不正確，Unreal 讀不到自訂參數，會保留 APK 預設的：

```text
-project="../../../HellVR/HellVR.uproject"
```

Lobby 另外保留 `Websocket` 及個別欄位，供舊版 Blueprint／非 UE Content 使用：

```text
Websocket, userId, fromApp, serverip, wsserverip, wsport, roomId,
useMultiVRGis, mapName, ip, port, playerheight
```

Content 與 Lobby 在同一台 Quest 上共用同一個 IPv4 作為 `clientId`；`clientId` 不會另外透過 Intent 傳給 Content，Content 自行由裝置 IPv4 推導出相同身分。

正式啟動步驟（實際實作順序）：

1. Server 傳入 `headset_game_backend_command / connect`。
2. WebSocket callback 執行在 OkHttp callback thread；透過 `mainHandler.post()` 將整個啟動操作序列化到 Android main thread，之後所有 launch/state 變更都只在單一 thread 進行。
3. 重複防護：
   - `if (contentLaunched) return;` — 已有正在進行中的 Content session。
   - `if (!contentLaunchInProgress.compareAndSet(false, true)) return;` — 已有一次啟動在 3000ms handoff 中。
4. 檢查 Content package 是否已安裝（`getLaunchIntentForPackage` 非 null）。未安裝則清除 runtime state 並結束。
5. Device Owner 模式下，`updateLockTaskAllowlist(pkg)` 把 Lobby 與 Content package 一起加入 Lock Task allowlist。
6. 寫入 SharedPreferences：
   - `content_should_run=true`
   - `content_session_confirmed=true`
   - `content_package`
   - `content_extras_json`
   - `content_launched_at`
7. `close(1000)` + `cancel()` 關閉 Lobby WebSocket，釋放與 Content 共用的 IPv4 `clientId`（Server 必須先看到 Lobby 斷線，Content 用相同 `clientId` 才連得上）。
8. 建立可取消的 pending launch Runnable（`pendingContentLaunchRunnable`）。
9. 等待 `LAUNCH_DELAY_AFTER_DISCONNECT_MS`（目前 3000ms），讓 Server 執行 onClose 並釋放 `clientId`。
10. Runnable 執行前先把自己設為 null，並**重新讀取** `content_should_run` 與 `content_session_confirmed`。
11. 只有在 session 仍有效時，才以 `NEW_TASK | CLEAR_TOP` 啟動 Content（全小寫 `cmdline` 內含 server 參數）。
12. 啟動成功後 `contentLaunchInProgress.set(false)`，但保留 `contentLaunched=true`（session 仍有效）。
13. 啟動失敗（intent 為 null 或 `startActivity` 例外）呼叫 `clearContentRuntimeState(...)` 清除殘留 session，讓後續 connect 可以重試。

### 避免 Content 卡在三點載入畫面

原本的 race condition：

```text
Server connect
→ MainActivity 寫入 should_run / confirmed / launched_at
→ MainActivity 排程 3 秒後啟動
→ Quest 切換 App 時 Meta shell / loading activity 暫時成為前景
→ KioskService 看到「前景不是 Lobby 也不是 Content」→ 提前 bringContentToFront()
→ 3 秒後 MainActivity 再啟動一次
→ Unreal Activity 被重複啟動
→ 可能卡在 Meta 三點載入畫面
```

目前針對「已知的重複啟動與 Lock Task 問題」的防護（這些防護處理已知成因，並不保證所有三點載入狀況都不會發生）：

- `KioskService` 在 `content_launched_at` 之後的 `LAUNCH_GRACE_MS=8000ms` 內完全不介入 foreground pullback（詳見下方 Watchdog 段落）。
- `contentLaunchInProgress.compareAndSet(false, true)` 以原子操作阻擋短時間內的重複 connect／重複啟動。
- pending launch Runnable 可在 disconnect、Activity destroy、runtime state 清除時被取消。
- delayed Runnable 真正執行前會再次驗證 `content_should_run` 與 `content_session_confirmed`，即使取消與執行剛好競態，也不會啟動已被 disconnect 的 session。
- Device Owner 模式下 Lock Task allowlist 同時包含 Lobby 與 Content，避免在鎖定裝置上阻擋 App 切換。

## Content session 狀態

### SharedPreferences：`lobby_state`（跨 process 持久）

| Key | 用途 |
|---|---|
| `content_should_run` | Server 是否仍預期遊戲運行 |
| `content_session_confirmed` | 本次 process 是否已收到 Server 的 connect 確認 |
| `content_package` | 目前 Content package |
| `content_extras_json` | 原始啟動 extras，watchdog 拉回／重建時重用 |
| `content_launched_at` | 最近一次正式啟動時間（watchdog launch-grace 基準） |

`content_session_confirmed` 的變化：

```text
Server connect -> true
Server disconnect -> false
headset_check_reconnect timeout -> false
新的 Lobby process 第一次建立 -> false
同一 process 的 Activity recreation -> 保留原值
```

每個 process 只在第一次 `onCreate()` 清除一次舊 confirmation。Android 重新建立同一個 Activity 時不會再把有效遊戲 session 清掉。

### 記憶體內 launch 狀態（非 SharedPreferences）

| 狀態 | 用途 |
|---|---|
| `contentLaunched` | 本 Activity 是否已接受並建立 Content session 啟動流程 |
| `contentLaunchInProgress` (`AtomicBoolean`) | 3000ms handoff／正式 `startActivity()` 是否正在進行 |
| `pendingContentLaunchRunnable` | 可取消的延遲 Content 啟動工作 |

- 這三項只存在於記憶體，不會寫入 SharedPreferences。
- 以下情況都會釋放對應的 launch 狀態：App 未安裝、Intent 為 null、`startActivity()` 失敗、Server disconnect、delayed Runnable 執行前發現 session 失效。
- `clearContentRuntimeState()` 會：取消 pending launch（連同釋放 `contentLaunchInProgress`）、清除 SharedPreferences session key、並重設 `contentLaunched=false` 與 `currentContentPackage`。

## Watchdog / Meta Menu

`KioskService` 每 `CHECK_INTERVAL_MS=100ms` 查詢最近的前景 App。它不能阻止 Meta Menu 本身出現，只能偵測後將正確 App 拉回前景。

### 首次啟動保護（launch grace）

`doCheck()` 讀取 SharedPreferences 之後、**任何** foreground/pullback 邏輯之前，先檢查 launch grace：

```text
content_should_run == true
且 content_launched_at 之後未超過 LAUNCH_GRACE_MS=8000ms
→ 直接 return
```

在這段 grace 期間 watchdog 完全不介入，**不會**執行：

- `bringContentToFront()`
- Content pullback
- Lobby pullback

這正是「避免三點載入」的核心：Content 正式啟動有 3000ms handoff 延遲，grace（8000ms）必須大於此延遲，讓 MainActivity 完成單次 `startActivity()` 之前 watchdog 不會搶先啟動 Content。收到 connect 後，watchdog **不會**立即啟動 Content；真正的啟動由 MainActivity 在延遲後執行。

### 遊戲 session 已確認（grace 結束後）

grace 結束後，當以下條件成立：

```text
content_should_run=true
content_session_confirmed=true
Content process alive
前景 App 不是 Lobby，也不是 Content
```

watchdog 才使用 `REORDER_TO_FRONT` 快速拉回**已存在**的 Content task（不重新啟動、不重帶 extras；必要時 Android 重建 task 才會用保存的 extras）。

這條快速路徑不依賴 `pidof` 作為 Meta Menu 判斷。Quest Android 14 可能禁止 Lobby 查詢其他 App PID，造成 `dead (no pid found)`，因此 PID 結果不能單獨用來判定 Meta Menu 快速拉回。

### Lobby 閒置

如果 session 未確認或 `content_should_run=false`，Meta Menu 關閉後目標是 Lobby，不可啟動 Content。這可避免舊 SharedPreferences 讓 Lobby 閒置時誤跳 HellVR。

### Content 被關閉或 crash

- 若前景落到 Meta Menu 且 session 仍確認且 Content 仍存活，watchdog 用 `REORDER_TO_FRONT` 拉回 Content（不重新啟動）。
- 若前景回到 Lobby，Lobby 透過週期性 idle poll（`headset_check_reconnect`）向 Server 確認遊戲狀態。
- Server 若仍保留遊戲狀態，回傳新的 `connect`，Lobby 正式重啟 Content。
- Server 在 `CHECK_RECONNECT_TIMEOUT_MS=8000ms` 內未確認（僅在驗證舊 session 的路徑），Lobby 清除 Content runtime state 並停留在 Lobby。

## Server 關閉遊戲

Server 傳入：

```json
{
  "type": "headset_game_backend_command",
  "command": "disconnect"
}
```

Lobby 依序（同樣序列化到 main thread）：

1. `mainHandler.post()` 將 disconnect 處理序列化到 main thread。
2. `cancelPendingContentLaunch("disconnect")`：取消尚未執行的 pending launch Runnable。
3. 釋放 `contentLaunchInProgress`（由上一步的 cancel 一併清除）。
4. 明確設定 `contentLaunched=false`，並記錄 `Content launch state reset by server disconnect`（不依賴 `onResume()` 清除，因為 Lobby 可能原本就在前景，`startActivity()` 不保證再次觸發 `onResume()`）。
5. 寫入 `content_should_run=false`、`content_session_confirmed=false`。
6. 將下一次 reconnect poll 延後到 `POST_CLOSE_POLL_DELAY_MS=20000ms`，避免 Server 尚在關閉遊戲（`closeServer` 為非同步、房間 ip/port 尚未清空）時又回覆 connect 造成重啟。
7. `stopContent()` 將 Lobby 拉回前景，並送出 `game_closed`。
8. 即使 pending launch 剛好已開始執行，也會因步驟 10 的 session 二次檢查（`content_should_run=false`）失敗而停止，不會啟動。

注意：disconnect 的主要行為是**把 Lobby 拉回前景並停止 watchdog 再把 Content 拉回**。目前流程並不會主動終止 HellVR process；Content process 可能仍留在背景，只是不再被 watchdog 拉回前景。

## Lock Task / Device Owner

- 一般裝置若**不是** Device Owner，`startLockTask()` 可能只是一般 screen pinning，或受 Quest 系統限制，無法封鎖所有系統介面。
- Device Owner 模式下，Lobby 使用 `DevicePolicyManager.setLockTaskPackages()` 設定 allowlist。
- `enableLockTaskWhitelist()`（`onCreate`）先把 allowlist 設為只有 Lobby。
- 啟動 Content 前 `updateLockTaskAllowlist(pkg)` 會把 allowlist 設為包含：
  - `com.quest.lobby`
  - Server 指定的 Content package，例如 `com.moondream.HellVR`
  - 並記錄 `dpm.isLockTaskPermitted(pkg)` 供診斷。
- Activity recreation 時，`onCreate()` 依 **`content_session_confirmed`** 判斷是否恢復 Content allowlist：
  - 同一 process 的 Activity recreation：`content_session_confirmed` 仍為 true → 恢復 Lobby + Content allowlist，Content 不會被移出 allowlist。
  - 全新 Lobby process：`content_session_confirmed` 已被清成 false → 不信任舊的 `content_should_run` / package → 不加入過期的 Content package。
- 非 Device Owner 時 `updateLockTaskAllowlist()` 僅記錄警告，不改變現有行為、不拋出例外。
- Manifest 宣告 package **不等同**加入 Lock Task allowlist；allowlist 必須透過 Device Owner API 設定。

檢查 Device Owner 狀態：

```bat
adb shell dpm list-owners
adb shell dumpsys device_policy
```

## 必要權限

Manifest 已宣告：

- `PACKAGE_USAGE_STATS`：watchdog 讀取 Meta Menu／前景 App。
- `MANAGE_EXTERNAL_STORAGE`：讀取外部自訂檔案時使用。
- `READ_EXTERNAL_STORAGE`：舊版 Android 相容。
- `RECEIVE_BOOT_COMPLETED`：開機啟動。
- `FOREGROUND_SERVICE`／`FOREGROUND_SERVICE_SPECIAL_USE`：持續執行 KioskService。

`PACKAGE_USAGE_STATS` 與 `MANAGE_EXTERNAL_STORAGE` 不能由一般 App 自行靜默核准；每台 Quest 都必須設定一次。若未授權：

- Lobby 可能重複開啟系統設定頁。
- Watchdog 無法得知 Meta Menu 在前景。

查詢權限：

```bat
adb shell appops get com.quest.lobby GET_USAGE_STATS
adb shell appops get com.quest.lobby MANAGE_EXTERNAL_STORAGE
```

正確結果應包含 `allow`。

## Build 與安裝

### Build

```bat
gradlew.bat assembleDebug
```

預設 APK：

```text
app\build\outputs\apk\debug\app-debug.apk
```

### 建議：使用安裝腳本

每次只連接一台已授權 USB debugging 的 Quest，執行：

```bat
install-quest.bat
```

腳本會：

1. `adb install -r` 安裝 APK。
2. 設定 `GET_USAGE_STATS=allow`。
3. 設定 `MANAGE_EXTERNAL_STORAGE=allow`。
4. force-stop 並重新啟動 Lobby。
5. 顯示最後的 AppOps 狀態。

也可以將其他 APK 拖到 `install-quest.bat` 上，或傳入路徑：

```bat
install-quest.bat path\to\lobby.apk
```

手動設定指令：

```bat
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell appops set com.quest.lobby GET_USAGE_STATS allow
adb shell appops set --uid com.quest.lobby MANAGE_EXTERNAL_STORAGE allow
adb shell am force-stop com.quest.lobby
adb shell am start -n com.quest.lobby/.MainActivity
```

## 設定值

[`MainActivity.java`](app/src/main/java/com/quest/lobby/MainActivity.java)：

| 常數 | 目前值 | 用途 |
|---|---:|---|
| `SERVER_HOST` | `192.168.99.200` | fiveg-local IP |
| `SERVER_PORT` | `5000` | fiveg-local port |
| `RECONNECT_DELAY_MS` | `3000ms` | Lobby WebSocket 重試間隔 |
| `CHECK_RECONNECT_TIMEOUT_MS` | `8000ms` | 等待 Server reconnect 確認（僅驗證舊 session 路徑） |
| `CHECK_RECONNECT_POLL_MS` | `10000ms` | 閒置時週期性詢問 Server 是否有遊戲 |
| `POST_CLOSE_POLL_DELAY_MS` | `20000ms` | 遊戲關閉後延後首次 poll，避開 Server 關閉窗 |
| `KIOSK_LAUNCH_GRACE_MS` | `8000ms` | Content 啟動期間，Lobby 端不重連 WebSocket 的判斷 |
| `LAUNCH_DELAY_AFTER_DISCONNECT_MS` | `3000ms` | 釋放 clientId 後正式啟動 Content 的等待時間 |

Legacy／目前程式未引用（保留宣告但不屬於現行流程）：

| 常數 | 目前值 | 狀態 |
|---|---:|---|
| `CONTENT_GRACE_PERIOD_MS` | `60000ms` | 宣告存在但目前未被引用（legacy） |
| `CONTENT_AUTO_RELAUNCH_WINDOW_MS` | `3600000ms` | 宣告存在但目前未被引用（legacy；舊 onResume 自動重啟邏輯已移除） |

> `DEFAULT_ROOM_ID` 已從程式碼移除（Lobby URL 不再帶 `roomId`）。

[`KioskService.java`](app/src/main/java/com/quest/lobby/KioskService.java)：

| 常數 | 目前值 | 用途 |
|---|---:|---|
| `CHECK_INTERVAL_MS` | `100ms` | watchdog 輪詢間隔 |
| `LAUNCH_GRACE_MS` | `8000ms` | Content 首次啟動保護期，期間不介入 pullback |
| `PID_CACHE_MS` | `300ms` | `pidof` 結果快取，降低 CPU 佔用 |
| `MIN_INTERVENTION_INTERVAL_MS` | `500ms` | 避免前景 App 互相閃爍的安全間隔 |

## Log

頭盔內：

```text
/sdcard/Android/data/com.quest.lobby/files/log.txt
/sdcard/Android/data/com.quest.lobby/files/log.txt.1 ... log.txt.5
```

每個檔案最多 5MB。

讀取：

```bat
adb pull /sdcard/Android/data/com.quest.lobby/files/log.txt .
adb logcat -s Lobby:V
```

重要 log：

| Log | 意義 |
|---|---|
| `WebSocket connected (as Lobby)` | Lobby 已連線 Server |
| `Content cmdLine: -serverip=...` | Lobby 已建立完整 Unreal 參數 |
| `Intent extra to Content: cmdline=...` | 正確的全小寫 key 已放入 Intent |
| `Lock task allowlist updated` | Lobby 與 Content 已加入 Device Owner allowlist |
| `Try launch apk` | 開始一次 Content 啟動流程 |
| `Ignoring duplicate Content launch request` | 重複 connect／啟動請求已被防護擋下 |
| `Content launch grace active (...)` | Watchdog 正在首次啟動保護期間讓位 |
| `Delayed launch runnable running now` | 3000ms handoff 等待結束，準備正式啟動 |
| `Skipping delayed Content launch because session is no longer active` | 等待期間收到 disconnect 或 session 已失效 |
| `Cancelled pending Content launch` | 尚未執行的延遲啟動已取消 |
| `Content launch state reset by server disconnect` | disconnect 已釋放 launch 狀態 |
| `Application launch successfully` | Android 已接受單次 Content `startActivity` |
| `Clearing Content runtime state` | 啟動失敗或 reconnect timeout 後清除殘留 session |
| `Meta/other foreground -> immediate Content pullback` | watchdog 快速拉回 Content（grace 結束後） |
| `Content -> REORDER_TO_FRONT` | Content task 已送回前景 |
| `bringing Lobby back` | 目前 session 不應執行 Content，因此拉回 Lobby |
| `Sent headset_check_reconnect` | Lobby 正在向 Server 確認遊戲狀態 |
| `dead (no pid found)` | Android 14 隱藏其他 App PID，不一定代表 Content 真的死亡 |

## 疑難排解

### 按 Meta Menu 後沒有回 Lobby／Content

```bat
adb shell appops get com.quest.lobby GET_USAGE_STATS
```

必須是 `allow`。同時確認 `KioskService` 存活：

```bat
adb shell dumpsys activity services com.quest.lobby
```

### Content 停在三點載入畫面

先收集：

```bat
adb pull /sdcard/Android/data/com.quest.lobby/files/log.txt .
adb logcat -s Lobby:V
adb shell dpm list-owners
adb shell dumpsys device_policy
```

正常單次啟動的 log 順序大致為：

```text
headset_game_backend_command: connect
Lock task allowlist updated
Try launch apk
WebSocket close(...)+cancel
Content launch grace active
Delayed launch runnable running now
Application launch successfully
```

異常訊號（代表重複啟動或 Lock Task 問題）：

- 同一次 session 出現兩次 `Application launch successfully`。
- 第一個 `Application launch successfully` 之前出現 `Content -> REORDER_TO_FRONT`。
- grace 期間出現 `Meta/other foreground -> immediate Content pullback`。
- `isLockTaskPermitted(ContentPackage)=false`。
- delayed Runnable 顯示 `Skipping delayed Content launch because session is no longer active`。
- Content package 未安裝或沒有 launch intent。

`Ignoring duplicate Content launch request` 本身通常代表防護成功擋下重複請求，不一定是錯誤；但若持續反覆出現，需檢查 Server 是否重複送 connect。

### Lobby 一直跳系統設定

表示特殊權限尚未核准。執行 `install-quest.bat`，或手動設定兩個 AppOps。

### Unreal 只取得 `-project="../../../HellVR/HellVR.uproject"`

確認 Lobby log 包含：

```text
Intent extra to Content: cmdline=-serverip=...
```

如果看到 `CommandLine` 或 `cmdLine`，代表安裝的 Lobby APK 不是目前版本。正確 key 只有全小寫 `cmdline`。

### Lobby 無法連線 Server

確認 Quest 與 `192.168.99.200` 位於可互通網段。若 Quest IP 是 `192.168.9.x`，通常無法直接連到 `192.168.99.200`，log 會持續出現 timeout。

### Meta Menu 仍能出現

這是正常限制。Watchdog 只能在 Menu 出現後拉回 App，不能禁止 Meta 系統 UI。完全鎖定 Meta Menu 需要 Quest 企業 Kiosk／MDM 或 Device Owner；一般 `startLockTask()` 無法封鎖所有 Quest 系統介面。

## 近期重要修正

- 2026-07-20：修正 KioskService 在 Content 首次啟動空窗內提前 pullback 造成 Unreal Activity 雙重啟動——launch-grace guard 移到 `doCheck()` 最前面，grace 期間完全不介入。
- 2026-07-20：加入 `AtomicBoolean contentLaunchInProgress` 的重複啟動防護（`compareAndSet`）。
- 2026-07-20：加入可取消的 pending launch Runnable，並在 delayed Runnable 執行前二次驗證 session。
- 2026-07-20：Server disconnect 明確清除 launch state（`contentLaunched=false` 等），避免後續 connect 被誤判為重複而永久被擋。
- 2026-07-20：Device Owner Lock Task allowlist 於啟動 Content 前一併加入 Content package。
- 2026-07-20：Activity recreation 依 `content_session_confirmed` 恢復有效的 Content allowlist；全新 process 不信任過期 package。
- 2026-07-20：啟動失敗（intent null／`startActivity` 例外／未安裝）時清除 runtime session state，使後續 connect 可重試。
- 2026-07-20：Lobby WebSocket URL 移除 `roomId`（避免 Server 誤把 Lobby 當 Content）。
- 2026-07-16：加入 `install-quest.bat`，自動安裝並設定每台 Quest 的必要 AppOps。
- 2026-07-16：Unreal Intent command-line key 修正為全小寫 `cmdline`；刪除錯誤的 `CommandLine`／`cmdLine`。
- 2026-07-16：Activity recreation 不再清除有效的 `content_session_confirmed`。
- 2026-07-15：Meta Menu 快速拉回要求 `content_should_run && content_session_confirmed`，避免 Lobby 閒置時誤啟動 Content。
