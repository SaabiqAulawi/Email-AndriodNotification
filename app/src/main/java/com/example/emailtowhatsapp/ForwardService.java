package com.example.emailtowhatsapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.core.app.NotificationCompat;
import java.util.Date;
import java.util.Properties;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.search.FlagTerm;

public class ForwardService extends Service {
    public static final String SERVICE_CHANNEL = "email-monitor";
    public static final String ALERT_CHANNEL = "email-alerts";
    public static volatile boolean isRunning = false;

    private Handler handler;
    private Runnable poller;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels(this);
        startForeground(1, new NotificationCompat.Builder(this, SERVICE_CHANNEL)
                .setContentTitle("Email monitor")
                .setContentText("Monitoring unread email")
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setOngoing(true)
                .build());
        isRunning = true;
        handler = new Handler(Looper.getMainLooper());
        poller = () -> {
            new Thread(() -> poll(this)).start();
            if (isRunning) handler.postDelayed(poller, 60000);
        };
        poller.run();
    }

    public static void poll(Context context) {
        SharedPreferences p = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String host = p.getString("host", "");
        String user = p.getString("username", "");
        String pass = p.getString("password", "");
        if (host.isEmpty() || user.isEmpty() || pass.isEmpty()) return;
        try {
            Properties props = new Properties();
            props.put("mail.imap.ssl.enable", "true");
            Session session = Session.getInstance(props);
            Store store = session.getStore("imap");
            store.connect(host, 993, user, pass);
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);

            long cutoffTime = p.getLong("monitoring_start_time", System.currentTimeMillis() - (10 * 60 * 1000L));
            Message[] messages = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));

            // Process newest messages first and skip emails older than monitoring start time
            for (int i = messages.length - 1; i >= 0; i--) {
                Message message = messages[i];
                Date msgDate = message.getReceivedDate() != null ? message.getReceivedDate() : message.getSentDate();
                if (msgDate != null && msgDate.getTime() < cutoffTime) {
                    continue; // Ignore old unread emails from months/years ago
                }
                String text = format(message);
                postEmailNotification(context, message, text);
                message.setFlag(Flags.Flag.SEEN, true);
            }
            inbox.close(false);
            store.close();
        } catch (Exception e) {
            android.util.Log.e("ForwardService", "Polling failed", e);
        }
    }

    private static void postEmailNotification(Context context, Message message, String text) throws Exception {
        createChannels(context);
        int notificationId = Math.abs(message.getMessageNumber());
        Notification notification = new NotificationCompat.Builder(context, ALERT_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(message.getSubject() == null ? "New email" : message.getSubject())
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EMAIL)
                .setAutoCancel(true)
                .build();
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(notificationId, notification);
        }
    }

    private static String format(Message message) throws Exception {
        String from = message.getFrom() == null ? "" : message.getFrom()[0].toString();
        String subject = message.getSubject() == null ? "(no subject)" : message.getSubject();
        Object content = message.getContent();
        String body = content instanceof String ? (String) content : "(multipart email)";
        String result = "New email\nFrom: " + from + "\nSubject: " + subject + "\n\n" + body;
        return result.length() > 4000 ? result.substring(0, 3999) + "…" : result;
    }

    public static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                if (manager.getNotificationChannel(SERVICE_CHANNEL) == null) {
                    manager.createNotificationChannel(new NotificationChannel(
                            SERVICE_CHANNEL, "Email monitoring", NotificationManager.IMPORTANCE_LOW));
                }
                if (manager.getNotificationChannel(ALERT_CHANNEL) == null) {
                    manager.createNotificationChannel(new NotificationChannel(
                            ALERT_CHANNEL, "Email alerts", NotificationManager.IMPORTANCE_HIGH));
                }
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int id) {
        isRunning = true;
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
