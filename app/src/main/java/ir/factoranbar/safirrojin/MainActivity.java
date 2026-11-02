package ir.factoranbar.safirrojin;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    public static final String PORTAL_URL = "https://server.factor-anbar.online/driver/index.html";
    public static final String SERVER_ORIGIN = "https://server.factor-anbar.online";
    public static final String PREFS_NAME = "safir_rojin_prefs";
    public static final String ALERT_CHANNEL_ID = "safir_rojin_alerts_v1";

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraOutputUri;
    private File cameraOutputFile;
    private static final int FILE_CHOOSER_REQ = 1001;
    private static final int PERMISSIONS_REQ = 1002;
    private static final int BG_LOC_PERMISSION_REQ = 1003;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createAlertNotificationChannel();
        requestEssentialPermissions();

        webView = new WebView(this);
        setContentView(webView);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setGeolocationEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.addJavascriptInterface(new SafirBridge(this), "SafirRojinAndroid");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String url = uri.toString();
                if (url.startsWith("tel:") || url.startsWith("sms:") || url.contains("nshn.ir") || url.contains("maps.google") || url.contains("google.com/maps")) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                        startActivity(intent);
                    } catch (Exception ignored) {}
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = callback;

                boolean imageRequest = acceptsImages(fileChooserParams != null ? fileChooserParams.getAcceptTypes() : null);
                Intent pickerIntent;
                try {
                    pickerIntent = fileChooserParams != null
                        ? fileChooserParams.createIntent()
                        : new Intent(Intent.ACTION_GET_CONTENT);
                } catch (Exception ignored) {
                    pickerIntent = new Intent(Intent.ACTION_GET_CONTENT);
                }
                pickerIntent.addCategory(Intent.CATEGORY_OPENABLE);
                if (imageRequest && (pickerIntent.getType() == null || pickerIntent.getType().isEmpty())) {
                    pickerIntent.setType("image/*");
                }

                Intent cameraIntent = imageRequest ? createCameraCaptureIntent() : null;
                try {
                    if (cameraIntent != null && fileChooserParams != null && fileChooserParams.isCaptureEnabled()) {
                        try {
                            startActivityForResult(cameraIntent, FILE_CHOOSER_REQ);
                            return true;
                        } catch (Exception cameraUnavailable) {
                            // Fall through to the picker if no camera app can handle capture.
                        }
                    }

                    Intent chooserIntent = Intent.createChooser(
                        pickerIntent,
                        imageRequest ? "گرفتن عکس با دوربین یا انتخاب از گالری" : "انتخاب فایل"
                    );
                    if (cameraIntent != null) {
                        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});
                    }
                    startActivityForResult(chooserIntent, FILE_CHOOSER_REQ);
                    return true;
                } catch (Exception e) {
                    if (MainActivity.this.filePathCallback != null) {
                        MainActivity.this.filePathCallback.onReceiveValue(null);
                        MainActivity.this.filePathCallback = null;
                    }
                    discardPendingCameraPhoto();
                    return false;
                }
            }
        });

        webView.loadUrl(PORTAL_URL);
        startNotificationWatcherService();
    }

    private void startNotificationWatcherService() {
        try {
            Intent svc = new Intent(this, SafirNotificationService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Exception ignored) {}
    }

    private void requestEssentialPermissions() {
        List<String> needed = new ArrayList<>();
        String[] perms = new String[]{
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        };
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMISSIONS_REQ);
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                }
            }
        } catch (Exception ignored) {}
    }

    private boolean acceptsImages(String[] acceptTypes) {
        if (acceptTypes == null || acceptTypes.length == 0) return true;
        boolean hasType = false;
        for (String acceptType : acceptTypes) {
            if (acceptType == null || acceptType.trim().isEmpty()) continue;
            hasType = true;
            String type = acceptType.toLowerCase(java.util.Locale.ROOT);
            if (type.contains("image/") || type.contains("*/*")) return true;
        }
        return !hasType;
    }

    private Intent createCameraCaptureIntent() {
        discardPendingCameraPhoto();
        try {
            File picturesDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (picturesDir == null || (!picturesDir.exists() && !picturesDir.mkdirs())) {
                picturesDir = new File(getCacheDir(), "camera");
            }
            if (!picturesDir.exists() && !picturesDir.mkdirs()) return null;

            cameraOutputFile = File.createTempFile("safir-chat-", ".jpg", picturesDir);
            cameraOutputUri = FileProvider.getUriForFile(
                this, getPackageName() + ".fileprovider", cameraOutputFile
            );

            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraOutputUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.setClipData(ClipData.newUri(getContentResolver(), "SafirRojinPhoto", cameraOutputUri));
            return intent;
        } catch (Exception e) {
            discardPendingCameraPhoto();
            return null;
        }
    }

    private void discardPendingCameraPhoto() {
        try {
            if (cameraOutputFile != null && cameraOutputFile.exists() && cameraOutputFile.length() == 0L) {
                cameraOutputFile.delete();
            }
        } catch (Exception ignored) {}
        cameraOutputUri = null;
        cameraOutputFile = null;
    }

    // بعد از شروع ردیابی: اول مجوز «همیشه»، اگر لازم نبود راهنمای تنظیم باتری/شروع خودکار گوشی.
    // ───── به‌روزرسانی داخل برنامه: دانلود APK از سرور خودمان و باز کردن پنجرهٔ نصب (راننده فقط «نصب» را می‌زند) ─────
    public void startUpdateDownload(final String url) {
        try {
            if (url == null || !url.startsWith(SERVER_ORIGIN + "/")) {
                Toast.makeText(this, "آدرس به‌روزرسانی معتبر نیست.", Toast.LENGTH_LONG).show();
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
                new AlertDialog.Builder(this)
                    .setTitle("اجازهٔ نصب نسخهٔ جدید")
                    .setMessage("برای به‌روزرسانی، در صفحهٔ بعد گزینهٔ «اجازه دادن از این منبع» را روشن کنید و برگردید؛ سپس دوباره دکمهٔ به‌روزرسانی را بزنید.")
                    .setPositiveButton("باز کردن تنظیمات", (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                        } catch (Exception ignored) {}
                    })
                    .setNegativeButton("بعداً", null)
                    .show();
                return;
            }
            Toast.makeText(this, "در حال دانلود نسخهٔ جدید… چند لحظه صبر کنید", Toast.LENGTH_LONG).show();
            new Thread(() -> {
                File out = null;
                try {
                    File dir = getExternalFilesDir("updates");
                    if (dir == null) dir = new File(getCacheDir(), "updates");
                    if (!dir.exists()) dir.mkdirs();
                    out = new File(dir, "Safir-Rojin.apk");
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(60000);
                    if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                    try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(out, false)) {
                        byte[] buf = new byte[32768];
                        int n;
                        while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                    }
                    c.disconnect();
                    if (out.length() < 100000L) throw new Exception("small file");
                    final File apk = out;
                    runOnUiThread(() -> {
                        try {
                            Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", apk);
                            Intent i = new Intent(Intent.ACTION_VIEW);
                            i.setDataAndType(uri, "application/vnd.android.package-archive");
                            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "باز کردن نصب‌کننده ناموفق بود.", Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "دانلود نسخهٔ جدید ناموفق بود؛ اینترنت را بررسی و دوباره تلاش کنید.", Toast.LENGTH_LONG).show());
                }
            }).start();
        } catch (Exception ignored) {}
    }

    private void onTrackingStartedUi() {
        if (!requestBackgroundLocationPermissionIfNeeded()) showOemKeepAliveGuideIfNeeded();
    }

    private void showOemKeepAliveGuideIfNeeded() {
        try {
            SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            if (sp.getBoolean("oem_guide_done", false)) return;
            long now = System.currentTimeMillis();
            if (now - sp.getLong("oem_guide_prompt_at", 0L) < 3L * 24L * 60L * 60L * 1000L) return;
            String man = (Build.MANUFACTURER == null ? "" : Build.MANUFACTURER).toLowerCase(java.util.Locale.ROOT);
            final List<ComponentName> targets = new ArrayList<>();
            String tip;
            if (man.contains("xiaomi") || man.contains("redmi") || man.contains("poco")) {
                targets.add(new ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"));
                tip = "«شروع خودکار (Autostart)» را برای سفیر روژین روشن کنید و در بخش باتری گزینهٔ «بدون محدودیت» را انتخاب کنید. سپس برنامه را در لیست برنامه‌های اخیر قفل کنید.";
            } else if (man.contains("huawei") || man.contains("honor")) {
                targets.add(new ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"));
                targets.add(new ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"));
                tip = "برای سفیر روژین «مدیریت دستی» را بزنید و هر سه گزینه (راه‌اندازی خودکار، راه‌اندازی ثانویه، اجرا در پس‌زمینه) را روشن کنید.";
            } else if (man.contains("oppo") || man.contains("realme") || man.contains("oneplus")) {
                targets.add(new ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"));
                targets.add(new ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"));
                targets.add(new ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"));
                tip = "«شروع خودکار / Auto-launch» و «فعالیت در پس‌زمینه» را برای سفیر روژین روشن کنید.";
            } else if (man.contains("vivo") || man.contains("iqoo")) {
                targets.add(new ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"));
                targets.add(new ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"));
                tip = "سفیر روژین را در «شروع خودکار» و «مصرف باتری در پس‌زمینه» مجاز کنید.";
            } else if (man.contains("samsung")) {
                targets.add(new ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"));
                tip = "از مسیر باتری ← «محدودیت‌های استفاده در پس‌زمینه» ← «برنامه‌های هرگز خواب‌نرو»، سفیر روژین را اضافه کنید.";
            } else {
                return;
            }
            sp.edit().putLong("oem_guide_prompt_at", now).apply();
            new AlertDialog.Builder(this)
                .setTitle("برای قطع نشدن ردیابی")
                .setMessage("در این مدل گوشی، سیستم ممکن است برنامه را هنگام خاموش بودن صفحه ببندد و مسیر شما ثبت نشود.\n\n" + tip)
                .setPositiveButton("باز کردن تنظیمات", (d, w) -> {
                    boolean opened = false;
                    for (ComponentName cn : targets) {
                        try {
                            Intent i = new Intent();
                            i.setComponent(cn);
                            startActivity(i);
                            opened = true;
                            break;
                        } catch (Exception ignored) {}
                    }
                    if (!opened) {
                        try {
                            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                        } catch (Exception ignored) {}
                    }
                })
                .setNeutralButton("انجام دادم", (d, w) -> sp.edit().putBoolean("oem_guide_done", true).apply())
                .setNegativeButton("بعداً", null)
                .show();
        } catch (Exception ignored) {}
    }

    private boolean requestBackgroundLocationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED) return false;

        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        long lastPrompt = preferences.getLong("background_location_prompt_at", 0L);
        long now = System.currentTimeMillis();
        if (now - lastPrompt < 24L * 60L * 60L * 1000L) return false;
        preferences.edit().putLong("background_location_prompt_at", now).apply();

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                BG_LOC_PERMISSION_REQ
            );
            return true;
        }

        new AlertDialog.Builder(this)
            .setTitle("اجازهٔ دسترسی دائمی به مکان")
            .setMessage("برای اینکه ردیابی مسیر پس از قفل‌شدن گوشی و پس از راه‌اندازی مجدد بهتر ادامه پیدا کند، در تنظیمات مجوز مکان را روی «همیشه مجاز» قرار دهید. ردیابی فقط هنگام فعال بودن سفر انجام می‌شود.")
            .setPositiveButton("باز کردن تنظیمات", (dialog, which) -> {
                try {
                    Intent settingsIntent = new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())
                    );
                    startActivity(settingsIntent);
                } catch (Exception ignored) {}
            })
            .setNegativeButton("بعداً", (dialog, which) -> {})
            .show();
        return true;
    }

    private void createAlertNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                ALERT_CHANNEL_ID,
                "اعلان‌های ماموریت و پیام سفیر روژین",
                NotificationManager.IMPORTANCE_HIGH
            );
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 350, 150, 350, 150, 500});
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    public static void showSystemAlertNotification(Context ctx, String title, String body, String tag) {
        try {
            Intent intent = new Intent(ctx, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, intent, flags);

            String cleanTitle = (title != null && !title.isEmpty()) ? title : "🚚 سفیر روژین | اعلان جدید";
            if (!cleanTitle.contains("سفیر روژین")) {
                cleanTitle = "🚚 سفیر روژین | " + cleanTitle;
            }

            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, ALERT_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setLargeIcon(BitmapFactory.decodeResource(ctx.getResources(), R.mipmap.ic_launcher))
                .setContentTitle(cleanTitle)
                .setContentText(body != null ? body : "")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body != null ? body : ""))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                .setVibrate(new long[]{0, 350, 150, 350, 150, 500})
                .setContentIntent(pi);

            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                int id = (tag != null ? tag.hashCode() : (int) System.currentTimeMillis());
                nm.notify(id, b.build());
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQ) return;

        if (filePathCallback == null) {
            discardPendingCameraPhoto();
            return;
        }

        Uri selectedUri = null;
        Uri[] results = null;
        if (resultCode == Activity.RESULT_OK) {
            // دوربین معمولاً URI را در data برنمی‌گرداند؛ فایل خروجی را بررسی می‌کنیم.
            if (cameraOutputFile != null && cameraOutputUri != null
                    && cameraOutputFile.exists() && cameraOutputFile.length() > 0L) {
                selectedUri = cameraOutputUri;
            } else if (data != null && data.getData() != null) {
                selectedUri = data.getData();
            } else if (data != null && data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                results = new Uri[count];
                for (int i = 0; i < count; i++) {
                    results[i] = data.getClipData().getItemAt(i).getUri();
                }
            }
        }

        if (selectedUri != null) {
            results = new Uri[]{selectedUri};
        }

        boolean usedCameraFile = selectedUri != null && selectedUri.equals(cameraOutputUri);
        if (!usedCameraFile) {
            discardPendingCameraPhoto();
        } else {
            cameraOutputUri = null;
            cameraOutputFile = null;
        }

        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }
    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            moveTaskToBack(true);
        }
    }

    public class SafirBridge {
        private final Context context;

        SafirBridge(Context ctx) {
            this.context = ctx;
        }

        @JavascriptInterface
        public void syncCredentials(String token, String driverName, String trackCode, String tripId, boolean tripActive) {
            SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            sp.edit()
                .putString("token", token != null ? token : "")
                .putString("driverName", driverName != null ? driverName : "")
                .putString("trackCode", trackCode != null ? trackCode : "")
                .putString("tripId", tripId != null ? tripId : "")
                .putBoolean("tripActive", tripActive)
                .apply();

            if (tripActive && trackCode != null && !trackCode.isEmpty()) {
                startTracking(trackCode, tripId);
            } else if (!tripActive) {
                stopTracking();
            }
        }

        @JavascriptInterface
        public void startTracking(String trackCode, String tripId) {
            SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            if (trackCode != null && !trackCode.isEmpty()) {
                sp.edit().putString("trackCode", trackCode).putString("tripId", tripId != null ? tripId : "").putBoolean("tripActive", true).apply();
            }
            if (context instanceof MainActivity) {
                MainActivity activity = (MainActivity) context;
                activity.runOnUiThread(activity::onTrackingStartedUi);
            }
            Intent svc = new Intent(context, SafirLocationService.class);
            svc.setAction("START_TRACKING");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        }

        @JavascriptInterface
        public void stopTracking() {
            SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            sp.edit().putBoolean("tripActive", false).apply();
            Intent svc = new Intent(context, SafirLocationService.class);
            context.stopService(svc);
        }

        @JavascriptInterface
        public int getVersionCode() {
            try {
                android.content.pm.PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                return (int) (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? pi.getLongVersionCode() : pi.versionCode);
            } catch (Exception e) { return 1; }
        }

        @JavascriptInterface
        public void installUpdate(final String url) {
            if (!(context instanceof MainActivity)) return;
            final MainActivity a = (MainActivity) context;
            a.runOnUiThread(() -> a.startUpdateDownload(url));
        }

        @JavascriptInterface
        public void showNativeNotification(String title, String body, String tag) {
            showSystemAlertNotification(context, title, body, tag);
        }
    }
}
