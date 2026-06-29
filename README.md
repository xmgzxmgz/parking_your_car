# 🚗 停车游戏 (Parking Your Car)

Android 停车模拟游戏，使用 Jetpack Compose + AR 技术。

## 功能
- 3D 停车场景模拟
- AR 增强现实视角
- 多种停车难度关卡
- 高德地图集成

## 技术栈
- Kotlin + Jetpack Compose
- ARCore (增强现实)
- 高德地图 SDK
- Material Design 3

## 构建
1. 在 [高德开放平台](https://lbs.amap.com/) 申请 API Key
2. 在 `local.properties` 中配置：
   ```
   sdk.dir=/path/to/Android/sdk
   AMAP_API_KEY=your_key_here
   ```
3. 用 Android Studio 打开并构建

## 环境要求
- Android Studio Arctic Fox+
- Android SDK 24+
- ARCore 支持的设备
