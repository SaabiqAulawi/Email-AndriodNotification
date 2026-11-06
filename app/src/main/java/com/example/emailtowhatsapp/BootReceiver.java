package com.example.emailtowhatsapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * Automatically restarts the email monitoring service after the device boots.
 * Only starts if the user previously enabled monitoring with valid credentials.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) &&
            !"android.intent.action.QUICKBOOT_POWERON".equals(action) &&
            !"com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        Log.d(TAG, "Boot event received: " + action);

        SharedPreferences prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        boolean isMonitoring = prefs.getBoolean("is_monitoring", false);
        String user = prefs.getString("username", "");
        String pass = prefs.getString("password", "");

        // Only auto-start if the user previously enabled monitoring and has configured credentials
        if (isMonitoring && !user.isEmpty() && !pass.isEmpty()) {
            try {
                Intent serviceIntent = new Intent(context, ForwardService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
                Log.d(TAG, "ForwardService started on boot.");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start ForwardService on boot", e);
            }

            // Ensure WorkManager periodic fallback is also active
            RestartWorker.schedule(context);
        }
    }
}
