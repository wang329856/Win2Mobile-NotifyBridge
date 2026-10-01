# Android 任务

请先读 docs/protocol-v1.md。你的编辑范围仅 src/android、scripts/build_android.ps1、docs/android-task-report.md；不改旧 Flutter、Windows、后端、根 README/.gitignore/global.json。不提交或推送。所有实现必须真实可编译，不留空壳/伪连接。

实现独立 Kotlin + Jetpack Compose App（min26 target35 compile36），applicationId com.notifforward.app，版本3.0.0 code30000。已有 Gradle wrapper jar可从旧Flutter的android/gradle/wrapper复制。建议 AGP8.13.1、Kotlin2.2.20、Gradle8.13（请核对兼容性，可采用同等稳定兼容版本）。本机 JAVA_HOME=C:/Program Files/Java/jdk-21，但旧AndroidSdk可能为 junction/本机可用。你负责发现 SDK、构建环境；重要命令因 sandbox网络或路径阻塞按平台要求重跑 require_escalated。不要静默降级构建。

实现扫码（ZXing嵌入扫描组件等）、JSON解析校验、严格 DER SHA256证书指纹和有效期检查、只使用HTTPS/WSS、请求设备配对/轮询电脑批准，token Android Keystore AES-GCM加密。配对码/请求secret/token不日志输出。普通界面提供二维码扫描和粘贴配对信息的恢复入口，不让用户填topic。

Room保存通知（eventId唯一）、电脑配置/游标；同一事务先保存事件再推进游标，成功后ack，重放不增加重复记录或成批响铃。系统通知稳定ID。WebSocket接收使用协议hello/events/heartbeat，OkHttp auth头，不把token放URL；网络恢复退避、明确连接/等待网络/需授权状态。前台服务connectedDevice与必要声明/CHANGE_NETWORK_STATE，持续状态通知、NetworkCallback、BOOT_COMPLETED正常恢复、stop/撤销设备/权限失败恢复入口。前台服务不保证Doze豁免，界面提供电池后台诊断/系统设置入口，不用无限wakelock或精确闹钟规避限制。

Compose 中文界面包含通知历史、展开正文、搜索/应用筛选、电脑设备及删除、设置（声音/清理历史/后台权限），首次运行按需申请POST_NOTIFICATIONS、相机权限；不每次自动自检弹窗。风格清晰现代、暗色适配。设备凭据以serverId隔离，游标和去重也按serverId隔离。网络状态不假标connected，服务和UI共享实际持久状态。

实现协议解析/指纹/游标相关有意义JVM测试，执行 testDebugUnitTest、assembleDebug和lint；build_android.ps1使用本机SDK/JDK配置并输出dist APK。禁止提交local.properties/keystore/凭据。写report准确包含命令、结果、APK路径和实机待验证项。任何API改变先消息根线程，不自行改protocol。
