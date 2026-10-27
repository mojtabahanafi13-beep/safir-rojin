package ir.factoranbar.safirrojin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SafirNotificationService extends Service {
    private static final String CHANNEL_ID = "safir_bg_sync_channel";
    private static final int NOTIF_ID = 7002;
    private ScheduledExecutorService scheduler;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIF_ID, buildSilentNotification());
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(this::pollServerUpdates, 8, 12, TimeUnit.SECONDS);
    }

    private void pollServerUpdates() {
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
                    MainActivity.showSystemAlertNotification(
                        this,
                        "🚀 ماموریت جدید در سفیر روژین",
                        "برنامهٔ مسیر جدید (" + stopsCount + " مقصد) توسط مدیریت برای شما ارسال شد.",
                        "safir-trip-" + tripId + "-" + stopsCount
                    );
                }
            }

            // اگر سفر در سرور به صورت خودکار پایان یافته است، ردیاب پس‌زمینه گوشی را هم خودکار خاموش کن!
            if ("finished".equals(status) && sp.getBoolean("tripActive", false)) {
                sp.edit().putBoolean("tripActive", false).apply();
                stopService(new Intent(this, SafirLocationService.class));
                MainActivity.showSystemAlertNotification(
                    this,
                    "🏁 پایان خودکار سفر در انبار",
                    "مسیر شما به پایان رسید و ردیاب گوشی به‌طور خودکار خاموش شد.",
                    "safir-auto-finish-" + tripId
                );
            }

            // بررسی پیام‌های جدید چت از طرف ادمین حتی در حالت قفل بودن گوشی
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
                                    MainActivity.showSystemAlertNotification(this, title, body, "safir-msg-" + msgId);
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
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(7000);
            if (conn.getResponseCode() != 200) {
                conn.disconnect();
                return null;
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            conn.disconnect();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private Notification buildSilentNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        int pFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent, pFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("سفیر روژین")
            .setContentText("آماده دریافت اعلان ماموریت‌ها و پیام‌های مدیریت")
            .setPriority(NotificationCompat.PRIORITY_MIN)
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
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (scheduler != null) scheduler.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
