# 可莉相机

可莉相机使用 Android Camera2 API，最低支持 Android 11，包名为 `org.kleeui.camera`。从应用内拍摄的照片和视频保存在 `DCIM/KleeCamera`。

## 功能

- 主摄、超广角、微距和前摄 JPEG。主摄可输出 4096 × 3072。
- RAW DNG、专业模式的 ISO、快门、对焦距离和白平衡，以及设备支持的闪光灯和曝光补偿。
- HDR 三帧曝光包围与夜景四帧合成。前摄已产出真机合成图，长边 2304 像素。缺少可对齐纹理时保留参考帧。
- 人像场景使用相机 HAL 的人像参数。系统提供人像扩展时使用该扩展。黑白文档模式增强对比度并校正不均匀照明。
- 在底部选择「录像」，点取景框左上角切换画质与帧率。主摄支持 1080p 和 4K 的 30、60 fps，超广角支持最高 4K 30 fps。30 fps 档使用 H.264，60 fps 档使用 HEVC。授权麦克风后录制 AAC 单声道。延时模式每秒采集约两帧并生成静音视频。
- 主摄录制 4K 60 fps 时，取景画面继续刷新。取景使用录像帧缩小得到的 320 × 180 画面，保存的视频为 3840 × 2160。`actual017` 真机回归的有声样片平均 59.71 fps，取景约 19.82 fps。
- 120 fps 慢动作，主摄 1080p，前摄 720p，两者均以约 30 fps 播放。
- 光轨模式合成 8 帧中的移动亮部。拍摄时需固定手机。拍照和专业模式可配合倒计时连拍 3 张或 5 张。
- 构图网格、定时、点按对焦、双指变焦、音量键快门和相册入口。
- Android 12 及以上跟随系统从壁纸生成的主题色。拍照快门、模式选中项、对焦框和专业参数滑块会使用该配色。录像快门和计时器保持红色。Android 11 使用琥珀色。

## 与原厂规格的差距

| 模式 | `actual017` 的验证结果 |
| --- | --- |
| 50 MP | 重马赛克节点、算法库、IMX766 调校和固件与原厂一致。临时框架补丁保留 8192 × 6144 请求，并把 JPEG 缓冲容量从约 19 MB 提升到 76 MB。真机已保存 8192 × 6144 JPEG。换场景后 CamX 亮度指标仍约为 256，超出重马赛克阈值 230。原生全分辨率尚未验证。 |
| HDR10+ | 采集探针可获取 P010 帧，HEVC 编码器接受 Main10 配置。测试过的录像会话在 CamX 管线或输出流配置阶段失败，尚未产出带 ST 2094-40 动态元数据的录像。 |
| 8K、双景 | 8K HEVC 编码器可启动，厂商摄像会话拒绝配置输出流。前后摄并发及相机扩展列表为空。 |
| 240、480、960、1920 fps 慢动作 | 240 和 480 fps 的试拍出现停帧与会话收尾超时。960 和 1920 fps 未由标准接口公开。交付版提供已验证的 120 fps。 |
| 人像、美颜 | 人像场景已出片。虚化、深度调节、美颜和视频人像仍待实现或验证。 |
| 专业录像、Log、ProFocus | 已有专业静态拍摄和触摸对焦。专业录像、Log、动作和眼部跟焦待实现。 |
| 创意拍摄 | 已有光轨、黑白文档和基础延时。其它长曝光效果、分身、滤镜、全景自拍、Vlog、AI 电影特效和编辑待实现。 |

[小米 12 官方相机规格](https://www.mi.com/global/product/xiaomi-12/specs)

0.5.0 的回归记录位于 `build/actual017-camera-0.5.0-live-qa.json`，MP4 音视频轨和实测帧率位于 `build/actual017-camera-0.5.0-live-tracks.json`。

## 构建和安装

预装版响应 Android 的普通拍照、录像和锁屏拍照入口。第三方应用可通过 Android 的拍摄 Intent 获取缩略图、读取返回的视频 URI，或授权相机将原文件写入指定的 URI。锁屏相机只提供拍照，并在灭屏或解锁后结束。

小米 12 的产品配置会选用 `KleeCamera`。在 Android 源码根目录中，可以单独构建这个模块或随 ROM 一起构建。Soong 从此处的 Java 和资源生成 APK，并使用产品构建的签名。常规增量构建复用已有产物。

```bash
source build/envsetup.sh
lunch cupid-cp2a-userdebug
m KleeCamera
```

在 Windows 上也可使用 PowerShell、JDK 和 Android SDK Platform 36 构建独立调试版。调试证书和 ROM 证书不同时，Android 无法用其中一份 APK 直接覆盖另一份。

```powershell
pwsh -File ./build.ps1
adb install -r ./build/klee-camera.apk
```

手机解锁且 ADB 中只有一部在线设备时，可通过界面执行真机测试。测试会实际拍摄并录音，媒体文件保留在手机相册，测试结束会关闭相机。

```powershell
pwsh -File ./tools/smoke-camera.ps1 -GrantAudio
```

在 PowerShell 中可单独验证扩展模式。

```powershell
& ./tools/smoke-camera.ps1 -GrantAudio -Modes Video4K,Video4K60,Video1080p60,Video4KUltraWide,SlowMotion,SlowMotionFront,HDRFront,NightFront,LightTrails,TimedBurst
```
