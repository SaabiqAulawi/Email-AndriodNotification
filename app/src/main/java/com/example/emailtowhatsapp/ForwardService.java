package com.example.emailtowhatsapp;

import android.app.*;
import android.content.*;
import android.os.*;
import androidx.core.app.NotificationCompat;
import java.util.*;
import javax.mail.*;
import javax.mail.search.FlagTerm;

public class ForwardService extends Service {
    private static final String SERVICE_CHANNEL = "email-monitor";
    private static final String ALERT_CHANNEL = "email-alerts";
    private Handler handler;
    private Runnable poller;
    private boolean running;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(1, new NotificationCompat.Builder(this, SERVICE_CHANNEL)
                .setContentTitle("Email monitor").setContentText("Monitoring unread email")
                .setSmallIcon(android.R.drawable.ic_dialog_email).setOngoing(true).build());
        handler = new Handler(Looper.getMainLooper());
        running = true;
        poller = () -> { new Thread(this::poll).start(); if (running) handler.postDelayed(poller, 60000); };
        poller.run();
    }

    private void poll() {
        SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        String host = p.getString("host", ""), user = p.getString("username", "");
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
            for (Message message : inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false))) {
                String text = format(message);
                postEmailNotification(message, text);
                message.setFlag(Flags.Flag.SEEN, true);
            }
            inbox.close(false); store.close();
        } catch (Exception e) {
            android.util.Log.e("EmailToWhatsApp", "Polling failed", e);
        }
    }

    private void postEmailNotification(Message message, String text) throws Exception {
        int notificationId = Math.abs(message.getMessageNumber());
        Notification notification = new NotificationCompat.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(message.getSubject() == null ? "New email" : message.getSubject())
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EMAIL)
                .setAutoCancel(true)
                .build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(notificationId, notification);
    }

    private String format(Message message) throws Exception {
        String from = message.getFrom() == null ? "" : message.getFrom()[0].toString();
        String subject = message.getSubject() == null ? "(no subject)" : message.getSubject();
        Object content = message.getContent();
        String body = content instanceof String ? (String) content : "(multipart email)";
        String result = "New email\nFrom: " + from + "\nSubject: " + subject + "\n\n" + body;
        return result.length() > 4000 ? result.substring(0, 3999) + "…" : result;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26)
            {
                NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                manager.createNotificationChannel(new NotificationChannel(
                        SERVICE_CHANNEL, "Email monitoring", NotificationManager.IMPORTANCE_LOW));
                manager.createNotificationChannel(new NotificationChannel(
                        ALERT_CHANNEL, "Email alerts", NotificationManager.IMPORTANCE_HIGH));
            }
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) { return START_STICKY; }
    @Override public void onDestroy() { running = false; if (handler != null) handler.removeCallbacksAndMessages(null); super.onDestroy(); }
    @Override public android.os.IBinder onBind(Intent intent) { return null; }
}
