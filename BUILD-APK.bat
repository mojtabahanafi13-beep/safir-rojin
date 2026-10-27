@echo off
chcp 65001 >nul
title ساخت خودکار فایل نصبی اندروید «سفیر روژین» (Safir-Rojin.apk)
echo ================================================================
echo   در حال آماده‌سازی و ساخت اپلیکیشن اندروید «سفیر روژین»
echo   آدرس متصل به سرور: https://server.factor-anbar.online/driver/index.html
echo ================================================================
echo.

where gradle >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    echo [1/2] در حال کامپایل فایل نصبی Safir-Rojin.apk ...
    call gradle assembleDebug
    if exist "app\build\outputs\apk\debug\app-debug.apk" (
        copy /Y "app\build\outputs\apk\debug\app-debug.apk" "Safir-Rojin.apk" >nul
        echo.
        echo ✅ تبریک! فایل نصبی «Safir-Rojin.apk» با موفقیت در همین پوشه ساخته شد!
        pause
        exit /b 0
    )
)

echo برای ساخت مستقیم روی ویندوز، پوشه را در Android Studio باز کنید و از منوی
echo Build ^> Build Bundle(s) / APK(s) ^> Build APK(s)
echo را بزنید تا فایل Safir-Rojin.apk تحویل داده شود.
echo (یا طبق فایل راهنما، در ۲ دقیقه به صورت آنلاین و رایگان در GitHub بسازید).
echo.
pause
