package com.quest.lobby;

import android.app.admin.DeviceAdminReceiver;

/**
 * Device admin receiver. Its only purpose is to let this app be provisioned as a
 * device owner (via `adb shell dpm set-device-owner com.quest.lobby/.LobbyAdminReceiver`),
 * which in turn allows silent Lock Task (kiosk) mode without a user confirmation.
 */
public class LobbyAdminReceiver extends DeviceAdminReceiver {
}
