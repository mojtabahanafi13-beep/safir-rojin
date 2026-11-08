package ir.factoranbar.safirrojin;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * دریافت آنیِ اعلان‌ها (مثل حالت «اتصال پس‌زمینه» تلگرام):
 * یک اتصال دائمی (SSE) به سرور نگه داشته می‌شود و هر اعلان همان لحظه می‌رسد؛ دیگر پرسیدن هر ۱۲ ثانیه وجود ندارد.
 * اگر اتصال قطع شود خودش با فاصلهٔ فزاینده دوباره وصل می‌شود و تا وصل‌شدن، به روش قدیمی (پرسیدن هر ۱ دقیقه) برمی‌گردد.
 * اندروید برای ماندگاری سرویس پس‌زمینه یک اعلان کم‌اهمیت الزامی می‌کند؛ این اعلان در کمترین اولویت است
 * (بدون آیکن در نوار وضعیت، بی‌صدا، پنهان در صفحهٔ قفل).
 */
public class SafirNotificationService extends Service {
    private static final String CHANNEL_ID = "safir_bg_sync_channel";
    private static final int NOTIF_ID = 7002;
    private static final int READ_TIMEOUT_MS = 75_000;       // سرور هر ۲۵ ثانیه ping می‌فرستد
    private static final long MAX_BACKOFF_MS = 60_000L;
    private static final long POLL_WHEN_DISCONNECTED_MS = 60_000L;
    private static final long POLL_WHEN_CONNECTED_MS = 10 * 60_000L;

    private ScheduledExecutorService watchdog;
    private ExecutorService syncExec;
    private Thread streamThread;
    private volatile boolean running = false;
    private volatile boolean connected = false;
    private volatile long lastPollAt = 0L;
    private volatile HttpURLConnection currentConn;
    private final Object wake = new Object();
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCb;
    private long netCbRegisteredAt = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIF_ID, buildQuietNotification());
        syncExec = Executors.newSingleThreadExecutor();
        watchdog = Executors.newSingleThreadScheduledExecutor();
        watchdog.scheduleWithFixedDelay(this::watchdogTick, 20, 20, TimeUnit.SECONDS);
        registerNetworkWatcher();
        startStream();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startStream();          // اگر ریسمان به هر دلیل مرده باشد دوباره راه می‌افتد
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // وقتی کاربر برنامه را از لیست اخیر پاک می‌کند، یک بازراه‌اندازی نزدیک زمان‌بندی می‌شود
        try {
            Intent i = new Intent(getApplicationContext(), SafirNotificationService.class);
            int fl = PendingIntent.FLAG_ONE_SHOT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) fl |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? PendingIntent.getForegroundService(getApplicationContext(), 7102, i, fl)
                : PendingIntent.getService(getApplicationContext(), 7102, i, fl);
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1500L, pi);
        } catch (Exception ignored) {}
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        running = false;
        connected = false;
        try { if (cm != null && netCb != null) cm.unregisterNetworkCallback(netCb); } catch (Exception ignored) {}
        try { HttpURLConnection c = currentConn; if (c != null) c.disconnect(); } catch (Exception ignored) {}
        synchronized (wake) { wake.notifyAll(); }
        try { if (watchdog != null) watchdog.shutdownNow(); } catch (Exception ignored) {}
        try { if (syncExec != null) syncExec.shutdownNow(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ───────────── اتصال دائمی ─────────────
    private synchronized void startStream() {
        if (running && streamThread != null && streamThread.isAlive()) return;
        running = true;
        streamThread = new Thread(this::streamLoop, "safir-push-stream");
        streamThread.setDaemon(true);
        streamThread.start();
    }

    private String token() {
        try {
            SharedPreferences sp = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
            String t = sp.getString("token", "");
            return t == null ? "" : t.trim();
        } catch (Exception e) { return ""; }
    }

    private void sleepInterruptibly(long ms) {
        synchronized (wake) {
            try { wake.wait(ms); } catch (InterruptedException ignored) {}
        }
    }

    private void streamLoop() {
        long backoff = 2000L;
        while (running) {
            String token = token();
            if (token.isEmpty()) { sleepInterruptibly(15_000L); continue; }
            long startedAt = System.currentTimeMillis();
            int result = connectAndRead(token);
            connected = false;
            if (!running) break;
            if (result == 401) { backoff = 2000L; sleepInterruptibly(60_000L); continue; }   // ورود منقضی/نامعتبر؛ هجوم نیاوریم
            long lived = System.currentTimeMillis() - startedAt;
            backoff = lived > 30_000L ? 2000L : Math.min(backoff * 2, MAX_BACKOFF_MS);
            sleepInterruptibly(backoff);
        }
    }

    /** @return 401 اگر ورود رد شد؛ در غیر این صورت ۰ (قطع شدن/خطا → اتصال دوباره) */
    private int connectAndRead(String token) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(MainActivity.SERVER_ORIGIN + "/api/dashboard-events?app=driver");
            conn = (HttpURLConnection) url.openConnection();
            currentConn = conn;
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setRequestProperty("Accept-Encoding", "identity");   // فشرده‌سازی جریان را معطل می‌کند
            conn.setUseCaches(false);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            int code = conn.getResponseCode();
            if (code == 401 || code == 403) return 401;
            if (code != 200) return 0;
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            String event = "message";
            StringBuilder data = new StringBuilder();
            String line;
            while (running && (line = br.readLine()) != null) {
                if (!token.equals(token())) break;           // کاربر خارج شد یا کاربر دیگری وارد شد
                if (line.isEmpty()) {
                    dispatch(event, data.toString());
                    event = "message";
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith(":")) continue;           // ping
                if (line.startsWith("event:")) event = line.substring(6).trim();
                else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).trim());
                }
            }
            return 0;
        } catch (Exception e) {
            return 0;
        } finally {
            try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
            currentConn = null;
        }
    }

    private void dispatch(String event, String data) {
        try {
            if ("connected".equals(event)) {
                connected = true;
                try { syncExec.execute(() -> syncState(true)); } catch (Exception ignored) {}   // جبران اعلان‌های زمان قطع بودن
            } else if ("notification".equals(event) && !data.isEmpty()) {
                JSONObject j = new JSONObject(data);
                String title = j.optString("title", "");
                String body = j.optString("body", "");
                String tag = j.optString("tag", "");
                if (tag.isEmpty()) tag = "safir-push-" + System.currentTimeMillis();
                // وقتی برنامه روی صفحه است، خود صفحه همین اعلان را نشان می‌دهد؛ تکراری نشان نمی‌دهیم
                if (!MainActivity.appInForeground) MainActivity.showSystemAlertNotification(this, title, body, tag);
                try { syncExec.execute(() -> syncState(false)); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    // ───────────── شبکه و پایش ─────────────
    private void registerNetworkWatcher() {
        try {
            cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            netCb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    // شبکهٔ جدید (مثلاً تغییر وای‌فای به اینترنت موبایل): اتصال قدیمی احتمالاً مرده است
                    if (System.currentTimeMillis() - netCbRegisteredAt < 4000L) return;
                    try { HttpURLConnection c = currentConn; if (c != null) c.disconnect(); } catch (Exception ignored) {}
                    synchronized (wake) { wake.notifyAll(); }
                }
            };
            netCbRegisteredAt = System.currentTimeMillis();
            NetworkRequest req = new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            cm.registerNetworkCallback(req, netCb);
        } catch (Exception ignored) {}
    }

    private void watchdogTick() {
        try {
            if (!running) return;
            if (streamThread == null || !streamThread.isAlive()) startStream();
            long now = System.currentTimeMillis();
            long gap = now - lastPollAt;
            if (!connected) {
                if (gap >= POLL_WHEN_DISCONNECTED_MS) syncExec.execute(() -> syncState(true));   // حالت جایگزین تا وصل شدن
            } else if (gap >= POLL_WHEN_CONNECTED_MS) {
                syncExec.execute(() -> syncState(false));                                       // فقط به‌روزرسانی وضعیت
            }
        } catch (Exception ignored) {}
    }

    // ───────────── همگام‌سازی وضعیت سفر/پیام (و جبران اعلان‌های از دست‌رفته) ─────────────
    private void alert(boolean notify, String title, String body, String tag) {
        if (!notify || MainActivity.appInForeground) return;
        MainActivity.showSystemAlertNotification(this, title, body, tag);
    }

    private void syncState(boolean notify) {
        lastPollAt = System.currentTimeMillis();
        try {
            SharedPreferences sp = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
            String token = sp.getString("token", "");
            if (token == null || token.isEmpty()) return;

            String tripResp = httpGet(MainActivity.SERVER_ORIGIN + "/api/driver/trip", token);
            if (tripResp == null) return;
            JSONObject j = new JSONObject(tripResp);
            JSONObject trip = j.optJSONObject("trip");
            if (trip == null) {
                if (sp.getBoolean("tripActive", false)) {
                    sp.edit().putBoolean("tripActive", false).apply();
                    stopService(new Intent(this, SafirLocationService.class));
                }
                return;
            }

            String tripId = trip.optString("id", "");
            String status = trip.optString("status", "");
            JSONArray stops = trip.optJSONArray("stops");
            int stopsCount = stops != null ? stops.length() : 0;

            String prevTripId = sp.getString("lastSeenTripId", "");
            int prevStopsCount = sp.getInt("lastSeenStopsCount", 0);

            if (!tripId.isEmpty() && (!tripId.equals(prevTripId) || stopsCount > prevStopsCount)) {
                sp.edit().putString("lastSeenTripId", tripId).putInt("lastSeenStopsCount", stopsCount).apply();
                if (!prevTripId.isEmpty() || stopsCount > 0) {
                    alert(notify, "🚀 ماموریت جدید در سفیر روژین",
                        "برنامهٔ مسیر جدید (" + stopsCount + " مقصد) توسط مدیریت برای شما ارسال شد.",
                        "safir-trip-" + tripId + "-" + stopsCount);
                }
            }

            // اگر سفر در سرور به صورت خودکار پایان یافته است، ردیاب پس‌زمینه گوشی را هم خودکار خاموش کن
            if ("finished".equals(status) && sp.getBoolean("tripActive", false)) {
                sp.edit().putBoolean("tripActive", false).apply();
                stopService(new Intent(this, SafirLocationService.class));
                alert(notify, "🏁 پایان خودکار سفر در انبار",
                    "مسیر شما به پایان رسید و ردیاب گوشی به‌طور خودکار خاموش شد.",
                    "safir-auto-finish-" + tripId);
            }

            // پیام‌های جدید چت مدیریت
            if (!tripId.isEmpty()) {
                String chatResp = httpGet(MainActivity.SERVER_ORIGIN + "/api/driver/conversation?tripId=" + URLEncoder.encode(tripId, "UTF-8"), token);
                if (chatResp != null) {
                    JSONObject cj = new JSONObject(chatResp);
                    JSONArray msgs = cj.optJSONArray("messages");
                    if (msgs != null && msgs.length() > 0) {
                        String lastNotifiedMsgId = sp.getString("lastNotifiedMsgId", "");
                        for (int i = msgs.length() - 1; i >= 0; i--) {
                            JSONObject m = msgs.getJSONObject(i);
                            String senderRole = m.optString("senderRole", "");
                            boolean isRead = !m.isNull("readMs") && m.optLong("readMs", 0) > 0;
                            String msgId = m.optString("id", "");
                            if (!"driver".equals(senderRole) && !isRead && !msgId.isEmpty()) {
                                if (!msgId.equals(lastNotifiedMsgId)) {
                                    sp.edit().putString("lastNotifiedMsgId", msgId).apply();
                                    String type = m.optString("type", "text");
                                    String text = m.optString("text", "");
                                    String title = "photo".equals(type) ? "📷 تصویر جدید از مدیریت"
                                        : ("voice".equals(type) ? "🎙 پیام صوتی جدید از مدیریت" : "💬 پیام جدید از مدیریت");
                                    String body = !text.isEmpty() ? text
                                        : ("photo".equals(type) ? "مدیریت یک تصویر در گفت‌وگو ارسال کرد." : "مدیریت پیامی برای شما ارسال کرده است.");
                                    alert(notify, title, body, "safir-msg-" + msgId);
                                }
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private String httpGet(String urlStr, String token) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(7000);
            if (conn.getResponseCode() != 200) return null;
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    // ───────────── اعلان الزامی سرویس: کمترین اولویت، بی‌صدا، بدون آیکن نوار وضعیت ─────────────
    private Notification buildQuietNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        int pFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent, pFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("سفیر روژین")
            .setContentText("دریافت اعلان‌ها فعال است")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(pi)
            .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "سرویس دریافت اعلان سفیر روژین",
                NotificationManager.IMPORTANCE_MIN
            );
            ch.setShowBadge(false);
            ch.enableLights(false);
            ch.enableVibration(false);
            ch.setSound(null, null);
            ch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            ch.setDescription("اتصال زنده برای رسیدن فوری ماموریت و پیام. می‌توانید این دسته را در تنظیمات اعلان‌ها پنهان کنید.");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }
}
