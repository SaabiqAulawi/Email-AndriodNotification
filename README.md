# Auto-forward email to WhatsApp

## Android app

The `app/` directory is a native Android version. It has no Twilio or WhatsApp
dependency: it polls IMAP in a foreground service and shows a high-priority
Android notification for each unread email.

Open the project in Android Studio, build it, install it, and exempt it from
battery optimization. Gmail users should use an App Password and enable IMAP.
Open the project root (the folder containing `settings.gradle`), not the
`app/src/main/java` folder. Android Studio must complete **Sync Project with
Gradle Files** before Java files under `app/src/main/java` are opened; otherwise
the editor can report missing mandatory classpath entries.

## Background Reliability & Resilience

The app includes three mechanisms to ensure continuous 24/7 background operation:

1. **Auto-restart on Device Boot (`BootReceiver`)**:
   - Listens to `BOOT_COMPLETED` and `QUICKBOOT_POWERON` broadcasts.
   - Automatically restarts `ForwardService` and reenqueues `RestartWorker` upon device restart (if monitoring was enabled).

2. **Battery Optimization Exemption UI**:
   - In-app status indicator and button to request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
   - Prevents aggressive Android Doze and OEM power managers from killing the service or restricting network connections.

3. **WorkManager Fallback (`RestartWorker`)**:
   - Enqueues a periodic WorkManager check every 15 minutes (`ExistingPeriodicWorkPolicy.UPDATE`).
   - If `ForwardService` is terminated by the OS due to memory pressure, `RestartWorker` restarts the service or executes an immediate poll fallback.
   - Cleanly cancelled when the user taps **Stop monitoring**.

## Android setup

1. Enable IMAP for the email account. For Gmail, create an **App Password**; do not use your normal account password.
2. Open this folder in Android Studio and build/install the `app` module (or install `app-debug.apk`).
3. Enter the IMAP host, email, and App Password.
4. Tap **Start monitoring**.
5. Tap **Disable Battery Optimization** in the app to exempt it from system power-saving restrictions.

The foreground service checks every 60 seconds. When it finds an unread
message, it posts a high-priority notification containing the sender, subject,
and body. The email is marked read after the notification is posted.
