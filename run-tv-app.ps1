# Stop script on any error
$ErrorActionPreference = "Stop"

try {
    # Set Environment Variables
    $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
    $env:ANDROID_HOME = "C:\Users\albah\AppData\Local\Android\Sdk"
    $adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
    $device = "192.168.68.145:5555"

    # Ensure the PC is connected to the TV
    Write-Host "Connecting to TV..."
    & $adb connect $device

    # Rebuild and Install
    cd D:\Projects\parentapproved\tv-app
    .\gradlew.bat clean assembleDebug

    Write-Host "Installing APK..."
    & $adb -s $device install -r app\build\outputs\apk\debug\app-debug.apk

    # Launch the app
    & $adb -s $device shell monkey -p tv.parentapproved.app -c android.intent.category.LAUNCHER 1

    Write-Host "✅ Done successfully"

    Read-Host "Press Enter to exit"
}
catch {
    Write-Host "❌ Error occurred:"
    Write-Host $_

    # Keep console open
    Read-Host "Press Enter to exit"
}