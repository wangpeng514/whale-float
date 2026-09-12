package com.dsh.whalefloat;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 配置界面：填 API Key、设刷新间隔、开关悬浮窗。
 * 悬浮窗本身由 {@link FloatingWhaleService} 以 specialUse 前台服务维持。
 *
 * 继承系统 Activity（而不是 AppCompatActivity）：本工程刻意不依赖 androidx，
 * 这样构建时不需要任何第三方依赖，避免 Kotlin 标准库重复类（Duplicate class）的编译错误。
 */
public class MainActivity extends Activity {

    static final String PREFS = "whale";
    static final String KEY_API = "api_key";
    static final String KEY_INTERVAL = "interval";
    static final String KEY_RUNNING = "running";
    static final String KEY_POSX = "pos_x";
    static final String KEY_POSY = "pos_y";

    private static final int REQ_NOTIF = 101;

    private EditText apiKey, interval;
    private Button save, toggle;
    private TextView status;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        apiKey = findViewById(R.id.apiKey);
        interval = findViewById(R.id.interval);
        save = findViewById(R.id.save);
        toggle = findViewById(R.id.toggle);
        status = findViewById(R.id.status);

        apiKey.setText(prefs.getString(KEY_API, ""));
        interval.setText(String.valueOf(prefs.getInt(KEY_INTERVAL, 60)));

        // 服务可能在别的进程生命周期里启停过，以实际运行状态为准
        boolean running = prefs.getBoolean(KEY_RUNNING, false)
                && isServiceActuallyRunning();
        prefs.edit().putBoolean(KEY_RUNNING, running).apply();

        save.setOnClickListener(v -> saveSettings());
        toggle.setOnClickListener(v -> toggleOverlay());

        requestNotificationPermissionIfNeeded();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean running = prefs.getBoolean(KEY_RUNNING, false) && isServiceActuallyRunning();
        prefs.edit().putBoolean(KEY_RUNNING, running).apply();
        render();
    }

    private boolean isServiceActuallyRunning() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return false;
            String me = FloatingWhaleService.class.getName();
            for (android.app.ActivityManager.RunningServiceInfo info
                    : am.getRunningServices(Integer.MAX_VALUE)) {
                if (info.service != null && me.equals(info.service.getClassName())) return true;
            }
        }
        return true;
    }

    private void saveSettings() {
        String key = apiKey.getText().toString().trim();
        int sec = 60;
        try {
            sec = Integer.parseInt(interval.getText().toString().trim());
        } catch (Exception ignored) {
        }
        if (sec < 10) sec = 10;
        if (sec > 3600) sec = 3600;
        interval.setText(String.valueOf(sec));
        prefs.edit().putString(KEY_API, key).putInt(KEY_INTERVAL, sec).apply();
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
    }

    private void toggleOverlay() {
        if (prefs.getBoolean(KEY_RUNNING, false) && isServiceActuallyRunning()) {
            stopService(new Intent(this, FloatingWhaleService.class));
            prefs.edit().putBoolean(KEY_RUNNING, false).apply();
            render();
            return;
        }
        saveSettings();
        if (prefs.getString(KEY_API, "").isEmpty()) {
            Toast.makeText(this, "先填 API Key", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予「显示在其他应用上层」权限", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(new Intent(this, FloatingWhaleService.class));
        } else {
            startService(new Intent(this, FloatingWhaleService.class));
        }
        prefs.edit().putBoolean(KEY_RUNNING, true).apply();
        render();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    private void render() {
        boolean running = prefs.getBoolean(KEY_RUNNING, false) && isServiceActuallyRunning();
        boolean overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        toggle.setText(running ? "隐藏鲸鱼" : "显示鲸鱼");
        status.setText("悬浮窗权限：" + (overlayOk ? "已授予 ✅" : "未授予 ❌（点“显示鲸鱼”会带你去开）")
                + "\n运行状态：" + (running ? "运行中 ✅" : "未运行")
                + "\n刷新间隔：" + prefs.getInt(KEY_INTERVAL, 60) + " 秒"
                + "\n\n用法：填 Key → 保存 → 点“显示鲸鱼”。"
                + "\n之后可以直接把本 App 从后台划掉，鲸鱼会继续浮在屏幕上。"
                + "\n拖动鲸鱼上方的 ✥ 挪位置（松手自动吸附到最近的边），点鲸鱼立刻刷新，长按气泡复制余额。");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                          int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        render();
    }
}
