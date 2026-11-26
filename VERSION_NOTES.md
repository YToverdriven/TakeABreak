# Take a Break — Version Notes

Newest first. Version 1.0 is reserved until the owner decides the apps are ready.

## 0.12.2 (2026-11-26)

- **Version 0.12.2** (versionCode 122) on both apps.
- **Documentation rewrite:**
  - Simplified `README.md` into a clear, approachable GitHub landing page covering intent, privacy posture, core features, and updated v0.12.x getting-started steps.
  - Added `ARCHITECTURE.md` as a separate technical deep dive covering the two-app signature trust model, multi-window/split-screen blocking engine (`AppLockAccessibilityService`, `AccessPolicy`, `BlockOverlay`), PIN session locking (`PinLockedActivity`), guided setup wizards & Watcher handshake, `PeerLink` tamper detection, durable SMS outbox/retry queue, permissions, and debug/release build & signing workflows.
- Files: README.md, ARCHITECTURE.md (new), VERSION_NOTES.md, takeabreak/build.gradle.kts, takeabreak-watcher/build.gradle.kts, MainActivity.kt (both apps).

## 0.12.1 (2026-11-25)

- **Version 0.12.1** (versionCode 121) on both apps.
- **Fixed Restricted Settings shortcut on Android 13+ / GrapheneOS:** Replaced direct `ACTION_APPLICATION_DETAILS_SETTINGS` launches (where Android hides the `⋮ → Allow restricted settings` option) with **Open Apps Settings** (`ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS`, falling back to `ACTION_APPLICATION_SETTINGS` → `ACTION_SETTINGS`) and updated instructions across both apps so selecting the app from All apps shows the `⋮` menu.
- **Screen 4 (Take a Break Setup):** Added all four SMS alert toggles (**New app installed**, **Tamper/disabled/accessibility off**, **PIN entered to unlock a blocked app**, and **Once-a-day status check**) plus the **Scheduled time / Change Time** picker during first-time setup (still PIN-protected on post-setup revisits).
- **Screens 7 & 8 (Take a Break Setup):** Updated un-blockable apps text to *"Apps like Phone and Settings cannot be blocked."* (removed Camera due to device inconsistency and included the note on Screen 8 as well).
- Files: PermissionsSetupActivity.kt, SmsAlertsActivity.kt, MainActivity.kt, strings.xml, build.gradle.kts. Watcher: PermissionsSetupActivity.kt, DebugPanel.kt, MainActivity.kt, build.gradle.kts. Docs: README.md, VERSION_NOTES.md.

## 0.12.0 (2026-11-25)

- **Version 0.12.0** (versionCode 120) on both apps.
- **Rebuilt multi-step setup wizards for both apps** (not locked to portrait orientation; preserves state across rotation; existing upgraded installs skip the first-time wizard):
  - **Take a Break (`PermissionsSetupActivity`):** 8-step first-time setup flow (`Welcome` → `Enable Accessibility Service` → `Restricted Settings` if needed → `SMS Permission & optional country-coded auto-dashed phone number` → `Mandatory Watcher check & handshake` → `Set a PIN` → `Set Up App Blocking` with Skip/Save → `Set Up Time Limits` with Back, Skip/Save, and empty-selection warning). Reopening **Setup & Permissions** from the home screen walks through Screens 2–5 only and blocks phone number edits without the PIN.
  - **Take a Break Watcher (`PermissionsSetupActivity`):** 3-step setup flow (`1B: Grant SMS Permission` — skips straight to 3B if already granted → `2B: Restricted Settings` if blocked → `3B: Watcher is Set Up.` which signals Take a Break via setup-complete `PONG` and returns to Take a Break).
  - **Home screens:** Added a note on both apps' home screens when no alert phone number is configured, indicating that a PIN is required under **Text Message Alerts** to set up or change the number.
  - **Accessibility description:** Moved the note that Android's shortcut option should not be enabled / is not needed to the beginning of the Accessibility Service description.
- Files: PermissionsSetupActivity.kt, MainActivity.kt, Prefs.kt, PeerLink.kt, PeerMessageReceiver.kt, PinSetupActivity.kt, AndroidManifest.xml, strings.xml, build.gradle.kts. Watcher: PermissionsSetupActivity.kt, MainActivity.kt, PeerLink.kt, strings.xml, build.gradle.kts. Docs: README.md, VERSION_NOTES.md.

## 0.11.1 (2026-11-23)

- **Version 0.11.1** (versionCode 111) on both apps.
- **Fixed: SMS notification for PIN entry on a blocked app did not fire for PIN-locked apps.** Previously, `AppLockAccessibilityService.handlePinAccepted` only sent an alert for `TIME_UP` (time-limit reached) unlocks and ignored `PIN_LOCK` unlocks. Entering the PIN on any blocked app now sends an alert (`ALERT: PIN entered to unlock <App>.` for PIN-locked apps and `ALERT: PIN entered to unlock <App> for the rest of the day.` for time-limited apps) when the toggle (**PIN is entered to unlock a blocked app**) is enabled, still subject to the 5-minute duplicate suppression and rate limits.
- **Dedicated Setup & Permissions screen (`PermissionsSetupActivity`) for BOTH apps on fresh install (and accessible anytime afterward for reconfiguring).** Step order now matches Android 13+ / GrapheneOS behavior where Android hides "Allow restricted settings" in App Info until a restricted setting/permission is attempted once first, with a single **Restricted Settings Help & App Info** button (no duplicate buttons):
  - **Take a Break:** First-time PIN setup flows directly into **Setup & Permissions** (`Step 1: Accessibility Service` → `Step 2: Restricted Settings Help & App Info` → `Step 3: SMS Permission`), and the home screen includes a **Setup & Permissions** button to reopen it anytime. **Text Message Alerts** also shows **Grant SMS Permission** before **Restricted Settings Help & App Info**.
  - **Take a Break Watcher:** Fresh installs open **Watcher Setup & Permissions** (`Step 1: Enable SMS Permission` → `Step 2: Restricted Settings Help & App Info`), and the main screen includes a **Watcher Setup & Permissions** button to reopen it anytime.
- **Removed optional Device Admin support** (`DeviceAdminReceiver`, `device_admin.xml`, `BIND_DEVICE_ADMIN` receiver declaration, and Setup step) to keep Take a Break focused on voluntary usage restrictions without full device-admin privileges.
- Files: PermissionsSetupActivity.kt (new), AppLockAccessibilityService.kt, PeerLink.kt, Prefs.kt, PinSetupActivity.kt, MainActivity.kt, SmsAlertsActivity.kt, AndroidManifest.xml, strings.xml, build.gradle.kts; deleted DeviceAdminReceiver.kt, device_admin.xml. Watcher: PermissionsSetupActivity.kt (new), MainActivity.kt, AndroidManifest.xml, strings.xml, build.gradle.kts. Docs: README.md, VERSION_NOTES.md.

## 0.11.0 (2026-11-23)

- **Version 0.11.0** (versionCode 110) on both apps.
- **New PIN-gated page: Text Message Alerts** (`SmsAlertsActivity`).
  - Enter and save the alert destination phone number (blank by default — replaces the `+1XXXXXXXXXX` placeholder; clearing it turns SMS alerts off and clears the retry queue). Automatically syncs the number and tamper-alert toggle to Take a Break Watcher over a signature-protected broadcast.
  - Grant `SEND_SMS` permission and send a test SMS directly from this screen.
  - Per-event toggles:
    1. **New app installed**
    2. **Take a Break or Watcher deleted, disabled, reset, or accessibility turned off**
    3. **PIN entered to unlock an app for the rest of the day** (fires when PIN unlocks a `TIME_UP` app for the day)
    4. **Once-a-day status check** — defaults to **8:00 PM** local time with a **Change Time** picker (`DailyStatusWorker`, capped strictly at once per calendar day).
- **Durable SMS outbox, airplane mode / low-service retry queue, and anti-spam safeguards** in both apps (`SmsAlerts`, `SmsSentReceiver`, `SmsRetryWorker`).
  - Uses explicit `sentIntent` confirmation so items are removed on `RESULT_OK` and only retried on explicit failure or Airplane Mode.
  - Retries via WorkManager with exponential backoff (`2m → 5m → 15m → 30m → 60m`) only while the outbox is non-empty (zero polling loops / no battery drain), plus an automatic flush 15 seconds after Airplane Mode is switched off. Delayed retries append their original event time.
  - Anti-spam guards: 5-minute duplicate suppression, hard rate limits (max 5 SMS / 10 min and max 20 SMS / 24h per app), max 10 queued messages, max 8 attempts, and 48-hour expiration.
- **Moved camera apps count from home screen to Debug screen.** The home screen now stays focused on locked/limited counts and navigation; Debug shows the camera count alongside the camera list and SMS queue status (plus a Clear queued SMS debug action).
- Files: Prefs.kt, SmsAlerts.kt, SmsSentReceiver.kt, SmsRetryWorker.kt, DailyStatusWorker.kt, SmsAlertsActivity.kt, WatcherMessages.kt, PeerLink.kt, AppLockAccessibilityService.kt, MainActivity.kt, DebugActivity.kt, TakeABreakApp.kt, BootReceiver.kt, AndroidManifest.xml, build.gradle.kts. Watcher: SmsAlerts.kt, SmsSentReceiver.kt, SmsRetryWorker.kt, AlertMessageReceiver.kt, PeerLink.kt, BootReceiver.kt, WatcherApp.kt, MainActivity.kt, AndroidManifest.xml, build.gradle.kts. Docs: README.md, VERSION_NOTES.md.

## 0.10.1 (2026-11-23)

- **Version 0.10.1** (versionCode 101) on both apps.
- **Fixed split-screen blocking glitch.** Previously, the service tracked only the single focused app window (`fgPkg`). Opening a blocked app first in split-screen caused the launcher picker or an unblocked second app to take focus, hiding the block screen or making it flicker/delay. The service now tracks **all visible application windows** simultaneously, keeps a blocked app enforced even when unfocused in split-screen, preserves per-app visit PIN state while each pane remains visible, counts foreground usage across all visible panes, and keeps the overlay steady on one blocked app until unlocked before prompting for a second blocked app.
- **Setup help for Restricted Settings (Android 13+ / GrapheneOS).** While the accessibility service is off, the home screen now shows an **Allow Restricted Settings (Setup Help)** button with step-by-step instructions and direct shortcuts to open Take a Break's App Info screen and Accessibility Settings. First-time PIN setup also opens the home screen automatically once the PIN is saved.
- **Alert when the accessibility service is turned off.** Once the accessibility service has been enabled, turning it off triggers `ALERT: Take a Break accessibility service was turned off!` immediately in `onUnbind`/`onDestroy` (sent via SMS and to the watcher message box), and is also checked on boot, app open, and hourly. Updates and restarts while the service stays enabled do not false-alarm. Take a Break Watcher also performs a backup accessibility check on its boot/hourly/open checks (deduplicated if the controller already reported it).
- **Accessibility service description clarifies the Android shortcut toggle.** Android's Accessibility Settings screen always shows a shortcut switch for every accessibility service (apps cannot remove it); the service description now states clearly that the shortcut does not need to be enabled for Take a Break to function.
- Files: AppLockAccessibilityService.kt, AccessPolicy.kt, MenuUi.kt, PeerLink.kt, MainActivity.kt, PinSetupActivity.kt, strings.xml, build.gradle.kts. Watcher: PeerLink.kt, AlertMessageReceiver.kt, MainActivity.kt, build.gradle.kts. Docs: README.md, VERSION_NOTES.md.

## 0.10.0 (2026-11-21)

- **Version 0.10.0** (versionCode 100) on both apps. The home screen reads that version from the installed app.
- **Uninstalls of other apps are recorded.** A new install was already recorded. Removing an app was recorded only when the removed app was Take a Break Watcher, and on Android 8+ that broadcast often never arrived unless the accessibility service was on. Any uninstall now sends `ALERT: App uninstalled: <package>` by SMS and into the watcher message box. The signal is `PACKAGE_FULLY_REMOVED`, which Android delivers even when Take a Break is in the background. An update is still not recorded as an uninstall. The watcher itself still uses the existing line `ALERT: Take a Break Watcher was uninstalled!`.
- **Each app watches the other.** Uninstall and storage cleared are heard as they happen. Disable is heard immediately while that app's process is alive, and otherwise on the next check. Force-stop is noticed on the next check, and only after the other app had been seen running, so a watcher that has never been opened does not raise an alarm. Messages: `was disabled!`, `storage was cleared!`, and `was stopped or its storage was cleared!`.
- **The check is not a loop.** A continuous check would keep waking the phone. Boot runs one check. A short job runs about once an hour, and Android may batch it a little later while the phone is asleep. Opening either app, or the accessibility service connecting, also runs one check for that process. After a force-stop, open that app once so its hourly job is scheduled again. The other app's check still notices the stop.
- **Link check.** Those same moments, each app sends a ping to the other and logs it. A reply is logged when it arrives. Logcat tags are `TakeABreak` and `Watcher`. The controller Debug screen and the watcher screen show the same lines. The ping is not an SMS and it is not stored in the ten-message box.
- **Watcher test SMS.** The watcher screen has a text box and a Send SMS button. The message goes to the same placeholder number as the alerts. The button asks for SMS permission if it is not granted yet.
- Files: PackageMonitorReceiver.kt, PeerLink.kt, PeerMessageReceiver.kt, PeerCheckWorker.kt, BootReceiver.kt, SmsAlerts.kt, TakeABreakApp.kt, AppLockAccessibilityService.kt, MainActivity.kt, DebugActivity.kt, AndroidManifest.xml, build.gradle.kts. Watcher: the same new files, WatcherApp.kt, MainActivity.kt, AndroidManifest.xml, build.gradle.kts.

## 0.9.1 (2026-11-20)

- **Version 0.9.1** (versionCode 91) on both apps. The watcher version fields previously said 1.0.
- **App Usage Limits says Save** instead of Back. Save closes the screen. Each limit is still stored when you tap Set or Clear, so the screen does not have to stay open.
- **Every system camera app is never blocked**, not only the default camera. Lookup asks for every app that handles a camera intent, and also for other launchable system apps with "camera" in the package name. A sideloaded camera can still be locked. Debug lists each camera app. The home screen shows a count when there is more than one.
- **Prep for 0.10.0.** The watcher has a message box that loads the last ten messages received from Take a Break. Each controller alert is delivered to the watcher as well as by SMS. The box stays empty until an alert is sent. Opening the watcher loads what it has stored. The SMS number is still the placeholder. Both apps must be signed with the same key or the watcher rejects the message.
- **Fixed: installing an app did not reach the watcher.** Android 8 and later does not deliver package-added broadcasts to a manifest receiver. The accessibility service now listens for them while it is on, and sends `ALERT: New app installed: <package>` to the watcher.
- **An app update is not reported as a new install.** `PACKAGE_ADDED` with `EXTRA_REPLACING` is an update, so no alert is sent.
- Files: MainActivity.kt, MenuUi.kt, UsageLimitsActivity.kt, AppSelectorActivity.kt, DebugActivity.kt, AccessPolicy.kt, ProtectedPackages.kt, PackageMonitorReceiver.kt, WatcherMessages.kt, build.gradle.kts, AndroidManifest.xml. Watcher: MainActivity.kt, ReceivedMessages.kt, AlertMessageReceiver.kt, AndroidManifest.xml, build.gradle.kts.

## 0.9.0 (2026-11-20)

- **Fixed the block screen flashing.** Showing the PIN overlay sends window events from Take a Break's own package, and those class names are often empty or a layout, not the overlay. The service treated that as "the user left the app", removed the PIN screen, saw the blocked app again, and put the screen back. It now ends the visit only for a real Take a Break activity. A probe that sees our own package while the PIN screen is up is ignored. The overlay no longer grabs focus when it appears, which was starting that same loop.
- **Debug screen** (home screen, PIN required): clear today's time spent, reset every app to no limit, clear PIN locks, clear today's day-unlocks and 5-minute extensions, add 2 minutes of usage to each limited app, and a status readout (accessibility, protected apps on this phone, last service note).
- **Back button** on PIN setup, the lock list, usage limits, time spent, and debug.
- **PIN prompt** before those screens is an on-screen pad, so there is no text box to tap. PIN setup still uses text fields, and the keyboard opens on its own. The block screen also takes focus as soon as it appears.
- **Time-spent chart was behind real usage.** Three causes: the list was built only when the screen was first created (coming back showed old numbers), usage was written every 5 seconds, and each row was rounded to the nearest minute (under 30 seconds showed as nothing). Usage is now saved about once a second, opening any Take a Break screen saves the visit in progress first, the list reloads when you return to it, and each row shows minutes and seconds. Time on the block screen is still not counted.
- **These are never blocked**, and they are hidden from the lock and limit lists: system camera, Phone (the default dialer and the system call screen), Settings, Google Play services, and the default SMS app. The Play Store can still be locked or given a limit.
- Files: MainActivity.kt, DebugActivity.kt, PinPrompt.kt, PinSetupActivity.kt, AppSelectorActivity.kt, UsageLimitsActivity.kt, TimeSpentActivity.kt, AccessPolicy.kt, ProtectedPackages.kt, AppLockAccessibilityService.kt, BlockOverlay.kt, Prefs.kt, AndroidManifest.xml, build.gradle.kts.

## 0.8.3 (2026-11-18)
- **Block screen is now removed whenever the lock screen is showing** (for example Android asking for fingerprint/PIN after unpinning), and the visit ends. When you come back, the app in front is re-checked and blocked again if needed.
- **Added diagnostic logging** (Logcat tag `TakeABreak`): window events, which app the service thinks is in front, lock-screen/screen state, and each block decision. Used to find out what Android actually shows after unpinning.
- Pinned hint text now mentions the fingerprint/PIN prompt.
- Note: the 0.8.2 zip was packaged by mistake without its changes (identical to 0.8.1). 0.8.3 includes what 0.8.2 was meant to contain.
- Files: AppLockAccessibilityService.kt, BlockOverlay.kt.

## 0.8.1 (2026-11-18)
- **Fixed: a blocked app that was screen-pinned could not be unpinned** because the block screen covered the navigation (Home/Back/Overview) area.
  - The block screen now sizes itself from the real navigation-bar insets and leaves the nav bar / gesture area (and the side nav bar in landscape) uncovered.
  - Touches outside the block screen now pass through to the system (FLAG_NOT_TOUCH_MODAL).
  - The block screen is rebuilt if the phone is rotated while it is showing.
- Files: BlockOverlay.kt.

## 0.8.0 (2026-11-18) — Locking system rewrite
**Fixes:** apps sometimes not locking when opened; screen pinning bypassing limits; features conflicting.
- **One enforcement point.** New `AccessPolicy` decides ALLOW / PIN_LOCK / TIME_UP. Time limit is always checked first; a PIN entered earlier no longer excuses an over-limit app.
- **One blocking screen.** New `BlockOverlay` (accessibility overlay with on-screen PIN pad) replaces LockActivity, TimeUpActivity and the separate "app is pinned" overlay. It is a window, not an activity launch, so it works on top of a screen-pinned app and no longer races with the app opening.
- **Limits enforced live.** The time limit is checked every second while an app is open, not only when re-entering it.
- **Front app is verified every second** from the real window list, so a missed event can no longer leave an app unprotected. Also re-checked right after screen-on / unlock.
- **Leaving to Home/Recents now ends the visit** (it used to be ignored, so an unlocked app could be re-opened without a PIN).
- **Screen off ends the visit** (PIN needed again after the screen turns off and back on).
- Removed: app-launch relaunch with CLEAR_TASK, 2.5 s / 3 s ignore windows, unpin-instruction overlay, "Allow display over other apps" button and SYSTEM_ALERT_WINDOW permission (no longer needed).
- Accessibility config now requests window-list access (`canRetrieveWindowContent`, `flagRetrieveInteractiveWindows`); only the front app's package name is read.
- App version fields set to 0.8.0 (versionCode 80); they previously said 1.0.
- Files: AppLockAccessibilityService.kt (rewritten), AccessPolicy.kt (new), BlockOverlay.kt (new), Prefs.kt, MainActivity.kt, AndroidManifest.xml, accessibility_service_config.xml, strings.xml, build.gradle.kts. Deleted: LockActivity.kt, TimeUpActivity.kt.
- **Untested on device.** After installing, turn the Take a Break accessibility service off and on once so Android picks up the new permissions.

## 0.7.9 (2026-11-18)
- **System Back and Overview buttons are pressable while the unpin overlay is shown.**
  - In portrait the overlay now leaves left, right, and bottom edges free so side gestures and the system navigation buttons are outside the window and can be pressed.
  - Bottom reserve is larger so the button touch targets are not covered.
- **Fixed free-use bypass after pressing Home in the popup.**
  - Pressing the Home / “Try Home again” button no longer suppresses the lock on re-entry.
  - Returning to a controlled app always re-applies the PIN lock or Time Up screen (unless a session unlock is active).
  - Per-package launch cooldown reduced from 12 s to 3 s so a quick leave-and-return is not ignored.
  - currentPkg is no longer cleared on escape, so the service still knows which app is controlled.
- Files updated: AppLockAccessibilityService.kt.

## 0.7.8 (2026-11-18)
- Dynamic nav-bar reserve and gesture-nav instructions.
- Overlay re-shown if dismissed while still pinned.
- Files updated: AppLockAccessibilityService.kt.

## 0.7.7 and earlier
- See previous notes.
