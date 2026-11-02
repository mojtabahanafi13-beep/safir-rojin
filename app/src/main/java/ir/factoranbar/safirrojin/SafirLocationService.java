package ir.factoranbar.safirrojin;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SafirLocationService extends Service implements LocationListener {
    private static final String CHANNEL_ID = "safir_gps_tracking_channel";
    private static final int NOTIF_ID = 7001;
    private LocationManager locationManager;
    private PowerManager.WakeLock wakeLock;
    private final ExecutorService netExecutor = Executors.newSingleThreadExecutor();
    private long lastSentMs = 0;
    private volatile long lastFixMs = 0;
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private final Object queueLock = new Object();
    private static final int MAX_QUEUE = 3000;
    private static final String QUEUE_FILE = "safir_pos_queue.txt";
    public static final String ACTION_REVIVE = "ir.factoranbar.safirrojin.REVIVE";
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            try { runWatchdog(); } catch (Exception ignored) {}
            watchdogHandler.postDelayed(this, 30000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SafirRojin:GpsWakeLock");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if ("STOP_TRACKING".equals(action)) {
            cancelRevive();
            stopForeground(true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        String trackCode = prefs.getString("trackCode", "");
        if (!prefs.getBoolean("tripActive", false) || trackCode == null || trackCode.trim().isEmpty()) {
            stopForeground(true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        startForeground(NOTIF_ID, buildTrackingNotification());
        if (!hasLocationPermission()) {
            stopForeground(true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (wakeLock != null && !wakeLock.isHeld()) {
            try {
                // این قفل فقط تا پایان سفر فعال نگه داشته می‌شود تا زمان قفل صفحه GPS متوقف نشود.
                wakeLock.acquire();
            } catch (Exception ignored) {}
        }

        try {
            locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            requestLocationUpdatesSafely();
        } catch (Exception ignored) {}

        // نگهبان: اگر GPS بی‌خبر ساکت شد دوباره ثبت می‌شود، صف ارسال خالی می‌شود و سرویس زنده می‌ماند.
        watchdogHandler.removeCallbacks(watchdog);
        watchdogHandler.postDelayed(watchdog, 30000L);
        scheduleRevive(4 * 60 * 1000L);
        netExecutor.execute(this::flushQueue);

        return START_STICKY;
    }

    private boolean tripIsActive() {
        SharedPreferences sp = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        String code = sp.getString("trackCode", "");
        return sp.getBoolean("tripActive", false) && code != null && !code.trim().isEmpty();
    }

    private boolean gpsEnabled() {
        try { return locationManager != null && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER); } catch (Exception e) { return true; }
    }

    private void runWatchdog() {
        if (!tripIsActive()) { cancelRevive(); stopForeground(true); stopSelf(); return; }
        long now = System.currentTimeMillis();
        boolean gpsOn = gpsEnabled();
        if (lastFixMs == 0 || now - lastFixMs > 75000L) requestLocationUpdatesSafely();
        updateNotification(gpsOn
            ? "ارسال خودکار موقعیت فعال است و به محض پایان سفر در انبار خاموش می‌شود."
            : "⚠️ GPS گوشی خاموش است؛ لوکیشن را روشن کنید تا مسیر ثبت شود.");
        scheduleRevive(4 * 60 * 1000L);
        netExecutor.execute(this::flushQueue);
    }

    private void updateNotification(String text) {
        try { startForeground(NOTIF_ID, buildTrackingNotification(text)); } catch (Exception ignored) {}
    }

    // اگر سیستم/سازنده گوشی سرویس را ببندد، آلارم هر چند دقیقه آن را دوباره راه می‌اندازد (در حالت سفر فعال).
    private PendingIntent revivePendingIntent() {
        Intent i = new Intent(this, BootReceiver.class);
        i.setAction(ACTION_REVIVE);
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) f |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(this, 7002, i, f);
    }
    private void scheduleRevive(long delayMs) {
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long at = System.currentTimeMillis() + delayMs;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, revivePendingIntent());
            else am.set(AlarmManager.RTC_WAKEUP, at, revivePendingIntent());
        } catch (Exception ignored) {}
    }
    private void cancelRevive() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(revivePendingIntent());
        } catch (Exception ignored) {}
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // کاربر برنامه را از لیست اخیر پاک کرد؛ اگر سفر فعال است کمی بعد دوباره راه‌اندازی شود.
        try { if (tripIsActive()) scheduleRevive(3000L); } catch (Exception ignored) {}
        super.onTaskRemoved(rootIntent);
    }

    // ───── صف دائمی نقاط: بدون اینترنت نقطه‌ها روی حافظه می‌مانند و بعداً با زمان واقعی خودشان ارسال می‌شوند ─────
    private File queueFile() { return new File(getFilesDir(), QUEUE_FILE); }

    private void enqueuePoint(String line) {
        synchronized (queueLock) {
            try {
                List<String> all = readQueueLocked();
                all.add(line);
                if (all.size() > MAX_QUEUE) all = new ArrayList<>(all.subList(all.size() - MAX_QUEUE, all.size()));
                writeQueueLocked(all);
            } catch (Exception ignored) {}
        }
    }
    private List<String> readQueueLocked() {
        List<String> out = new ArrayList<>();
        File f = queueFile();
        if (!f.exists()) return out;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = br.readLine()) != null) { if (!l.trim().isEmpty()) out.add(l.trim()); }
        } catch (Exception ignored) {}
        return out;
    }
    private void writeQueueLocked(List<String> lines) {
        try (FileWriter fw = new FileWriter(queueFile(), false)) {
            for (String l : lines) { fw.write(l); fw.write("\n"); }
        } catch (Exception ignored) {}
    }

    // فقط روی netExecutor اجرا می‌شود (ترتیب نقاط حفظ می‌شود).
    private void flushQueue() {
        String trackCode = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).getString("trackCode", "");
        if (trackCode == null || trackCode.trim().isEmpty()) return;
        int sent = 0;
        while (sent < 200) {
            String line;
            synchronized (queueLock) {
                List<String> all = readQueueLocked();
                if (all.isEmpty()) return;
                line = all.get(0);
            }
            int result = sendLine(trackCode, line); // 1 = ثبت/رد قطعی، 0 = خطای موقت
            if (result == 0) return;
            synchronized (queueLock) {
                List<String> all = readQueueLocked();
                if (!all.isEmpty() && all.get(0).equals(line)) { all.remove(0); writeQueueLocked(all); }
            }
            sent++;
        }
    }

    private int sendLine(String trackCode, String line) {
        try {
            String[] p = line.split(",");
            if (p.length < 6) return 1; // ردیف خراب
            long tsSec = Long.parseLong(p[0]) / 1000L;
            String q = MainActivity.SERVER_ORIGIN + "/api/driver/track"
                + "?id=" + URLEncoder.encode(trackCode, "UTF-8")
                + "&lat=" + p[1]
                + "&lon=" + p[2]
                + "&timestamp=" + tsSec
                + "&accuracy=" + p[3]
                + "&speed=" + p[4]
                + "&batt=" + p[5];
            HttpURLConnection conn = (HttpURLConnection) new URL(q).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            int code = conn.getResponseCode();
            conn.disconnect();
            if (code >= 200 && code < 300) return 1;
            if (code == 408 || code == 429 || code >= 500) return 0;
            return 1; // 4xx دیگر (مثلاً کد نامعتبر): تکرار فایده ندارد
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private void requestLocationUpdatesSafely() {
        if (locationManager == null || !hasLocationPermission()) return;

        try {
            locationManager.removeUpdates(this);
        } catch (Exception ignored) {}

        boolean fineGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarseGranted = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (fineGranted) {
            try {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10000L, 0f, this, Looper.getMainLooper());
                }
            } catch (Exception ignored) {}
        }
        if (fineGranted || coarseGranted) {
            try {
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 15000L, 0f, this, Looper.getMainLooper());
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void onLocationChanged(@NonNull Location loc) {
        long now = System.currentTimeMillis();
        lastFixMs = now;
        if (now - lastSentMs < 10000L) return;
        lastSentMs = now;

        if (!tripIsActive()) {
            cancelRevive();
            stopSelf();
            return;
        }

        long fixTime = loc.getTime() > 0 ? loc.getTime() : now;
        float speedKmh = loc.hasSpeed() ? (loc.getSpeed() * 3.6f) : 0f;
        String line = fixTime + "," + loc.getLatitude() + "," + loc.getLongitude() + ","
            + Math.round(loc.getAccuracy()) + "," + (speedKmh / 1.852f) + "," + getBatteryPercentage();
        enqueuePoint(line);
        netExecutor.execute(this::flushQueue);
    }

    private int getBatteryPercentage() {
        try {
            IntentFilter iFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = registerReceiver(null, iFilter);
            int level = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
            int scale = batteryStatus != null ? batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1) : -1;
            if (level >= 0 && scale > 0) return Math.round((level * 100f) / scale);
        } catch (Exception ignored) {}
        return 100;
    }

    private Notification buildTrackingNotification() {
        return buildTrackingNotification("ارسال خودکار موقعیت فعال است و به محض پایان سفر در انبار خاموش می‌شود.");
    }

    private Notification buildTrackingNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        int pFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent, pFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🚚 سفیر روژین — مسیر فعال")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "ردیاب سفر فعال سفیر روژین",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override
    public void onDestroy() {
        try { watchdogHandler.removeCallbacks(watchdog); } catch (Exception ignored) {}
        try { if (!tripIsActive()) cancelRevive(); } catch (Exception ignored) {}
        try {
            if (locationManager != null) locationManager.removeUpdates(this);
        } catch (Exception ignored) {}
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
        try {
            netExecutor.shutdownNow();
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(@NonNull String provider) {
        requestLocationUpdatesSafely();
        updateNotification("ارسال خودکار موقعیت فعال است و به محض پایان سفر در انبار خاموش می‌شود.");
    }
    @Override public void onProviderDisabled(@NonNull String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) updateNotification("⚠️ GPS گوشی خاموش است؛ لوکیشن را روشن کنید تا مسیر ثبت شود.");
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
