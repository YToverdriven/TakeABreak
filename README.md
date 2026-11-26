# Take a Break

**Version 0.12.2**

Take a Break is an offline Android accountability suite for people who want to spend less time on their phones.

Instead of relying on willpower alone—or sending your usage data to a cloud account—Take a Break lets someone you trust set a PIN, lock distracting apps, enforce daily screen-time limits, and receive SMS alerts if limits are bypassed or the app is tampered with.

- **100% offline & private:** No internet permission, no accounts, no analytics, no Google Play services, and no Firebase. Screen content is never read or stored.
- **Built for GrapheneOS and stock Android:** Runs on Android 8.0 (API 26) and newer.
- **Two-app design:** Paired with **Take a Break Watcher**, a lightweight companion app that sends an SMS alert if Take a Break is ever disabled, force-stopped, cleared, turned off, or uninstalled.
- **Voluntary by design:** No Device Admin lockouts—if there is ever an emergency or critical issue, the app can still be removed (while notifying your accountability partner via SMS).

---

## Features

- **PIN-Locked Apps:** Choose any apps (such as browsers, social media, shopping apps, or VPNs) that can only be opened after entering a 4+ digit PIN. Essential apps like Phone and Settings can never be blocked.
- **Daily Time Limits:** Set per-app daily usage caps (from 1 minute up to 23h 59m) that reset automatically at midnight. When time runs out, the user can either take a single 5-minute extension per app per day or have the PIN entered to unlock the app for the rest of the day.
- **Today's Time Spent:** View a sorted breakdown of foreground time spent in each app today without needing the PIN.
- **Text Message Alerts (Optional):** Send direct SIM SMS alerts to an accountability partner—with automatic airplane-mode/no-service queuing and anti-spam protections—when:
  - A new app is installed
  - Take a Break or Watcher is uninstalled, disabled, reset, or Accessibility Service is turned off
  - The PIN is entered to unlock a blocked or time-limited app
  - A once-a-day status check runs (customizable time, default 8:00 PM)
- **Companion Tamper Monitoring (`Take a Break Watcher`):** Watches Take a Break in the background without battery-draining loops and sends an SMS alert if Take a Break is removed or disabled.
- **Guided First-Time Setup:** Both apps include a step-by-step setup wizard covering Accessibility Service, Android 13+ / GrapheneOS Restricted Settings, SMS permissions, Watcher pairing, PIN creation, app blocking, and time limits.

---

## Getting Started

1. **Install both APKs** (`Take a Break` and `Take a Break Watcher`) signed with the same key.
2. **Open Take a Break** and follow the on-screen setup wizard:
   - Enable **Accessibility Service** (the Android shortcut toggle is not needed). On Android 13+ / GrapheneOS, follow the Restricted Settings prompt if blocked.
   - Grant **SMS Permission** and optionally configure your alert phone number, alert toggles, and daily status check time.
   - Tap **Continue Setup on Watcher App** when prompted, grant **SMS Permission** in Watcher, and tap **Return to Take a Break**.
   - Have your accountability partner set a **PIN** (4+ digits), then choose any apps to **PIN-block** or give **daily time limits**.
3. **Everyday use:** Once setup finishes, Take a Break opens directly to its home screen. Changing the PIN, editing locked apps, adjusting limits, or updating SMS alert settings all require the PIN.

---

## Documentation

- **[Technical Architecture & How It Works (ARCHITECTURE.md)](ARCHITECTURE.md)** — Deep dive into the two-app trust model, multi-window/split-screen blocking, `AccessPolicy`, overlay window design, PIN session locking, inter-app broadcasts, durable SMS retry queue, permissions, and building/signing release APKs.
- **[Version Notes (VERSION_NOTES.md)](VERSION_NOTES.md)** — Full release history and changelog. *(Version 1.0 is reserved until the apps are feature-complete.)*
