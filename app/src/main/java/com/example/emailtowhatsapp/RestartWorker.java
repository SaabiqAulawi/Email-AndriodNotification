package com.example.emailtowhatsapp;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * WorkManager fallback that restarts ForwardService if it gets killed by the system.
 * This ensures email monitoring survives aggressive battery optimization and low memory kills.
 */
public class RestartWorker extends Worker {
    private static final String TAG = "RestartWorker";
    public static final String WORK_NAME = "email_forward_restart_worker";

    public RestartWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        boolean isMonitoring = prefs.getBoolean("is_monitoring", false);
        String user = prefs.getString("username", "");
        String pass = prefs.getString("password", "");

        // Only do work if monitoring is active and credentials are configured
        if (!isMonitoring || user.isEmpty() || pass.isEmpty()) {
            return Result.success();
        }

        // If ForwardService is not running, attempt to restart it
        if (!ForwardService.isRunning) {
            Log.d(TAG, "ForwardService is not running. Attempting restart...");
            try {
                Intent serviceIntent = new Intent(context, ForwardService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
                Log.d(TAG, "ForwardService restarted by RestartWorker.");
            } catch (Exception e) {
                Log.w(TAG, "Failed to start foreground service directly from worker. Falling back to direct poll.", e);
                // Fallback: poll emails directly in the background worker
                ForwardService.poll(context);
            }
        } else {
            Log.d(TAG, "ForwardService is running normally.");
        }

        return Result.success();
    }

    /**
     * Enqueue a periodic WorkManager task that checks health every 15 minutes.
     */
    public static void schedule(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest workRequest = new PeriodicWorkRequest.Builder(
                RestartWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest);
        Log.d(TAG, "RestartWorker periodic fallback scheduled.");
    }

    /**
     * Cancel the periodic WorkManager task when user stops monitoring.
     */
    public static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
        Log.d(TAG, "RestartWorker periodic fallback cancelled.");
    }
}
