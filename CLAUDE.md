# Repository development guide

Win2Mobile NotifyBridge forwards Windows Toast notifications to an authorized Android phone over a LAN or an optional end-to-end encrypted ntfy relay, including remote first pairing. The current implementation is C#/.NET 10 WPF on Windows and Kotlin/Compose on Android.

## Build and verify

Run from the repository root in PowerShell:

```powershell
./scripts/bootstrap_dotnet.ps1 # only when a .NET 10 SDK is missing
./scripts/test_backend.ps1
./scripts/test_windows.ps1
./scripts/test_ntfy.ps1
./scripts/build_windows.ps1
./scripts/bootstrap_windows_packaging.ps1 # only when packaging tools are missing
./scripts/package_windows.ps1
./scripts/build_android.ps1
```

Android requires JDK 21 and Android SDK platform 36 / build-tools 36.0.0. Android Studio opens `src/android`. The Gradle verification tasks are `testDebugUnitTest lintDebug assembleDebug`. The three .NET console test runners must be invoked through their scripts; `dotnet test` does not execute them.

## Architecture

- `src/Bridge.Core`: SQLite outbox, persistent server identity, devices, events and acknowledgements.
- `src/Bridge.Transport.Lan`: HTTPS/WSS on port 47721, expiring QR pairing, desktop approval, authentication, replay and live delivery.
- `src/Bridge.Transport.Ntfy`: opt-in anonymous HTTPS/WSS relay, device-isolated AES-GCM fragments, DPAPI-protected publication checkpoints, ephemeral P-256 remote QR authorization and explicit desktop approval. Public service limits/cache are external constraints; a relay acceptance is not a phone ACK. See `docs/protocol-ntfy-v1.md`.
- `src/Bridge.Windows`: WPF window and tray, official UserNotificationListener capture, source filters and opt-in StartupTask. Run in the current user's session. Notification access requires installed MSIX identity and explicit user permission.
- `src/android`: Compose UI, Room history, Keystore-protected credentials and foreground receiving service. Network reconnection, event deduplication and acknowledgement must preserve delivery semantics.
- `tests`: backend integration and desktop capture-state runners.
- `packaging/windows`: full MSIX manifest, assets and installer. Installation chooses the system Appx volume without changing the machine's default volume.

The protocols are specified in `docs/protocol-v1.md` and `docs/protocol-ntfy-v1.md`. User instructions for the current release are in `README.md`, `docs/installation.md` and `docs/relay.md`. Loopback tests do not establish LAN or Doze reliability. Windows establishes a baseline at startup and on resume; notifications from a paused interval are not replayed.

## Local state and signing

Windows data lives under the current user's LocalApplicationData/Win2Mobile (redirected into the package's LocalCache when installed). Server private keys are DPAPI protected; device tokens are stored as hashes on Windows and encrypted through Keystore on Android. Do not log pairing codes or credentials.

`.tools`, `.artifacts`, build outputs, `local.properties`, signing keys and local certificate materials must stay out of Git. Default Windows packaging is unsigned, and the Android APK uses debug signing. Production signing must be configured separately. Do not change certificate trust, firewall rules or runtime permission settings as part of ordinary builds.
