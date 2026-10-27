package ir.factoranbar.safirrojin;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;

        // اعلان‌های پس‌زمینه پس از بوت یا به‌روزرسانی دوباره آغاز شوند.
        try {
            Intent notificationService = new Intent(context, SafirNotificationService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(notificationService);
            } else {
                context.startService(notificationService);
            }
        } catch (Exception ignored) {}

        SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("tripActive", false)) return;
        String trackCode = prefs.getString("trackCode", "");
        if (trackCode == null || trackCode.trim().isEmpty()) return;

        boolean hasFine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean hasBackground = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
            || context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;

        // در Android 10+ بازیابی ردیابی از پس‌زمینه به مجوز «همیشه مجاز» نیاز دارد.
        if (!hasBackground || (!hasFine && !hasCoarse)) return;

        try {
            Intent locationService = new Intent(context, SafirLocationService.class);
            locationService.setAction("START_TRACKING");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(locationService);
            } else {
                context.startService(locationService);
            }
        } catch (Exception ignored) {}
    }
}