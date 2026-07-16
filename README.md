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
ws://192.168.99.200:5000/?type=vr_headset&clientId=<IPv4>&roomId=1234
```

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

正式啟動步驟：

1. 寫入 `content_should_run=true`、`content_session_confirmed=true`、Content package 及完整 extras。
2. 關閉 Lobby WebSocket，釋放與 Content 共用的 `clientId`。
3. 等待 `LAUNCH_DELAY_AFTER_DISCONNECT_MS`（目前 3000ms）。
4. 使用 `NEW_TASK | CLEAR_TOP` 啟動 Content。
5. Content 使用全小寫 `cmdline` 取得 server 參數並建立自己的連線。

## Content session 狀態

SharedPreferences：`lobby_state`

| Key | 用途 |
|---|---|
| `content_should_run` | Server 是否仍預期遊戲運行 |
| `content_session_confirmed` | 本次 process 是否已收到 Server 的 connect 確認 |
| `content_package` | 目前 Content package |
| `content_extras_json` | 原始啟動 extras，watchdog 拉回／重啟時重用 |
| `content_launched_at` | 最近一次正式啟動時間 |

`content_session_confirmed` 的變化：

```text
Server connect -> true
Server disconnect -> false
headset_check_reconnect timeout -> false
新的 Lobby process 第一次建立 -> false
同一 process 的 Activity recreation -> 保留原值
```

每個 process 只在第一次 `onCreate()` 清除一次舊 confirmation。Android 重新建立同一個 Activity 時不會再把有效遊戲 session 清掉。

## Watchdog / Meta Menu

`KioskService` 每 100ms 查詢最近的前景 App。它不能阻止 Meta Menu 本身出現，只能偵測後將正確 App 拉回前景。

### 遊戲 session 已確認

當以下條件成立：

```text
content_should_run=true
content_session_confirmed=true
前景 App 不是 Lobby，也不是 Content
```

watchdog 立即使用 `REORDER_TO_FRONT` 拉回 Content，不等待 Lobby WebSocket 或 3 秒正式啟動延遲。Intent 會重新帶上保存的 extras；如果 Android 已丟棄 Content task，也可用相同 `cmdline` 重建。

這條快速路徑不依賴 `pidof`。Quest Android 14 可能禁止 Lobby 查詢其他 App PID，造成 `dead (no pid found)`，因此 PID 結果不能用來判定 Meta Menu 快速拉回。

### Lobby 閒置

如果 session 未確認或 `content_should_run=false`，Meta Menu 關閉後目標是 Lobby，不可啟動 Content。這可避免舊 SharedPreferences 讓 Lobby 閒置時誤跳 HellVR。

### Content 被關閉或 crash

- 若前景落到 Meta Menu 且 session 仍有效，watchdog 會直接嘗試用保存的 extras 啟動／拉回 Content。
- 若前景回到 Lobby，Lobby 傳送 `headset_check_reconnect` 給 Server。
- Server 若仍保留遊戲狀態，回傳新的 `connect`，Lobby 正式重啟 Content。
- Server 8 秒內未確認，Lobby 清除 Content runtime state 並停留在 Lobby。

## Server 關閉遊戲

Server 傳入：

```json
{
  "type": "headset_game_backend_command",
  "command": "disconnect"
}
```

Lobby 會：

1. 將 `content_should_run` 與 `content_session_confirmed` 設為 `false`。
2. 將 Lobby 拉回前景；Content 可能仍留在背景 process，但 watchdog 不會再拉回它。
3. 傳送 `game_closed` 給 Server。

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
| `DEFAULT_ROOM_ID` | `1234` | 初始 room ID |
| `RECONNECT_DELAY_MS` | `3000ms` | Lobby WebSocket 重試間隔 |
| `KIOSK_LAUNCH_GRACE_MS` | `8000ms` | Content 啟動保護期 |
| `CHECK_RECONNECT_TIMEOUT_MS` | `8000ms` | 等待 Server reconnect 確認 |
| `LAUNCH_DELAY_AFTER_DISCONNECT_MS` | `3000ms` | 釋放 clientId 後正式啟動 Content 的等待時間 |
| `CONTENT_GRACE_PERIOD_MS` | `60000ms` | Content 初始持有 clientId 的保護期 |
| `CONTENT_AUTO_RELAUNCH_WINDOW_MS` | `3600000ms` | 保存 Content session 的最長回復窗口 |

[`KioskService.java`](app/src/main/java/com/quest/lobby/KioskService.java)：

| 常數 | 目前值 | 用途 |
|---|---:|---|
| `CHECK_INTERVAL_MS` | `100ms` | watchdog 輪詢間隔 |
| `LAUNCH_GRACE_MS` | `8000ms` | 避免干擾 Content 啟動 |
| `MIN_INTERVENTION_INTERVAL_MS` | `500ms` | 避免前景 App 互相閃爍 |

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
| `Application launch successfully` | Android 已接收 Content launch Intent |
| `Meta/other foreground -> immediate Content pullback` | watchdog 快速拉回 Content |
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

- 2026-07-16：加入 `install-quest.bat`，自動安裝並設定每台 Quest 的必要 AppOps。
- 2026-07-16：Unreal Intent command-line key 修正為全小寫 `cmdline`；刪除錯誤的 `CommandLine`／`cmdLine`。
- 2026-07-16：Activity recreation 不再清除有效的 `content_session_confirmed`。
- 2026-07-15：Meta Menu 快速拉回要求 `content_should_run && content_session_confirmed`，避免 Lobby 閒置時誤啟動 Content。
