package ir.factoranbar.safirrojin;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
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
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SafirLocationService extends Service implements LocationListener {
    private static final String CHANNEL_ID = "safir_gps_tracking_channel";
    private static final int NOTIF_ID = 7001;
    private LocationManager locationManager;
    private PowerManager.WakeLock wakeLock;
    private final ExecutorService netExecutor = Executors.newSingleThreadExecutor();
    private long lastSentMs = 0;

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

    @SuppressLint("MissingPermission")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP_TRACKING".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIF_ID, buildTrackingNotification());
        if (wakeLock != null && !wakeLock.isHeld()) {
            try { wakeLock.acquire(10 * 3600 * 1000L); } catch (Exception ignored) {}
        }

        try {
            locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locationManager != null) {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 12000L, 0f, this, Looper.getMainLooper());
                }
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 15000L, 0f, this, Looper.getMainLooper());
                }
            }
        } catch (Exception ignored) {}

        return START_STICKY;
    }

    @Override
    public void onLocationChanged(@NonNull Location loc) {
        long now = System.currentTimeMillis();
        if (now - lastSentMs < 10000L) return;
        lastSentMs = now;

        SharedPreferences sp = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        boolean tripActive = sp.getBoolean("tripActive", false);
        String trackCode = sp.getString("trackCode", "");
        if (!tripActive || trackCode == null || trackCode.isEmpty()) {
            stopSelf();
            return;
        }

        final double lat = loc.getLatitude();
        final double lng = loc.getLongitude();
        final float acc = loc.getAccuracy();
        final float speedKmh = loc.hasSpeed() ? (loc.getSpeed() * 3.6f) : 0f;
        final int batteryPct = getBatteryPercentage();
        final long tsSec = now / 1000L;

        netExecutor.execute(() -> {
            try {
                String q = MainActivity.SERVER_ORIGIN + "/api/driver/track"
                    + "?id=" + URLEncoder.encode(trackCode, "UTF-8")
                    + "&lat=" + lat
                    + "&lon=" + lng
                    + "&timestamp=" + tsSec
                    + "&accuracy=" + Math.round(acc)
                    + "&speed=" + (speedKmh / 1.852f)
                    + "&batt=" + batteryPct;
                HttpURLConnection conn = (HttpURLConnection) new URL(q).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                int code = conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) {}
        });
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
        Intent openIntent = new Intent(this, MainActivity.class);
        int pFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent, pFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🚚 سفیر روژین — مسیر فعال")
            .setContentText("ارسال خودکار موقعیت فعال است و به محض پایان سفر در انبار خاموش می‌شود.")
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
        try {
            if (locationManager != null) locationManager.removeUpdates(this);
        } catch (Exception ignored) {}
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(@NonNull String provider) {}
    @Override public void onProviderDisabled(@NonNull String provider) {}
    @Override public IBinder onBind(Intent intent) { return null; }
}
