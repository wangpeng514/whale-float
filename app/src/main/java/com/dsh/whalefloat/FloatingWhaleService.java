package com.dsh.whalefloat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 悬浮余额鲸鱼。
 *
 * 数据来源：App 自己直连 https://api.deepseek.com/user/balance（不依赖 DSH，DSH 关着也能用）。
 * 窗口：TYPE_APPLICATION_OVERLAY，靠 specialUse 前台服务保活，进程被回收后系统会带 null
 * intent 重启服务（见 onStartCommand 里的 recreate 分支）。
 */
public class FloatingWhaleService extends Service {

    private static final String CHANNEL_ID = "whale_overlay";
    private static final int NOTIF_ID = 1001;
    private static final String BALANCE_URL = "https://api.deepseek.com/user/balance";

    private WindowManager wm;
    private View root;
    private TextView bubble;
    private WindowManager.LayoutParams lp;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    /** 最近一次成功的余额，网络抖动时继续显示它，不闪错误。 */
    private volatile Double lastBalance = null;
    private volatile String lastCurrency = null;
    private volatile boolean shown = false;

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, intervalMs());
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        createChannel();
        startForeground(NOTIF_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            // 系统回收进程后用 null intent 把我们拉回来：只在用户上次确实是"开着"时才重建窗口
            if (prefs.getBoolean(MainActivity.KEY_RUNNING, false)) {
                handler.post(this::showOverlay);
                handler.post(refreshTask);
            } else {
                stopSelf();
            }
            return START_REDELIVER_INTENT;
        }
        showOverlay();
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        removeOverlay();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 悬浮窗

    private void showOverlay() {
        if (shown || root != null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !android.provider.Settings.canDrawOverlays(this)) {
            toast("没有悬浮窗权限，无法显示鲸鱼");
            stopSelf();
            return;
        }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        root = LayoutInflater.from(this).inflate(R.layout.overlay_whale, null);
        bubble = root.findViewById(R.id.bubble);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        int[] size = screenSize();
        int defX = Math.max(0, size[0] - dp(170) - dp(8));
        int defY = Math.max(0, (int) (size[1] * 0.58));
        int x = prefs.getInt(MainActivity.KEY_POSX, defX);
        int y = prefs.getInt(MainActivity.KEY_POSY, defY);
        // 屏幕旋转/分辨率变化后旧坐标可能跑到屏幕外，钳制回来
        x = Math.min(Math.max(0, x), Math.max(0, size[0] - dp(80)));
        y = Math.min(Math.max(0, y), Math.max(0, size[1] - dp(80)));
        lp.x = x;
        lp.y = y;

        wireDrag();
        root.findViewById(R.id.whale).setOnClickListener(v -> refresh());
        bubble.setOnClickListener(v -> refresh());
        bubble.setOnLongClickListener(v -> {
            copy(bubble.getText().toString());
            return true;
        });

        try {
            wm.addView(root, lp);
            shown = true;
        } catch (Exception e) {
            toast("添加悬浮窗失败：" + e.getMessage());
            root = null;
        }
    }

    private void removeOverlay() {
        if (root != null && wm != null) {
            try {
                wm.removeView(root);
            } catch (Exception ignored) {
            }
        }
        root = null;
        shown = false;
    }

    /** 把手拖动 + 松手吸附到最近的左右边；位移小于阈值算点击刷新。 */
    private void wireDrag() {
        final View handle = root.findViewById(R.id.handle);
        handle.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = startX + (int) (e.getRawX() - downX);
                        lp.y = startY + (int) (e.getRawY() - downY);
                        update();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (Math.abs(e.getRawX() - downX) < dp(6)
                                && Math.abs(e.getRawY() - downY) < dp(6)) {
                            refresh();
                            return true;
                        }
                        snapToEdge();
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void snapToEdge() {
        int[] size = screenSize();
        int w = root.getWidth() > 0 ? root.getWidth() : dp(150);
        int margin = dp(8);
        lp.x = (lp.x + w / 2) < size[0] / 2 ? margin : Math.max(margin, size[0] - w - margin);
        lp.y = Math.min(Math.max(0, lp.y), Math.max(0, size[1] - dp(120)));
        update();
        prefs.edit().putInt(MainActivity.KEY_POSX, lp.x)
                .putInt(MainActivity.KEY_POSY, lp.y).apply();
    }

    private void update() {
        if (root != null && wm != null) {
            try {
                wm.updateViewLayout(root, lp);
            } catch (Exception ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- 余额

    private int intervalMs() {
        int sec = prefs.getInt(MainActivity.KEY_INTERVAL, 60);
        if (sec < 10) sec = 10;
        return sec * 1000;
    }

    private void refresh() {
        // 首次刷新且还没有数据时先给个占位，避免空白
        if (!shown) return;
        if (lastBalance == null) setBubble("…");
        final String key = prefs.getString(MainActivity.KEY_API, "");
        if (key == null || key.trim().isEmpty()) {
            setBubble("未配置 API Key");
            return;
        }
        io.execute(() -> {
            try {
                String[] r = fetchBalance(key.trim());
                lastBalance = Double.parseDouble(r[0]);
                lastCurrency = r[1];
                final String text = format(lastBalance, lastCurrency);
                handler.post(() -> setBubble(text));
            } catch (Exception e) {
                handler.post(() -> {
                    if (lastBalance != null) {
                        setBubble(format(lastBalance, lastCurrency)); // 沿用上次值
                    } else {
                        setBubble("读余额失败：" + shortMsg(e));
                    }
                });
            }
        });
    }

    /** @return [金额, 币种]；多币种时优先 CNY 且非零，其次任意非零，再退回 CNY，最后第一项。 */
    private String[] fetchBalance(String key) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BALANCE_URL).openConnection();
        try {
            c.setRequestMethod("GET");
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream in = c.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 200000) break;
            }
            in.close();
            JSONObject o = new JSONObject(bos.toString("UTF-8"));
            JSONArray infos = o.optJSONArray("balance_infos");
            if (infos == null || infos.length() == 0) throw new Exception("返回结构异常");
            JSONObject pick = null;
            for (int i = 0; i < infos.length(); i++) {
                JSONObject it = infos.getJSONObject(i);
                if ("CNY".equals(it.optString("currency")) && it.optDouble("total_balance", 0) > 0) {
                    pick = it;
                    break;
                }
            }
            if (pick == null) {
                for (int i = 0; i < infos.length(); i++) {
                    JSONObject it = infos.getJSONObject(i);
                    if (it.optDouble("total_balance", 0) > 0) {
                        pick = it;
                        break;
                    }
                }
            }
            if (pick == null) {
                for (int i = 0; i < infos.length(); i++) {
                    if ("CNY".equals(infos.getJSONObject(i).optString("currency"))) {
                        pick = infos.getJSONObject(i);
                        break;
                    }
                }
            }
            if (pick == null) pick = infos.getJSONObject(0);
            String currency = pick.optString("currency", "CNY");
            double amount = pick.optDouble("total_balance", 0);
            return new String[]{String.valueOf(amount), currency};
        } finally {
            c.disconnect();
        }
    }

    private static String format(Double amount, String currency) {
        if (amount == null) return "…";
        String sym = "CNY".equals(currency) ? "¥ " : "";
        return sym + String.format(java.util.Locale.US, "%.2f", amount)
                + (sym.isEmpty() && currency != null ? " " + currency : "");
    }

    private void setBubble(String text) {
        if (bubble != null) bubble.setText(text);
    }

    private static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null) m = e.getClass().getSimpleName();
        if (m.startsWith("HTTP 401")) return "API Key 无效";
        return m.length() > 40 ? m.substring(0, 40) : m;
    }

    // ---------------------------------------------------------------- 杂项

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "悬浮鲸鱼",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("保持小鲸鱼余额悬浮窗运行");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("小鲸鱼余额运行中")
                .setContentText("点这里打开设置 / 隐藏鲸鱼")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private int[] screenSize() {
        DisplayMetrics dm = new DisplayMetrics();
        if (wm != null) wm.getDefaultDisplay().getRealMetrics(dm);
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("balance", text));
            toast("已复制：" + text);
        }
    }
}
