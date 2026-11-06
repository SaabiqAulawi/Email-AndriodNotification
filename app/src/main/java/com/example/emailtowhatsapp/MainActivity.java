package com.example.emailtowhatsapp;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private SharedPreferences preferences;
    private EditText host, username, password;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        preferences = getSharedPreferences("settings", MODE_PRIVATE);
        host = findViewById(R.id.imapHost);
        username = findViewById(R.id.username);
        password = findViewById(R.id.password);
        host.setText(preferences.getString("host", "imap.gmail.com"));
        username.setText(preferences.getString("username", ""));
        ((Button) findViewById(R.id.start)).setOnClickListener(v -> startMonitoring());
        ((Button) findViewById(R.id.stop)).setOnClickListener(v -> stopService(new Intent(this, ForwardService.class)));
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
    }

    private void startMonitoring() {
        preferences.edit().putString("host", host.getText().toString().trim())
                .putString("username", username.getText().toString().trim())
                .putString("password", password.getText().toString()).apply();
        Intent intent = new Intent(this, ForwardService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
    }
}
