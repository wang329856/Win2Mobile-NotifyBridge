# Android 实现与验证记录

## 已实现

独立 Kotlin / Jetpack Compose 模块位于 `src/android`，minSdk 26、targetSdk 35、compileSdk 36；applicationId `com.notifforward.app`，3.0.0 / 30000。配对与接收依赖BridgeTransport接口，LAN factory封装网络选择与绑定；未实现云端传输。中文界面支持扫码或粘贴二维码配对、电脑批准、历史详情、数据库搜索与应用筛选、每批200条加载、电脑移除、接收开关、声音及系统通知/电池设置。

- HTTPS/WSS，Authorization头传凭据；完整证书DER SHA256及证书有效期校验，token使用Android Keystore AES-GCM加密。配对HTTP等待可随协程取消。
- Wi-Fi/Ethernet优先选择，并将OkHttp的socketFactory和DNS绑定到该Network；不要求Wi-Fi拥有互联网或validated状态，蜂窝默认路由不会覆盖已选择的局域网接口。
- 接收服务以单个coordinator串行cancelAndJoin旧连接后启动新连接；网络callback覆盖非default网络。onClosing立即回复关闭握手，1008仅触发重连（电脑正常暂停也使用此code），下一HTTP/WSS握手401/403才进入需授权状态。心跳超时和退避重连使用实际状态。
- Room事务先保存后推进游标；唯一约束冲突只接受字段完全相同的重放，冲突不推进游标。每台电脑独立游标与去重。
- 实时消息保存成功后展示系统通知，再ACK；hello时也补确认当前游标，修复先前ACK失败；ACK失败不能抹去已保存实时消息的提醒。历史和重复重放静默。系统展示失败不阻塞ACK。
- 进程启动清除数据库中上次遗留的已连接状态，服务取消也清除活跃连接状态；回到设置页时更新权限/电池诊断。

## 构建入口

```powershell
./scripts/build_android.ps1 -Configuration debug -JdkHome 'C:/Program Files/Java/jdk-21' -AndroidSdk 'C:/Users/川/AppData/Local/Android/Sdk'
```

脚本可配置 `GradleUserHome`、`GradleInitScript` 和 `OutputDirectory`，默认Gradle用户目录为工作区 `.tools/gradle-home`，默认输出 `dist/android/Win2Mobile-3.0.0-debug.apk`。运行 `testDebugUnitTest lintDebug assembleDebug --no-daemon --console=plain --max-workers=2`，任一失败立即退出，不复制旧APK。release签名尚未配置，传release会明确报错。

## 当前验证状态

已编写26项JVM测试：ProtocolTest 9、BridgeDaoTest 5、EventDeliveryTest 4、StreamFailuresTest 2、LanClientTest 6。覆盖二维码字段与地址、DER与SPKI区别、证书时间、游标连续性、stream握手/重放边界、唯一冲突、重配对保存最新游标和保存/展示/ACK顺序；真实loopback HTTPS/WSS测试批准配对、请求secret/Bearer请求头、401/403、错误DER pin拒绝、取消配对释放HTTP调用、确认游标回退拒绝。BridgeDaoTest使用fake DAO执行真实accept/savePairing算法，但不验证真实SQLite事务隔离或Android Service生命周期；该边界与实机测试分开记录。

2026-09-30 最终脚本退出0，日志为 `.artifacts/android-build.log`：`BUILD SUCCESSFUL in 44s`。最终26项测试全部通过，失败/错误/跳过均0；lint为0错误、21警告。警告主要为版本更新、targetSdk35、KAPT/KTX建议，以及自定义证书信任、备份规则与主题图标建议；没有通过关闭lint或隐藏警告来获得通过结果。

lint首次发现 `clearCapabilities` 需要API30，已改用API21可用的三个 `removeCapability`，保持minSdk26。Kotlin首次编译发现DNS接口不能使用SAM lambda，已改为显式 `Dns.lookup`。这些修复后的完整脚本已复验。

本机Java访问Maven Central间歇TLS失败；少量缺失依赖经官方备用域名repo1.maven.org下载并核对官方SHA1，工作区临时init脚本仅指定这些模块的本地缓存。Gradle8.13发行包已核对官方SHA256；未关闭TLS校验。中文用户目录导致测试worker无法加载，缓存迁至默认工作区路径后26项测试执行成功。本机复验命令：

```powershell
./scripts/build_android.ps1 -Configuration debug -GradleInitScript '.artifacts/android-local-repository.init.gradle' -Offline
```

此init脚本与缓存为本机未跟踪构建环境；正常联网环境默认入口不需要注入。结果路径：

- 测试XML：`src/android/app/build/test-results/testDebugUnitTest`
- lint：`src/android/app/build/reports/lint-results-debug.html` / `.xml`
- Gradle原始APK：`src/android/app/build/outputs/apk/debug/app-debug.apk`
- 分发APK：`dist/android/Win2Mobile-3.0.0-debug.apk`

APK为12,537,743字节，SHA256为 `2D213F841BD8F4A9857C14D7EDCC6CCC89FB580B4A66D858DC30F50E7B0D6B98`。apksigner退出0，v2签名验证通过，证书为Android Debug；aapt2确认applicationId、30000/3.0.0及target35/compile36。它是开发APK，未提供固定release签名。

## 实机待验证

ADB devices检查没有连接设备，尚未安装到实机。需验证：Android 8/13/15权限和前台服务启动、扫码相机、电脑批准/拒绝/撤销、Wi-Fi无外网同时蜂窝开启的真实路由、切网与断线补取、息屏Doze及厂商电池限制、开机恢复、通知声音和点击详情。前台服务不保证Doze豁免；目前也未开展十万条历史性能压测，数据库搜索仍需扫描文本，分页只限制UI当前加载量。
