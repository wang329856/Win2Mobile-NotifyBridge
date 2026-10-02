# Windows 源码打包

普通用户请直接使用 [首次安装教程](../../docs/installation.md)。本页用于维护当前版本的 MSIX 构建与签名。

## 构建与签名

需要 .NET 10 SDK，以及 Windows SDK 中的 MakeAppx / SignTool。发布包自带 .NET 运行时。

```powershell
./scripts/build_windows.ps1
./scripts/bootstrap_windows_packaging.ps1 # 缺少打包工具时运行
./scripts/package_windows.ps1             # 默认生成未签名开发包
# 使用与 Publisher 匹配的代码签名证书：
./scripts/package_windows.ps1 -Publisher 'CN=Win2Mobile' -SigningCertificateThumbprint '<40 位证书指纹>'
```

签名证书应位于当前用户 My 存储，含私钥、代码签名用途，Subject 与 Publisher 一致。脚本检查打包、签名与信任结果，不创建或导入证书。

正式 MSIX 需要受目标机器信任的签名。通知采集要求 MSIX 应用身份及用户授权，直接运行 EXE 不能完成真实采集。

## 安装与运行

```powershell
./packaging/windows/Install-Win2Mobile.ps1 -PackagePath './packaging/windows/output/Win2Mobile-3.1.2.0-x64.msix'
```

安装脚本检查签名并选择系统应用卷，不修改默认应用卷、证书信任或防火墙。安装前从托盘退出运行中的程序；安装后从开始菜单启动并授权系统通知访问。

电脑程序运行在当前用户会话中，不安装 Windows 服务。登录启动使用 MSIX StartupTask，默认关闭，并遵守系统策略。

## 可信时间戳与证书到期

签名脚本默认使用 DigiCert RFC 3161 端点 `http://timestamp.digicert.com`，可通过 `-TimestampServer` 指定其他服务。采用 SHA-256，验证签名与时间戳，失败则停止。

有效期内签名并带可信时间戳的包，可以在签名证书到期后继续通过安装验证；自签名证书仍须由目标机器信任。时间戳不能用于过期证书的新签名，也不能绕过证书吊销、篡改或系统策略。新版本须使用有效证书并保持包身份与 Publisher 一致。

依据：[微软 MSIX 签名](https://learn.microsoft.com/en-us/windows/msix/package/signing-package-overview)、[DigiCert 时间戳](https://knowledge.digicert.com/solution/troubleshooting-timestamping-problems)。不要提交私钥、签名密码或本地凭据。
