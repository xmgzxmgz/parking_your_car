# ── ProGuard / R8 Rules for parking_your_car ──

# Keep data model classes used for serialization
-keep class com.xiamuguizhi.parking.data.ParkingDataStore$* { *; }

# Keep BroadcastReceiver for alarm reminders
-keep class com.xiamuguizhi.parking.notify.ReminderReceiver { *; }

# ── osmdroid ──
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# ── 高德地图 SDK ──
-keep class com.amap.api.** { *; }
-keep class com.autonavi.** { *; }
-dontwarn com.amap.api.**
-dontwarn com.autonavi.**

# ── Google Play Services Location ──
-keep class com.google.android.gms.location.** { *; }
-dontwarn com.google.android.gms.**

# ── CameraX ──
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ── Coil ──
-keep class coil.** { *; }
-dontwarn coil.**

# ── Kotlin Coroutines ──
-dontwarn kotlinx.coroutines.**