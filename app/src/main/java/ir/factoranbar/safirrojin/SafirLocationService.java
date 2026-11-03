package ir.factoranbar.safirrojin;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
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
    public static final String ACTION_REVIVE = "ir.factoranbar.safirrojin.REVIVE";
    private static final String CHANNEL_ID = "safir_gps_tracking_channel";
    private static final int NOTIF_ID = 7001;
    private static final String QUEUE_FILE = "safir_pos_queue.txt";
    private static final int MAX_QUEUE = 3000;

    private LocationManager locationManager;
    private PowerManager.WakeLock wakeLock;
    private final ExecutorService netExecutor = Executors.newSingleThreadExecutor();
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
    private final Object queueLock = new Object();
    private long lastFixMs = 0;
    private long lastSentMs = 0;
    private long lastRegisterMs = 0;
    private boolean isRegistered = false;
    private Location lastKnownFix = null;

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            runWatchdog();
            watchdogHandler.postDelayed(this, 15000);
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

    private void startForegroundWithLocationType(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIF_ID, notification);
            }
        } catch (Throwable t) {
            try {
                startForeground(NOTIF_ID, notification);
            } catch (Throwable ignored) {}
        }
    }

    @SuppressLint("WakelockTimeout")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if ("STOP_TRACKING".equals(action)) {
            cancelRevive();
            isRegistered = false;
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        String code = prefs.getString("trackCode", "");
        boolean active = prefs.getBoolean("tripActive", false);
        if (!active || code == null || code.trim().isEmpty()) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundWithLocationType(buildTrackingNotification());
        if (!hasLocationPermission()) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (wakeLock != null && !wakeLock.isHeld()) {
            try { wakeLock.acquire(); } catch (Exception ignored) {}
        }
        if (locationManager == null) {
            locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        }
        long now = System.currentTimeMillis();
        // جلوگیری از ریست مکرر LocationManager در گوشی‌های سامسونگ اگر قبلاً ثبت شده است
        if (!isRegistered || (now - lastRegisterMs > 180000)) {
            requestLocationUpdatesSafely();
        }
        watchdogHandler.removeCallbacks(watchdog);
        watchdogHandler.postDelayed(watchdog, 12000);
        scheduleRevive(60000);
        netExecutor.execute(this::flushQueue);
        return START_STICKY;
    }

    @SuppressLint("MissingPermission")
    private void requestLocationUpdatesSafely() {
        if (locationManager == null || !hasLocationPermission()) return;
        try { locationManager.removeUpdates(this); } catch (Exception ignored) {}
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (fine) {
            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 4000L, 0f, this, Looper.getMainLooper());
            } catch (Exception ignored) {}
            try {
                locationManager.requestLocationUpdates("fused", 4000L, 0f, this, Looper.getMainLooper());
            } catch (Exception ignored) {}
            try {
                locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 2000L, 0f, this, Looper.getMainLooper());
            } catch (Exception ignored) {}
        }
        if (fine || coarse) {
            try {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, this, Looper.getMainLooper());
            } catch (Exception ignored) {}
        }
        isRegistered = true;
        lastRegisterMs = System.currentTimeMillis();
    }

    @SuppressLint("MissingPermission")
    private Location pollBestLastKnownLocation() {
        if (locationManager == null || !hasLocationPermission()) return lastKnownFix;
        Location best = lastKnownFix;
        String[] providers = new String[]{"gps", "fused", "network", "passive"};
        for (String p : providers) {
            try {
                Location cand = locationManager.getLastKnownLocation(p);
                if (cand != null) {
                    if (best == null || cand.getTime() > best.getTime() + 10000
                            || (Math.abs(cand.getTime() - best.getTime()) <= 15000 && cand.getAccuracy() < best.getAccuracy())) {
                        best = cand;
                    }
                }
            } catch (Exception ignored) {}
        }
        if (best != null) lastKnownFix = best;
        return best;
    }

    private void runWatchdog() {
        if (!tripIsActive()) {
            cancelRevive();
            isRegistered = false;
            stopForeground(true);
            stopSelf();
            return;
        }
        if (wakeLock != null && !wakeLock.isHeld()) {
            try { wakeLock.acquire(); } catch (Exception ignored) {}
        }
        long now = System.currentTimeMillis();
        boolean gpsOn = gpsEnabled();
        if (!isRegistered || (now - lastFixMs > 180000 && now - lastRegisterMs > 180000)) {
            requestLocationUpdatesSafely();
        }
        // ارسال خودکار موقعیت حتی در زمان توقف خودرو یا قفل بودن صفحه در سامسونگ
        if (now - lastSentMs >= 12000) {
            Location hbLoc = pollBestLastKnownLocation();
            if (hbLoc != null) {
                recordAndSendLocation(hbLoc, now);
            }
        }
        updateNotification(gpsOn
                ? "ارسال خودکار موقعیت فعال است و به محض پایان سفر در انبار خاموش می‌شود."
                : "⚠️ GPS گوشی خاموش است؛ لوکیشن را روشن کنید تا مسیر ثبت شود.");
        scheduleRevive(60000);
        netExecutor.execute(this::flushQueue);
    }

    private void recordAndSendLocation(Location loc, long now) {
        if (loc == null) return;
        lastKnownFix = loc;
        lastFixMs = now;
        lastSentMs = now;
        float speedKmh = loc.hasSpeed() ? (loc.getSpeed() * 3.6f) : 0f;
        float speedKnots = speedKmh / 1.852f;
        int acc = Math.round(loc.getAccuracy());
        int batt = getBatteryPercentage();
        String line = now + "," + loc.getLatitude() + "," + loc.getLongitude() + "," + acc + "," + speedKnots + "," + batt;
        enqueuePoint(line);
        netExecutor.execute(this::flushQueue);
    }

    @Override
    public void onLocationChanged(Location loc) {
        if (loc == null) return;
        long now = System.currentTimeMillis();
        lastKnownFix = loc;
        lastFixMs = now;
        if (now - lastSentMs < 8000) return;
        if (!tripIsActive()) {
            cancelRevive();
            stopSelf();
            return;
        }
        recordAndSendLocation(loc, now);
    }

    @Override
    public void onProviderDisabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            updateNotification("⚠️ GPS گوشی خاموش است؛ لوکیشن را روشن کنید تا مسیر ثبت شود.");
        }
    }

    @Override
    public void onProviderEnabled(String provider) {
        requestLocationUpdatesSafely();
        updateNotification("ارسال خودکار موقعیت فعال است و به محض پایان سفر در انبار خاموش می‌شود.");
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (tripIsActive()) {
            scheduleRevive(2000);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        watchdogHandler.removeCallbacks(watchdog);
        if (!tripIsActive()) {
            cancelRevive();
        } else {
            scheduleRevive(3000);
        }
        if (locationManager != null) {
            try { locationManager.removeUpdates(this); } catch (Exception ignored) {}
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        netExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean tripIsActive() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        String code = prefs.getString("trackCode", "");
        boolean active = prefs.getBoolean("tripActive", false);
        return active && code != null && !code.trim().isEmpty();
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean gpsEnabled() {
        try {
            return locationManager != null && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    private int getBatteryPercentage() {
        try {
            Intent batteryStatus = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) {
                    return Math.round((level * 100f) / scale);
                }
            }
        } catch (Exception ignored) {}
        return 100;
    }

    private File queueFile() {
        return new File(getFilesDir(), QUEUE_FILE);
    }

    private List<String> readQueueLocked() {
        List<String> list = new ArrayList<>();
        File f = queueFile();
        if (!f.exists()) return list;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) list.add(line.trim());
            }
        } catch (Exception ignored) {}
        return list;
    }

    private void writeQueueLocked(List<String> list) {
        try (FileWriter fw = new FileWriter(queueFile(), false)) {
            for (String s : list) {
                fw.write(s);
                fw.write("\n");
            }
        } catch (Exception ignored) {}
    }

    private void enqueuePoint(String line) {
        synchronized (queueLock) {
            List<String> q = readQueueLocked();
            q.add(line);
            if (q.size() > MAX_QUEUE) {
                q = new ArrayList<>(q.subList(q.size() - MAX_QUEUE, q.size()));
            }
            writeQueueLocked(q);
        }
    }

    private void flushQueue() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        String code = prefs.getString("trackCode", "");
        if (code == null || code.trim().isEmpty()) return;
        code = code.trim();
        for (int i = 0; i < 200; i++) {
            String line;
            synchronized (queueLock) {
                List<String> q = readQueueLocked();
                if (q.isEmpty()) return;
                line = q.get(0);
            }
            if (!sendLine(code, line)) return;
            synchronized (queueLock) {
                List<String> q = readQueueLocked();
                if (!q.isEmpty() && line.equals(q.get(0))) {
                    q.remove(0);
                    writeQueueLocked(q);
                }
            }
        }
    }

    private boolean sendLine(String code, String line) {
        try {
            String[] p = line.split(",");
            if (p.length < 6) return true;
            long tsSec = Long.parseLong(p[0]) / 1000L;
            String urlStr = MainActivity.SERVER_ORIGIN + "/api/driver/track?id=" + URLEncoder.encode(code, "UTF-8")
                    + "&lat=" + p[1]
                    + "&lon=" + p[2]
                    + "&timestamp=" + tsSec
                    + "&accuracy=" + p[3]
                    + "&speed=" + p[4]
                    + "&batt=" + p[5];
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            int resp = conn.getResponseCode();
            conn.disconnect();
            return (resp >= 200 && resp < 300) || (resp != 408 && resp != 429 && resp < 500);
        } catch (Exception e) {
            return false;
        }
    }

    private PendingIntent revivePendingIntent() {
        Intent i = new Intent(this, BootReceiver.class);
        i.setAction(ACTION_REVIVE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(this, 7002, i, flags);
    }

    private void scheduleRevive(long delayMs) {
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long at = System.currentTimeMillis() + delayMs;
            PendingIntent pi = revivePendingIntent();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                } catch (Throwable t) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                }
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Exception ignored) {}
    }

    private void cancelRevive() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(revivePendingIntent());
        } catch (Exception ignored) {}
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "ردیاب سفر فعال سفیر روژین", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
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

    private void updateNotification(String text) {
        try {
            startForegroundWithLocationType(buildTrackingNotification(text));
        } catch (Exception ignored) {}
    }
}