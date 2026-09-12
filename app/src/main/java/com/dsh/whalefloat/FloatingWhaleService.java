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
 * 数据来源（混合式，优先联动 DSH）：
 *   1. 先读 DSH 引擎在本机开的接口 http://127.0.0.1:3080/dsh-whale/balance.json —— 能拿到
 *      「今日已用」和「峰谷时段」，数字与 DSH 网页挂件完全一致；此时显示绿色引擎状态点。
 *   2. DSH 没开 / 接口失败时，回退到 App 自己直连 https://api.deepseek.com/user/balance，
 *      只显示余额（该接口不返回用量），状态点隐藏。
 * 窗口：TYPE_APPLICATION_OVERLAY，靠 specialUse 前台服务保活，进程被回收后系统会带 null
 * intent 重启服务（见 onStartCommand 里的 recreate 分支）。
 */
public class FloatingWhaleService extends Service {

    private static final String CHANNEL_ID = "whale_overlay";
    private static final int NOTIF_ID = 1001;
    private static final String BALANCE_URL = "https://api.deepseek.com/user/balance";
    /** DSH 引擎侧的余额接口（由 dsh-whale-widget 插件提供，比官方接口多出今日已用/峰谷）。 */
    private static final String DSH_BALANCE_URL = "http://127.0.0.1:3080/dsh-whale/balance.json";

    private WindowManager wm;
    private View root;
    private TextView bubble;
    private View dot;
    private WindowManager.LayoutParams lp;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    /** 最近一次成功的余额，网络抖动时继续显示它，不闪错误。 */
    private volatile Double lastBalance = null;
    private volatile String lastCurrency = null;
    /** 最近一次数据是否来自 DSH（决定状态点显示与气泡文案）。 */
    private volatile boolean fromDsh = false;
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
        dot = root.findViewById(R.id.engineDot);

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

    /** 余额数据统一入口：先试 DSH（数据更全），失败再直连 DeepSeek。 */
    private void refresh() {
        if (!shown) return;
        if (lastBalance == null) setBubble("…");
        final String key = prefs.getString(MainActivity.KEY_API, "");
        io.execute(() -> {
            try {
                apply(fetchFromDsh(), true);
                return;
            } catch (Exception dshErr) {
                // DSH 没开或没装插件：退化成直连，只显示余额
                if (key == null || key.trim().isEmpty()) {
                    handler.post(() -> setBubble(lastBalance != null
                            ? format(lastBalance, lastCurrency)
                            : "未配置 API Key"));
                    return;
                }
                try {
                    apply(fetchFromDirect(key.trim()), false);
                } catch (Exception directErr) {
                    handler.post(() -> setBubble(lastBalance != null
                            ? format(lastBalance, lastCurrency) // 网络抖动：沿用上次数字
                            : "读余额失败：" + shortMsg(directErr)));
                }
            }
        });
    }

    /** 把一次成功的结果写进界面状态：余额、来源、气泡文案、状态点。 */
    private void apply(Balance b, boolean fromDshSource) {
        lastBalance = b.amount;
        lastCurrency = b.currency;
        fromDsh = fromDshSource;
        final String text = format(b.amount, b.currency)
                + (b.todayUsage != null
                    ? " · 今日 ¥" + String.format(java.util.Locale.US, "%.2f", b.todayUsage)
                      + (Boolean.TRUE.equals(b.isPeak) ? " 高峰" : " 空闲")
                    : "");
        handler.post(() -> {
            setBubble(text);
            if (dot != null) dot.setVisibility(fromDshSource ? View.VISIBLE : View.GONE);
        });
    }

    /** 一次余额观测结果。todayUsage / isPeak 只有 DSH 接口能给，直连时为 null。 */
    private static final class Balance {
        final double amount;
        final String currency;
        final Double todayUsage;
        final Boolean isPeak;

        Balance(double amount, String currency, Double todayUsage, Boolean isPeak) {
            this.amount = amount;
            this.currency = currency;
            this.todayUsage = todayUsage;
            this.isPeak = isPeak;
        }
    }

    /** 读 DSH 插件的余额接口。超时故意设短：DSH 没开时不要拖慢回退。 */
    private Balance fetchFromDsh() throws Exception {
        JSONObject o = getJson(DSH_BALANCE_URL, null, 2500, 3000);
        if (!o.optBoolean("ok", false)) throw new Exception("DSH 未就绪");
        String currency = o.optString("currency", "CNY");
        return new Balance(o.optDouble("total_balance", o.optDouble("totalBalance", 0)), currency,
                o.has("todayUsage") ? o.optDouble("todayUsage", 0) : null,
                o.has("isPeak") ? o.optBoolean("isPeak", false) : null);
    }

    /** 直连 DeepSeek 官方余额接口；多币种时优先 CNY 且非零，其次任意非零，再退回 CNY，最后第一项。 */
    private Balance fetchFromDirect(String key) throws Exception {
        JSONObject o = getJson(BALANCE_URL, key, 15000, 20000);
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
        return new Balance(pick.optDouble("total_balance", 0),
                pick.optString("currency", "CNY"), null, null);
    }

    /** 发一个 GET 并把响应体解析成 JSON。key 为 null 时不带 Authorization。 */
    private JSONObject getJson(String url, String key, int connectMs, int readMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("GET");
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setRequestProperty("Accept", "application/json");
            if (key != null) c.setRequestProperty("Authorization", "Bearer " + key);
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
            return new JSONObject(bos.toString("UTF-8"));
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
