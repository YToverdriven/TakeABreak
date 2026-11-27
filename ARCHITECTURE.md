# Take a Break — Technical Architecture

**Version 1.0**

This document explains how **Take a Break** and **Take a Break Watcher** work internally, including blocking mechanics, cross-app security, tamper detection, SMS delivery guarantees, setup state machines, and build/signing structure.

For a high-level overview of features and intent, see [README.md](README.md). For change history, see [VERSION_NOTES.md](VERSION_NOTES.md).

---

## 1. Two-App Module Layout & Trust Model

The project consists of two independent Android application modules built from the same repository and signed with the **same signing key**:

| App | Gradle Module | Application ID | Role |
| --- | --- | --- | --- |
| **Take a Break** | `:takeabreak` | `com.takeabreak.controller` | Enforces PIN locks and daily time limits, tracks foreground usage, hosts the PIN-gated settings UI, and dispatches user/system SMS alerts. |
| **Take a Break Watcher** | `:takeabreak-watcher` | `com.takeabreak.watcher` | Monitors the controller package and its accessibility service, stores synced SMS settings, and dispatches tamper SMS alerts if the controller is removed, disabled, force-stopped, cleared, or turned off. |

### Cross-App IPC Security

All controller ↔ watcher broadcasts are explicit component-targeted intents protected by two layers:

1. **Signature-level custom permission:** Both manifests declare and request `com.takeabreak.permission.POST_MESSAGE` with `android:protectionLevel="signature"`. Android refuses delivery unless both apps are signed with the identical certificate.
2. **Sender identity verification (Android 14 / API 34+):** Outgoing broadcasts attach `BroadcastOptions.isShareIdentityEnabled = true`, and receivers (`PeerMessageReceiver`, `AlertMessageReceiver`) verify `sentFromPackage` against the expected peer package name.

Both apps target **API 34** with **minSdk 26** (Android 8.0+), zero third-party cloud SDKs, no `INTERNET` permission, and UI built directly with platform Views.

---

## 2. Blocking Engine & Multi-Window Enforcement

### Window Tracking (`AppLockAccessibilityService`)

[AppLockAccessibilityService](takeabreak/src/main/java/com/takeabreak/controller/AppLockAccessibilityService.kt) observes `TYPE_WINDOW_STATE_CHANGED` events and inspects `windows` (`flagRetrieveInteractiveWindows`) rather than relying solely on the last focused event.

- **Full-screen & split-screen awareness:** The service tracks **every visible application window** simultaneously. In split-screen, opening a launcher picker or an unblocked app in the second pane cannot hide the block screen or end the blocked app's visit while its pane remains visible.
- **Dual-blocked split-screen handling:** If two blocked apps are open side by side, the overlay stays locked onto one app until unlocked, then immediately evaluates and prompts for the second.
- **Visit lifecycle:** A *visit* begins when a package appears in the visible window set and ends when it leaves the visible window set—switching apps, going Home, closing a split-screen pane, turning off the screen, or showing the Android lock screen (including biometric/PIN prompts after unpinning an app). Ending a visit clears its PIN-passed status.
- **Privacy boundary:** Only visible window package names are inspected; window text/view hierarchies are never read or persisted. Android's Accessibility Settings always show a shortcut switch for every service, so `accessibility_service_description` states upfront that the shortcut should not be enabled.

### Single Decision Point (`AccessPolicy`)

[AccessPolicy](takeabreak/src/main/java/com/takeabreak/controller/AccessPolicy.kt) is the sole decision function (`AccessPolicy.decide`), evaluated in strict priority order:

1. **Protected app (`ProtectedPackages`) → `ALLOW`**
   System cameras (all system camera packages, not only the default), Phone (default dialer + system call UI), Settings, Google Play services, and the default SMS app are always allowed and filtered out of both selection lists. Sideloaded third-party cameras and the Google Play Store remain controllable. Launchers and enabled IMEs (keyboards) are also excluded via [AppList](takeabreak/src/main/java/com/takeabreak/controller/AppList.kt).
2. **Daily time limit (`Prefs.isOverLimit`) → `TIME_UP`**
   Always takes precedence over an earlier PIN unlock in the same visit. Checked every second using saved usage plus live in-memory `pendingMs` so limits trigger accurately to the second. Bypassed for the rest of the calendar day if day-unlocked via PIN, or temporarily during the app's single daily 5-minute extension.
3. **PIN lock → `PIN_LOCK`**
   Applies if the package is in `locked_packages` and has not passed the PIN during the current visible visit.
4. **Default → `ALLOW`**

### Block Overlay (`BlockOverlay`)

[BlockOverlay](takeabreak/src/main/java/com/takeabreak/controller/BlockOverlay.kt) renders directly as a `TYPE_ACCESSIBILITY_OVERLAY` window attached to the accessibility service:

- **No `SYSTEM_ALERT_WINDOW` required:** Works without "Display over other apps" and can draw above screen-pinned apps.
- **Screen-pinning escape safety:** System navigation bar and gesture regions remain outside the overlay bounds so Back + Overview or swipe-up-and-hold unpinning gestures continue reaching the system.
- **Configuration & lock-screen handling:** Rebuilds cleanly on screen rotation. When the device lock screen appears, the overlay is dismissed and all active visits end. If Android refuses overlay attachment, the service falls back to `GLOBAL_ACTION_HOME`.

### Foreground Usage Accounting

Foreground time is accumulated only while an app is visible and `BlockOverlay` is hidden. Usage is flushed to `SharedPreferences` roughly once per second—and immediately whenever any Take a Break Activity becomes visible (`ACTION_OUR_SCREEN_VISIBLE`)—so [TimeSpentActivity](takeabreak/src/main/java/com/takeabreak/controller/TimeSpentActivity.kt) reflects up-to-the-second totals. Usage counters, day-unlocks, and 5-minute extensions automatically roll over when `LocalDate.now()` (`BASIC_ISO_DATE`) changes.

---

## 3. PIN Storage & Session Locking

- **Storage:** [Prefs](takeabreak/src/main/java/com/takeabreak/controller/Prefs.kt) stores the PIN (minimum 4 digits) as a `SHA-256` hex digest (`pin_hash`) in private `SharedPreferences`. There is no biometric bypass inside the app.
- **PIN Prompt (`PinPrompt`):** All post-setup PIN gates use a custom on-screen numeric keypad with no text field to focus first.
- **Activity Session Guard (`PinLockedActivity`):** Every privileged Activity (`PinSetupActivity`, `AppSelectorActivity`, `UsageLimitsActivity`, `SmsAlertsActivity`, and debug `DebugActivity`) extends [PinLockedActivity](takeabreak/src/main/java/com/takeabreak/controller/PinLockedActivity.kt). Each unlocked launch receives an in-memory `sessionId`. Configuration changes (such as rotation) preserve the session; leaving the app (`onStop` when `!isChangingConfigurations`) releases the session and finishes the Activity so returning lands on `MainActivity` and requires the PIN again.

---

## 4. Guided Setup Wizards & Cross-App Handshake

### Controller Wizard (`takeabreak/.../PermissionsSetupActivity.kt`)

First-time setup (`EXTRA_FIRST_SETUP = true`) runs an 8-screen state machine that allows orientation changes (`onSaveInstanceState` preserves step, drafts, and selections) and persists progress (`setup_in_progress`, `setup_step`):

1. **Welcome** → begins setup.
2. **Enable Accessibility Service** → disables its button and reveals **Next** if already enabled; otherwise shows an explanation dialog before opening `Settings.ACTION_ACCESSIBILITY_SETTINGS`. On return (`onResume`), skips straight to Screen 4 if enabled or routes to Screen 3 if not.
3. **Restricted Settings** → explains Android 13+ / GrapheneOS behavior and opens `Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS` (falling back to `ACTION_APPLICATION_SETTINGS` → `ACTION_SETTINGS`) rather than `ACTION_APPLICATION_DETAILS_SETTINGS`, because Android hides the `⋮ → Allow restricted settings` option when App Info is launched directly. **Next** unlocks once Accessibility is enabled.
4. **SMS Permission & Alert Configuration** → requests `SEND_SMS` (with a Restricted Settings fallback if denied). During first-time setup, allows optional phone number entry (country code spinner + automatic dash formatting), all four SMS event toggles, and the daily status `TimePickerDialog`. **Next** unlocks once `SEND_SMS` is granted (phone number remains optional; if omitted, both apps show a home-screen reminder that a PIN is required to set it later).
5. **Mandatory Watcher Check** → probes Watcher via `PeerLink.probeWatcherSetup` (`ACTION_PING`, `reason = "setup_check"`).
   - If Watcher replies with `ACTION_PONG` (`EXTRA_SETUP_COMPLETE = true`) on initial arrival, displays the **"Watcher Is Already Set Up"** bypass screen.
   - If Watcher is missing or disabled, displays an install/enable notice and keeps **Continue** disabled.
   - If Watcher is installed but not finished, offers **Continue Setup on Watcher App** (launches Watcher's launcher Intent) and re-probes on every `onResume` until `EXTRA_SETUP_COMPLETE = true` arrives.
6. **Set a PIN** → 4+ digit PIN entry with confirmation.
7. **Set Up App Blocking** → controllable app checklist; button switches dynamically between **Skip** and **Save**.
8. **Set Up Time Limits** → **Back** button to Screen 7, per-app daily limit picker, **Skip**/**Save** button, and a confirmation warning if neither locked apps nor limits were configured. Completing Step 8 sets `setup_complete = true`.

**Edge cases handled:**
- **Existing upgrades:** If `isPinSet` is true and `isSetupInProgress` is false, `MainActivity` marks `setup_complete = true` and skips the wizard.
- **Interrupted first setup:** If closed after Screen 6 saved a PIN, reopening resumes at Screen 7/8 without prompting for the PIN or allowing the PIN to be overwritten.
- **Post-setup revisits:** Tapping **Setup & Permissions** on the home screen launches with `EXTRA_FIRST_SETUP = false`, walking through **Screens 2–5 only** and locking phone number / SMS toggle edits behind the PIN-gated **Text Message Alerts** screen.

### Watcher Wizard (`takeabreak-watcher/.../PermissionsSetupActivity.kt`)

Runs a 3-screen flow:
- **1B (Grant SMS Permission):** Skips straight to **3B** if `SEND_SMS` is already granted; otherwise displays the sideloaded-app explanation dialog, requests `SEND_SMS`, and routes to **3B** (granted) or **2B** (denied).
- **2B (Restricted Settings):** Opens Apps Settings (`ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS`) with instructions, offers **Grant SMS Permission**, and gates **Next** on `SEND_SMS`.
- **3B (Setup Complete):** Marks `watcher_setup.setup_complete = true`, flushes any queued SMS, proactively sends `ACTION_PONG` with `EXTRA_SETUP_COMPLETE = true` via `PeerLink.notifySetupComplete`, and offers **Return to Take a Break** (launching `com.takeabreak.controller`).

---

## 5. Inter-App Monitoring & Tamper Detection (`PeerLink`)

Both apps run symmetric monitoring modules ([controller PeerLink](takeabreak/src/main/java/com/takeabreak/controller/PeerLink.kt) and [watcher PeerLink](takeabreak-watcher/src/main/java/com/takeabreak/watcher/PeerLink.kt)) tracking peer state across `MISSING`, `DISABLED`, `STOPPED`, and `OK`:

- **Immediate system broadcasts (`PackageMonitorReceiver`):**
  - `ACTION_PACKAGE_REMOVED` / `ACTION_PACKAGE_FULLY_REMOVED` (ignoring `EXTRA_REPLACING = true` updates) → tamper alert if peer is uninstalled; logged as `OTHER_UNINSTALL` for non-peer apps on controller.
  - `ACTION_PACKAGE_DATA_CLEARED` → peer storage cleared alert.
  - `ACTION_PACKAGE_CHANGED` → inspects enabled/disabled transitions.
  - `ACTION_PACKAGE_ADDED` (controller only, ignoring updates) → `NEW_APP` alert while accessibility service is active, or resyncs config if Watcher was installed.
- **Immediate accessibility-off detection:** `AppLockAccessibilityService` detects unbind/destroy transitions from `ENABLED` → `DISABLED` and fires `ALERT: Take a Break accessibility service was turned off!` (never before first enable, and not during updates while still enabled). Watcher independently checks `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` as a backup and deduplicates if the controller already reported the event via `AlertMessageReceiver`.
- **Battery-friendly periodic checks (`PeerCheckWorker` + `BootReceiver`):** Disable and force-stop do not wake a stopped receiver, so each app inspects peer state at boot, on process UI open, and via a 1-hour `PeriodicWorkRequest` (`ExistingPeriodicWorkPolicy.KEEP`, no foreground service, no polling loop). Each check also sends `ACTION_PING` (with `FLAG_INCLUDE_STOPPED_PACKAGES`) and logs whether `ACTION_PONG` returns.

---

## 6. SMS Alerts, Config Sync & Durable Retry Queue

### Configuration Sync

When the alert phone number or tamper-alert toggle changes in Take a Break (during setup or in [SmsAlertsActivity](takeabreak/src/main/java/com/takeabreak/controller/SmsAlertsActivity.kt)), [WatcherMessages.syncConfig](takeabreak/src/main/java/com/takeabreak/controller/WatcherMessages.kt) broadcasts `com.takeabreak.watcher.SYNC_CONFIG` to Watcher's [AlertMessageReceiver](takeabreak-watcher/src/main/java/com/takeabreak/watcher/AlertMessageReceiver.kt). Clearing the phone number disables SMS alerts and purges both apps' retry queues.

### Alert Triggers

| Category | Sender | Message Format |
| --- | --- | --- |
| New app installed | Controller | `ALERT: New app installed: <package>` |
| Tamper / accessibility off | Surviving peer | `ALERT: <Peer> was uninstalled!`, `was disabled!`, `storage was cleared!`, `was stopped or its storage was cleared!`, or `ALERT: Take a Break accessibility service was turned off!` |
| PIN unlock on blocked app | Controller | `ALERT: PIN entered to unlock <App>.` or `ALERT: PIN entered to unlock <App> for the rest of the day.` |
| Daily status check (`DailyStatusWorker`) | Controller | `Take a Break daily status: Accessibility ON/OFF, Watcher <status>, <N> locked, <M> limited.` |

[DailyStatusWorker](takeabreak/src/main/java/com/takeabreak/controller/DailyStatusWorker.kt) schedules a `OneTimeWorkRequest` to the configured local time (default `20:00`), records `last_daily_status_date` so it can never fire twice on the same calendar day, and reschedules for the next day.

### Durable Outbox & Anti-Spam Protections (`SmsAlerts`)

Both apps share identical outbox guarantees ([controller SmsAlerts](takeabreak/src/main/java/com/takeabreak/controller/SmsAlerts.kt), [watcher SmsAlerts](takeabreak-watcher/src/main/java/com/takeabreak/watcher/SmsAlerts.kt)):

- **Persisted JSON queue** in `SharedPreferences` (`sms_outbox`) surviving process death and reboots.
- **Explicit send confirmation:** Each SMS part uses a `PendingIntent` targeting `SmsSentReceiver`. Items remain `IN_FLIGHT` (5-minute timeout) until all parts report `Activity.RESULT_OK` (removed) or an error such as `RESULT_ERROR_RADIO_OFF` / `RESULT_ERROR_NO_SERVICE` (scheduled for retry).
- **Airplane mode & exponential backoff:** While Airplane Mode is active, `SmsManager` is not invoked; items wait in the queue. Turning Airplane Mode off (`ACTION_AIRPLANE_MODE_CHANGED` registered dynamically in `Application.onCreate`) triggers a flush after a 15-second radio settle delay. Failed sends retry via `SmsRetryWorker` (`WorkManager`) using `2m → 5m → 15m → 30m → 60m` backoff—zero background work runs when the queue is empty. Messages delayed by more than 2 minutes append their original `[at HH:mm]` timestamp.
- **Anti-spam circuit breakers:**
  - 5-minute duplicate text suppression (`DEDUPE_MS`, bypassed only for manual test SMS)
  - Hard rate limits: **5 SMS / 10 minutes** and **20 SMS / 24 hours** per app
  - Queue cap: **10 items**, max **8 send attempts**, **48-hour** expiration TTL

---

## 7. Permissions & Privacy

Neither app requests `android.permission.INTERNET` or `android.permission.ACCESS_NETWORK_STATE`.

**Take a Break (`:takeabreak`)**
- `BIND_ACCESSIBILITY_SERVICE` — visible package detection, foreground timing, and `TYPE_ACCESSIBILITY_OVERLAY`.
- `SEND_SMS` — direct SIM alert texts (user-initiated optional phone number).
- `QUERY_ALL_PACKAGES` — populates controllable app lists, detects system cameras/dialers, and inspects Watcher package state.
- `RECEIVE_BOOT_COMPLETED` — runs post-boot peer/accessibility check and ensures WorkManager schedules.
- `com.takeabreak.permission.POST_MESSAGE` (signature) — authenticated controller ↔ watcher IPC.

**Take a Break Watcher (`:takeabreak-watcher`)**
- `SEND_SMS` — tamper alert texts using the synced alert number.
- `<queries>` for `com.takeabreak.controller` — inspects controller state without requiring `QUERY_ALL_PACKAGES`.
- `RECEIVE_BOOT_COMPLETED` — runs post-boot check and ensures hourly `PeerCheckWorker`.
- `com.takeabreak.permission.POST_MESSAGE` (signature) — authenticated controller ↔ watcher IPC.

---

## 8. Debug vs. Release Source Sets & Building

Debug diagnostics are isolated in `src/debug/` and replaced with no-op stubs in `src/release/` so release APKs contain zero debug screens or log views:

- **`:takeabreak`:**
  - `src/debug/`: Adds the PIN-gated **Debug** button (`DebugMenu`) and `DebugActivity` (camera detection list, live status, peer/SMS logs, and test reset actions).
  - `src/release/`: Omits the Debug button, `DebugActivity`, and its manifest entry.
- **`:takeabreak-watcher`:**
  - `src/debug/`: `DebugPanel` renders the last 10 received controller alerts, peer link log, and a manual Test SMS input.
  - `src/release/`: `DebugPanel` is a no-op; main screen shows only monitoring status, permission/phone warnings, and **Watcher Setup & Permissions**.

### Build Commands

1. **Development / Debug APKs** (signed with project `debug.keystore` if present):
   ```bash
   ./gradlew :takeabreak:assembleDebug :takeabreak-watcher:assembleDebug
   ```
   Outputs: `takeabreak/build/outputs/apk/debug/` and `takeabreak-watcher/build/outputs/apk/debug/`.

2. **Signed Release APKs:**
   - Copy `keystore.properties.example` to `keystore.properties` (git-ignored) and configure `storeFile`, `storePassword`, `keyAlias`, and `keyPassword` pointing to a shared release keystore.
   - Build both modules together so they share the exact signing key required for `com.takeabreak.permission.POST_MESSAGE`:
     ```bash
     ./gradlew :takeabreak:assembleRelease :takeabreak-watcher:assembleRelease
     ```
   - Uninstall any debug builds signed with a different key before installing release APKs.

### Logcat Tags
- Controller: `TakeABreak`
- Watcher: `Watcher`
