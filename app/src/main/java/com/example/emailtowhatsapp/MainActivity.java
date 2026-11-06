package com.example.emailtowhatsapp;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private SharedPreferences preferences;
    private EditText host, username, password;
    private TextView tvServiceStatus, tvBatteryStatus;
    private Button btnBatteryOpt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preferences = getSharedPreferences("settings", MODE_PRIVATE);
        host = findViewById(R.id.imapHost);
        username = findViewById(R.id.username);
        password = findViewById(R.id.password);
        tvServiceStatus = findViewById(R.id.tvServiceStatus);
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus);
        btnBatteryOpt = findViewById(R.id.btnBatteryOpt);

        host.setText(preferences.getString("host", "imap.gmail.com"));
        username.setText(preferences.getString("username", ""));
        password.setText(preferences.getString("password", ""));

        findViewById(R.id.start).setOnClickListener(v -> startMonitoring());
        findViewById(R.id.stop).setOnClickListener(v -> stopMonitoring());
        btnBatteryOpt.setOnClickListener(v -> requestIgnoreBatteryOptimization());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUiState();
    }

    private void startMonitoring() {
        String hostStr = host.getText().toString().trim();
        String userStr = username.getText().toString().trim();
        // Remove spaces in case user pasted Google App Password formatted with spaces (e.g., "abcd efgh ijkl mnop")
        String passStr = password.getText().toString().trim().replace(" ", "");

        if (hostStr.isEmpty() || userStr.isEmpty() || passStr.isEmpty()) {
            Toast.makeText(this, "Please enter IMAP host, email, and password", Toast.LENGTH_SHORT).show();
            return;
        }

        long cutoffTime = System.currentTimeMillis() - (10 * 60 * 1000L); // Only notify for emails from last 10 minutes or newer

        preferences.edit()
                .putString("host", hostStr)
                .putString("username", userStr)
                .putString("password", passStr)
                .putBoolean("is_monitoring", true)
                .putLong("monitoring_start_time", cutoffTime)
                .apply();

        Intent serviceIntent = new Intent(this, ForwardService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // Schedule WorkManager fallback to restart service if killed
        RestartWorker.schedule(this);

        updateUiState();
        Toast.makeText(this, "Monitoring started with auto-restart and WorkManager fallback", Toast.LENGTH_SHORT).show();

        // Prompt battery optimization if not already exempted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Toast.makeText(this, "Recommended: Exempt app from battery optimization for 24/7 reliability", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void stopMonitoring() {
        preferences.edit()
                .putBoolean("is_monitoring", false)
                .apply();

        stopService(new Intent(this, ForwardService.class));
        RestartWorker.cancel(this);

        updateUiState();
        Toast.makeText(this, "Monitoring stopped", Toast.LENGTH_SHORT).show();
    }

    private void updateUiState() {
        boolean isMonitoring = preferences.getBoolean("is_monitoring", false);
        if (isMonitoring || ForwardService.isRunning) {
            tvServiceStatus.setText("Service Status: Running (Active 24/7)");
        } else {
            tvServiceStatus.setText("Service Status: Stopped");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            boolean isIgnoring = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            if (isIgnoring) {
                tvBatteryStatus.setText("Battery Optimization: Exempted (Optimal / Unrestricted)");
                btnBatteryOpt.setText("Battery: Already Exempted");
                btnBatteryOpt.setEnabled(false);
            } else {
                tvBatteryStatus.setText("Battery Optimization: Active (Android may kill background service)");
                btnBatteryOpt.setText("Disable Battery Optimization");
                btnBatteryOpt.setEnabled(true);
            }
        } else {
            tvBatteryStatus.setText("Battery Optimization: Not applicable on this Android version");
            btnBatteryOpt.setVisibility(View.GONE);
        }
    }

    private void requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } else {
                    Toast.makeText(this, "App is already exempt from battery optimization", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                try {
                    Intent fallbackIntent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    startActivity(fallbackIntent);
                } catch (Exception ex) {
                    Intent detailsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    detailsIntent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(detailsIntent);
                }
            }
        }
    }
}
