# Take a Break

**Version 0.11.0**

Take a Break restricts phone use. A PIN, preferably chosen by someone other than the person who uses the phone, gates selected apps. Daily time limits block an app once its allowance for the day is used up. Configurable events can send an SMS to a chosen phone number.

The project is two Android apps. They are meant to run on GrapheneOS or stock Android, with no Google Play services or Firebase. The screens are simple on purpose until the features are finished.

Version 1.0 is reserved until the apps are ready. Change history is in [VERSION_NOTES.md](VERSION_NOTES.md).

| App | Module | Package | What it does today |
| --- | --- | --- | --- |
| Take a Break | `:takeabreak` | `com.takeabreak.controller` | PIN, app lock, daily limits, today's usage, blocking, configurable SMS alerts with retry queue, and daily status check |
| Take a Break Watcher | `:takeabreak-watcher` | `com.takeabreak.watcher` | Watches the controller. Sends an SMS (using the synced alert number and durable retry queue) if Take a Break is uninstalled, disabled, force-stopped, has its storage cleared, or has its accessibility service turned off. Shows the last ten messages from Take a Break, a link log, and a box to send a test SMS |

## Take a Break (controller)

The home screen shows how many apps are PIN-locked and how many have a usage limit. From there you can:

- Change the PIN
- Select apps to lock
- Set app usage limits
- View time spent on apps
- Configure **Text Message Alerts** (PIN required)
- Open the system screen to enable the accessibility service
- Open setup help for **Allow Restricted Settings** (shown while the accessibility service is off, with step-by-step instructions and shortcuts to App Info and Accessibility Settings for Android 13+ / GrapheneOS sideloaded installs)
- Enable device admin (optional)
- Open Debug (camera app count and list, SMS queue status, clear today's usage, clear limits, and other test actions)

Changing the PIN, choosing locked apps, editing limits, opening Text Message Alerts, and opening Debug each ask for the current PIN on a number pad, with no text field to tap first. Time spent can be opened without it. Each of those screens has a Back button, except App Usage Limits, which says Save and closes the screen.

### PIN

The first launch asks for a PIN of at least 4 digits, entered twice. Those fields take focus immediately and the keyboard opens without an extra tap. The PIN is stored as a SHA-256 hash. Every other PIN check, including the block screen, uses an on-screen number pad. There is no fingerprint or face unlock inside Take a Break.

### App lock

The lock list is the user-installed apps on the phone, plus the Play Store. A locked app asks for the PIN once per visit.

These are never blocked, even if an older save named them: every system camera app (a phone can have more than one), Phone (the default dialer and the system call screen), Settings, Google Play services, and the default SMS app. They are left off the lock list and the limit list. A sideloaded camera can still be locked. The Play Store stays available to lock.

A visit starts when that app becomes visible on screen (full-screen or in split-screen). It ends when that app is no longer visible: when the user goes Home, switches away, closes its split-screen pane, turns the screen off, or the lock screen appears. That includes the fingerprint or PIN prompt Android shows after unpinning an app. Opening the app again asks for the PIN again.

### Daily time limits

Each app can have its own limit, from 1 minute up to 23 hours 59 minutes. Limits and the usage counters reset at midnight. The launcher and the current keyboard are left off the limit list.

The limit is checked every second while the app is in front. Reaching the limit blocks the app even if the PIN was already entered during that visit.

On the time-up screen there are two ways through:

- Enter the PIN to unlock that app for the rest of the day.
- Take a one-time 5-minute extension. The extension does not ask for the PIN, and each app gets it once per day.

### Time spent

The time-spent screen lists today's foreground time, longest first, in minutes and seconds, with a bar that darkens as the time grows. Time is counted only while an app is in front and the block screen is hidden. It is saved about once a second, and again as soon as a Take a Break screen opens, so the list is not a visit behind. The list reloads whenever you return to it. Take a Break's own packages, the launcher, and the keyboard are left off the list. If nothing has been counted yet, the screen says so.

### How blocking works

[AppLockAccessibilityService](takeabreak/src/main/java/com/takeabreak/controller/AppLockAccessibilityService.kt) tracks **every visible app window** on screen (one in full-screen, two in split-screen). It learns that from window changes and from checks of the real window list, so a missed event or an unfocused split-screen pane cannot leave a blocked app exposed.

In split-screen, opening the launcher picker or an unblocked app in the second pane does not end the first app's visit or hide the block screen while the blocked app is still visible. If two blocked apps are open in split-screen at the same time, the block screen stays steady on one app until it is unlocked and then immediately prompts for the other. Foreground usage is counted for all visible apps while the block screen is hidden.

[AccessPolicy](takeabreak/src/main/java/com/takeabreak/controller/AccessPolicy.kt) is the only place that decides:

1. **Protected app.** A system camera, Phone, Settings, Play services, or the default SMS app: allow it. Every system camera is included, not only the default one. The Play Store is not in this group.
2. **Time limit.** Over the daily limit, and not day-unlocked, and not inside a 5-minute extension: show the time-up screen.
3. **PIN lock.** On the locked list, and the PIN has not been entered this visit: show the PIN screen.
4. **Allow.** Otherwise leave the app alone.

[BlockOverlay](takeabreak/src/main/java/com/takeabreak/controller/BlockOverlay.kt) is the only block screen. It is an accessibility overlay window, so it does not need "display over other apps", and it can sit on top of a screen-pinned app. The navigation bar and the gesture area stay outside the window, so Back, Overview, and swipe-up-and-hold can still unpin the app. Touches in that strip go to the system. Rotating the phone rebuilds the overlay. While the lock screen is up, the overlay is removed and all visits end.

If Android refuses to show the overlay, the service sends the user Home.

The service reads only the package names of visible apps. It does not read or store what is on screen. Android's accessibility settings screen always displays a shortcut toggle for every accessibility service; Take a Break's service description notes that the shortcut does not need to be enabled for the service to function. Logcat tag: `TakeABreak`.

After installing an update, turn the Take a Break accessibility service off and on once so Android picks up its window-list access.

### Device admin

Device admin is optional. The home screen can request it. On a dedicated phone the controller can also be set as device owner with adb (see below).

## Text Message Alerts & Retry Queue

The **Text Message Alerts** screen (PIN-protected, inside Take a Break) configures the destination phone number (blank by default — no SMS alerts are queued or sent until a number is saved) and syncs the phone number and tamper-alert setting to Take a Break Watcher over a signature-protected broadcast. Both apps can request `SEND_SMS` permission and send test texts.

Toggleable SMS alert categories:

| Toggle | Who sends the SMS | Message |
| --- | --- | --- |
| **New app installed** (updates ignored) | Controller | `ALERT: New app installed: <package>` |
| **Take a Break or Watcher deleted, disabled, reset, or accessibility off** | Controller / Watcher (whichever survives) | `ALERT: … was uninstalled!`, `was disabled!`, `storage was cleared!`, `was stopped or its storage was cleared!`, or `Take a Break accessibility service was turned off!` |
| **PIN entered for day-long app access** (when PIN unlocks a time-limited app for the rest of today) | Controller | `ALERT: PIN entered to unlock <App> for the rest of the day.` |
| **Once-a-day status check** (default **8:00 PM** local time; customizable via time picker) | Controller | `Take a Break daily status: Accessibility ON/OFF, Watcher …, X locked, Y limited.` |

Uninstalls of other (non-monitoring) apps are still recorded in the link log and Watcher's ten-message box, without sending untoggled SMS.

### Durable Outbox, Airplane Mode Retry Queue & Anti-Spam Protections

Both apps use a persisted SMS outbox ([SmsAlerts](takeabreak/src/main/java/com/takeabreak/controller/SmsAlerts.kt) / [SmsAlerts](takeabreak-watcher/src/main/java/com/takeabreak/watcher/SmsAlerts.kt)) designed to survive airplane mode and low cellular service without battery drain or accidental SMS spam:

- **Delivery handoff confirmation:** Every send uses an explicit `sentIntent` (`SmsSentReceiver`). An item stays `IN_FLIGHT` until Android confirms `RESULT_OK` (removed) or reports an error such as `RESULT_ERROR_RADIO_OFF` / `RESULT_ERROR_NO_SERVICE` (scheduled for retry).
- **Airplane mode / low-service backoff:** While Airplane Mode is on, messages stay queued without calling `SmsManager`. Failed sends retry via WorkManager with exponential backoff (`2m → 5m → 15m → 30m → 60m`), and no retry worker runs when the outbox is empty. Turning Airplane Mode off triggers a flush after a 15-second radio settle delay. Delayed messages include their original timestamp (`[at HH:mm]`).
- **Anti-spam circuit breakers:** Identical alerts are deduplicated for 5 minutes; each app enforces hard send caps (**max 5 SMS per 10 minutes** and **max 20 SMS per 24 hours**); queue size is capped at 10; items expire after 8 failed attempts or 48 hours; and the once-a-day status check records its calendar date so it can never send more than once per day.

An app that has already been removed cannot send its own alert. Uninstall and storage-cleared are system broadcasts, so they are recorded when they happen, including while Take a Break is in the background. Turning off the accessibility service is detected immediately when Android unbinds the service (without false alarms during updates or before it was first enabled), and checked again at boot, app open, and hourly. Install alerts are still heard only while the accessibility service is on. After installing an update, turn that service off and on once.

Disable and force-stop are not announced to an app that is not running. Each app looks those up at boot and about once an hour. Opening an app, or the accessibility service connecting, also runs one check for that process. That hourly job is a short piece of work Android can batch, including a little later while the phone is asleep. It is not a continuous loop, and there is no foreground service for it. A loop that kept checking would drain the battery. This does not.

The same check sends a ping to the other app and logs whether a reply comes back. Logcat tags are `TakeABreak` and `Watcher`. The controller Debug screen and the watcher screen show those lines. The ping is not an SMS, and it is not one of the ten stored messages. Both apps must be signed with the same key, which they are when built from this project, or the ping, config sync, and message box are rejected.

The watcher message box loads the last ten alerts the controller has sent it.

## Planned

What is still missing:

- No alert when device admin is turned off. Disabling the app itself or turning off the accessibility service is covered.

## Building

1. Open this folder in Android Studio.
2. Let Gradle sync.
3. Build `:takeabreak:assembleDebug` and `:takeabreak-watcher:assembleDebug`.
4. APKs are in each module's `build/outputs/apk/debug/` folder.

The apps require Android 8.0 (API 26) or newer. Compile SDK and target SDK are 34. UI is built in code with platform views.

## Device owner (optional)

```bash
adb install takeabreak-debug.apk
adb shell dpm set-device-owner com.takeabreak.controller/.DeviceAdminReceiver
```

Device owner can be set on a device with no accounts, usually right after setup or a factory reset.

## Permissions

**Controller**

- Accessibility service. Required for blocking and for counting foreground time.
- `SEND_SMS`. Alerts.
- `QUERY_ALL_PACKAGES`. Build the lock list and the limit list, and see whether the watcher is installed.
- `RECEIVE_BOOT_COMPLETED`. One link check after boot, and to schedule the hourly check.

**Watcher**

- `SEND_SMS`
- `<queries>` for `com.takeabreak.controller`. See whether the controller is installed without needing `QUERY_ALL_PACKAGES`.
- `RECEIVE_BOOT_COMPLETED`. One link check after boot, and to schedule the hourly check.

## On the phone

1. Install both apps.
2. Open Take a Break and set the PIN.
3. On Android 13+ / GrapheneOS, if Accessibility is grayed out as a restricted setting, tap **Allow Restricted Settings (Setup Help)** on the home screen to open App Info and allow restricted settings.
4. Enable the Take a Break accessibility service (the Android shortcut toggle does not need to be enabled).
5. Choose locked apps and any daily limits.
6. Open **Text Message Alerts** (PIN required), enter the alert phone number, choose which alerts to enable (including the daily status check time), and grant SMS permission. Open Take a Break Watcher once and grant SMS permission there too if you want tamper alerts when Take a Break is removed.
