# Take a Break

**Version 0.12.1**

Take a Break restricts phone use. A PIN, preferably chosen by someone other than the person who uses the phone, gates selected apps. Daily time limits block an app once its allowance for the day is used up. Configurable events can send an SMS to a chosen phone number.

The project is two Android apps. They are meant to run on GrapheneOS or stock Android, with no Google Play services or Firebase. The screens are simple on purpose until the features are finished.

Version 1.0 is reserved until the apps are ready. Change history is in [VERSION_NOTES.md](VERSION_NOTES.md).

| App | Module | Package | What it does today |
| --- | --- | --- | --- |
| Take a Break | `:takeabreak` | `com.takeabreak.controller` | PIN, app lock, daily limits, today's usage, blocking, configurable SMS alerts with retry queue, and daily status check |
| Take a Break Watcher | `:takeabreak-watcher` | `com.takeabreak.watcher` | Watches the controller. Sends an SMS (using the synced alert number and durable retry queue) if Take a Break is uninstalled, disabled, force-stopped, has its storage cleared, or has its accessibility service turned off. Release builds show only status and setup (no visible logs); debug builds also show the last ten messages from Take a Break, a link log, and a box to send a test SMS |

## Take a Break (controller)

The home screen shows how many apps are PIN-locked and how many have a usage limit. From there you can:

- Change the PIN
- Select apps to lock
- Set app usage limits
- View time spent on apps
- Configure **Text Message Alerts** (PIN required)
- Open **Setup & Permissions** (shown automatically on fresh install right after PIN setup, and accessible anytime to reconfigure Accessibility, Restricted Settings, and SMS permission)
- Open Debug (**debug builds only** — camera app count and list, SMS queue status, clear today's usage, clear limits, and other test actions; excluded from release builds)

Changing the PIN, choosing locked apps, editing limits, opening Text Message Alerts, and (in debug builds) opening Debug each ask for the current PIN on a number pad, with no text field to tap first. Leaving the app while on any PIN-locked screen forces it back to the main screen so the PIN is required again on return. Time spent and Setup & Permissions can be opened without a PIN. Each of those screens has a Back button, except App Usage Limits, which says Save and closes the screen.

### PIN

The first launch asks for a PIN of at least 4 digits, entered twice. Those fields take focus immediately and the keyboard opens without an extra tap. The PIN is stored as a SHA-256 hash. Every other PIN check, including the block screen, uses an on-screen number pad. There is no fingerprint or face unlock inside Take a Break.

### App lock

The lock list and limit list include all launchable apps on the phone (both user-installed and preinstalled, such as Vanadium or the Play Store), excluding protected system apps, launchers, and keyboards. A locked app asks for the PIN once per visit.

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

## Text Message Alerts & Retry Queue

The **Text Message Alerts** screen (PIN-protected, inside Take a Break) configures the destination phone number (blank by default — no SMS alerts are queued or sent until a number is saved) and syncs the phone number and tamper-alert setting to Take a Break Watcher over a signature-protected broadcast. Both apps can request `SEND_SMS` permission and send test texts.

Toggleable SMS alert categories:

| Toggle | Who sends the SMS | Message |
| --- | --- | --- |
| **New app installed** (updates ignored) | Controller | `ALERT: New app installed: <package>` |
| **Take a Break or Watcher deleted, disabled, reset, or accessibility off** | Controller / Watcher (whichever survives) | `ALERT: … was uninstalled!`, `was disabled!`, `storage was cleared!`, `was stopped or its storage was cleared!`, or `Take a Break accessibility service was turned off!` |
| **PIN entered to unlock a blocked app** (PIN-locked app or time-limit reached) | Controller | `ALERT: PIN entered to unlock <App>.` or `ALERT: PIN entered to unlock <App> for the rest of the day.` |
| **Once-a-day status check** (default **8:00 PM** local time; customizable via time picker) | Controller | `Take a Break daily status: Accessibility ON/OFF, Watcher …, X locked, Y limited.` |

Uninstalls of other (non-monitoring) apps are still recorded in the link log and Watcher's ten-message box, without sending untoggled SMS.

### Durable Outbox, Airplane Mode Retry Queue & Anti-Spam Protections

Both apps use a persisted SMS outbox ([SmsAlerts](takeabreak/src/main/java/com/takeabreak/controller/SmsAlerts.kt) / [SmsAlerts](takeabreak-watcher/src/main/java/com/takeabreak/watcher/SmsAlerts.kt)) designed to survive airplane mode and low cellular service without battery drain or accidental SMS spam:

- **Delivery handoff confirmation:** Every send uses an explicit `sentIntent` (`SmsSentReceiver`). An item stays `IN_FLIGHT` until Android confirms `RESULT_OK` (removed) or reports an error such as `RESULT_ERROR_RADIO_OFF` / `RESULT_ERROR_NO_SERVICE` (scheduled for retry).
- **Airplane mode / low-service backoff:** While Airplane Mode is on, messages stay queued without calling `SmsManager`. Failed sends retry via WorkManager with exponential backoff (`2m → 5m → 15m → 30m → 60m`), and no retry worker runs when the outbox is empty. Turning Airplane Mode off triggers a flush after a 15-second radio settle delay. Delayed messages include their original timestamp (`[at HH:mm]`).
- **Anti-spam circuit breakers:** Identical alerts are deduplicated for 5 minutes; each app enforces hard send caps (**max 5 SMS per 10 minutes** and **max 20 SMS per 24 hours**); queue size is capped at 10; items expire after 8 failed attempts or 48 hours; and the once-a-day status check records its calendar date so it can never send more than once per day.

An app that has already been removed cannot send its own alert. Uninstall and storage-cleared are system broadcasts, so they are recorded when they happen, including while Take a Break is in the background. Turning off the accessibility service is detected immediately when Android unbinds the service (without false alarms during updates or before it was first enabled), and checked again at boot, app open, and hourly. Install alerts are still heard only while the accessibility service is on. After installing an update, turn that service off and on once.

Disable and force-stop are not announced to an app that is not running. Each app looks those up at boot and about once an hour. Opening an app, or the accessibility service connecting, also runs one check for that process. That hourly job is a short piece of work Android can batch, including a little later while the phone is asleep. It is not a continuous loop, and there is no foreground service for it. A loop that kept checking would drain the battery. This does not.

The same check sends a ping to the other app and logs whether a reply comes back. Logcat tags are `TakeABreak` and `Watcher`. In **debug builds**, the controller Debug screen and the Watcher screen show those lines, and the Watcher message box shows the last ten alerts the controller has sent it. In **release builds**, the controller Debug screen is excluded and **the Watcher app has no visible logs** (no Messages list, no Link log, and no Test SMS box — only status and Setup & Permissions). The ping is not an SMS, and it is not one of the ten stored messages. Both apps must be signed with the same key, which they are when built from this project, or the ping, config sync, and message box are rejected.

## Debug vs Release builds

Debug-only tools are separated using Gradle `src/debug/` and `src/release/` source sets so they are completely excluded from release APKs:

- **Take a Break (`:takeabreak`)**
  - **Debug:** Includes the PIN-gated **Debug** button and `DebugActivity` (`takeabreak/src/debug/`).
  - **Release:** Omits the **Debug** button, `DebugActivity`, and its manifest entry (`takeabreak/src/release/`).
- **Take a Break Watcher (`:takeabreak-watcher`)**
  - **Debug:** Shows **Messages from Take a Break**, **Link log**, and the **Test SMS** box (`takeabreak-watcher/src/debug/`).
  - **Release:** Has **no visible logs** and no Test SMS box (`takeabreak-watcher/src/release/`); shows only the monitoring status and **Watcher Setup & Permissions**.

## Building

1. Open this folder in Android Studio.
2. Let Gradle sync.
3. For development/testing with debug tools, build `:takeabreak:assembleDebug` and `:takeabreak-watcher:assembleDebug` (APKs in `build/outputs/apk/debug/`).
4. For user testing / distribution without debug tools or visible Watcher logs:
   - Copy `keystore.properties.example` to `keystore.properties` (git-ignored) and point it at your shared release keystore (e.g., `release.keystore`, also git-ignored).
   - Build `:takeabreak:assembleRelease` and `:takeabreak-watcher:assembleRelease` (signed APKs in `build/outputs/apk/release/`).
   - Both modules read the same `keystore.properties`, ensuring both release APKs are signed with the same key so signature-protected broadcasts between Take a Break and Watcher succeed. Uninstall any debug builds from the phone before installing release builds signed with a different key.

The apps require Android 8.0 (API 26) or newer. Compile SDK and target SDK are 34. UI is built in code with platform views.

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
2. Open **Take a Break** and set the PIN. The **Setup & Permissions** screen opens automatically:
   - **Step 1 (Accessibility Service):** Tap **Open Accessibility Settings** and tap **Take a Break**. On Android 13+ / GrapheneOS, dismiss the "Restricted setting" popup if shown — doing this once unlocks Restricted Settings in App Info.
   - **Step 2 (Restricted Settings):** Tap **Restricted Settings Help & App Info** → **Open App Info**, open the `⋮` menu (or scroll down on GrapheneOS), and enable **Allow restricted settings**. Then return to Step 1 to turn on **Take a Break** (the Android shortcut toggle does not need to be enabled).
   - **Step 3 (SMS Permission):** Tap **Grant SMS Permission** (for alert texts).
3. Choose locked apps and any daily limits, then open **Text Message Alerts** (PIN required) to enter the alert phone number and choose which alerts to enable (including the daily status check time).
4. Open **Take a Break Watcher**. Its **Watcher Setup & Permissions** screen opens automatically:
   - **Step 1 (SMS Permission):** Tap **Grant SMS Permission** first (on Android 13+ / GrapheneOS, attempting this once unlocks Restricted Settings in App Info if blocked).
   - **Step 2 (Restricted Settings):** If Step 1 was blocked, tap **Restricted Settings Help & App Info** → **Open App Info**, enable **Allow restricted settings**, then return to Step 1 to grant SMS permission.
5. Both setup screens remain accessible anytime via **Setup & Permissions** on each app's main screen.
