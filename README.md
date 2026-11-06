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

## Android setup

1. Enable IMAP for the email account. For Gmail, create an **App Password**; do not use your normal account password.
2. Open this folder in Android Studio and build/install the `app` module.
3. Enter the IMAP host, email, and App Password.
4. Tap **Start monitoring** and exempt the app from Android battery optimization.

The foreground service checks every 60 seconds. When it finds an unread
message, it posts a high-priority notification containing the sender, subject,
and body. The email is marked read after the notification is posted.
