# Parking Your Car / 停车定位

Android 停车位置记录与 AR 寻车应用，使用 Kotlin + Jetpack Compose 构建。

## Features / 功能

- **停车位记录** -- 一键标记当前位置，记录楼层、车位号
- **实时距离与方向** -- 显示与停车位置的实时距离和八方位方向指引
- **地图展示** -- 高德地图 SDK 或 osmdroid 回退方案，支持长按更新位置
- **AR 寻车** -- 使用摄像头叠加 HUD，实时指引返回车辆的方向和距离
- **拍照记录** -- 拍摄停车位照片并保存到记录中
- **智能主题** -- 根据地理位置日出日落时间自动切换明暗主题
- **计时提醒** -- 设置 30/60/120 分钟提醒，避免超时
- **数据导入导出** -- JSON 格式备份与恢复停车记录
- **历史记录** -- 保留最近 10 条停车记录，支持快速切换
- **分享功能** -- 一键分享停车位置信息到其他应用

## Tech Stack / 技术栈

| Component | Technology |
|-----------|-----------|
| Language | Kotlin |
| UI Framework | Jetpack Compose + Material Design 3 |
| Maps | 高德地图 3D SDK + osmdroid (fallback) |
| Location | Google Play Services Fused Location |
| Camera | CameraX |
| AR Sensors | Android SensorManager (Rotation Vector) |
| Storage | DataStore Preferences |
| Build | Gradle 8.7 + AGP 8.5.2 |

## Requirements / 环境要求

- Android Studio Arctic Fox or newer
- JDK 17
- Android SDK 24+ (minSdk)
- Android SDK 34 (compileSdk/targetSdk)
- Device with GPS for full functionality
- Device with camera + rotation vector sensor for AR mode

## Build / 构建

1. Clone the repository:
   ```bash
   git clone https://github.com/xmgzxmgz/parking_your_car.git
   ```

2. (Optional) Get an API key from [AMap Open Platform](https://lbs.amap.com/) for the native map SDK. Without it, the app falls back to osmdroid with AMap tiles.

3. Create `local.properties` in the project root:
   ```properties
   sdk.dir=/path/to/your/Android/sdk
   AMAP_API_KEY=your_amap_key_here
   ```

4. Build with Gradle:
   ```bash
   ./gradlew assembleDebug
   ```

5. Run unit tests:
   ```bash
   ./gradlew testDebugUnitTest
   ```

6. Build a release APK (requires signing config):
   ```bash
   ./gradlew assembleRelease
   ```

## Project Structure / 项目结构

```
app/src/main/java/com/xiamuguizhi/parking/
  MainActivity.kt          -- Entry point, main UI (Compose)
  ar/ARFindCar.kt          -- AR camera preview + HUD overlay
  data/ParkingDataStore.kt -- Persistent storage (DataStore)
  notify/ReminderReceiver.kt -- Alarm-based notification receiver
  ui/theme/Theme.kt        -- Auto day/night theme (sunrise/sunset)
  util/GeoMath.kt          -- Geo math: distance, bearing, coord transforms
  util/SunriseSunset.kt    -- NOAA sunrise/sunset calculation
```

## Permissions / 权限

| Permission | Purpose |
|-----------|---------|
| `ACCESS_FINE_LOCATION` | GPS positioning for parking location |
| `ACCESS_COARSE_LOCATION` | Network-based fallback location |
| `CAMERA` | AR car-finding mode |
| `INTERNET` | Map tile loading |
| `ACCESS_NETWORK_STATE` | Check connectivity for map tiles |
| `POST_NOTIFICATIONS` | Parking timer reminders (Android 13+) |

## CI/CD

The project includes a GitHub Actions workflow (`.github/workflows/android.yml`) that runs on every push and PR to `main`:
- Builds the debug APK
- Runs unit tests

## License

MIT License. See [LICENSE](LICENSE) for details.
