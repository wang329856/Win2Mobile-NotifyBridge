param([ValidatePattern('^\d+\.\d+$')][string]$Channel = '10.0')
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$toolDirectory = Join-Path $workspace '.tools/dotnet'
$toolExecutable = Join-Path $toolDirectory 'dotnet.exe'
if (Test-Path -LiteralPath $toolExecutable) {
    $availableSdks = @(& $toolExecutable --list-sdks)
    if ($LASTEXITCODE -eq 0 -and ($availableSdks | Where-Object { $_ -match ('^' + [regex]::Escape($Channel) + '\.\d+\s') })) {
        Write-Output "工作区已存在 .NET $Channel SDK。"
        exit 0
    }
}
New-Item -ItemType Directory -Path $toolDirectory -Force | Out-Null
$metadataUrl = "https://dotnetcli.blob.core.windows.net/dotnet/release-metadata/$Channel/releases.json"
$metadataPath = Join-Path $workspace ".tools/dotnet-release-metadata-$Channel.json"
& curl.exe --fail --silent --show-error --location --retry 2 --connect-timeout 15 --max-time 60 --output $metadataPath $metadataUrl
if ($LASTEXITCODE -ne 0) { throw 'Could not fetch official SDK release metadata.' }
$metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
$sdkVersion = $metadata.'latest-sdk'
$selectedSdk = $null
foreach ($release in $metadata.releases) {
    foreach ($sdk in @($release.sdks) + @($release.sdk)) {
        if ($sdk.version -eq $sdkVersion) { $selectedSdk = $sdk; break }
    }
    if ($selectedSdk) { break }
}
if (!$selectedSdk) { throw "SDK metadata does not include $sdkVersion" }
$archive = $selectedSdk.files | Where-Object { $_.rid -eq 'win-x64' -and $_.name.EndsWith('.zip') } | Select-Object -First 1
if (!$archive) { throw 'Official metadata has no Windows x64 SDK archive.' }
$archivePath = Join-Path $workspace ".tools/dotnet-sdk-$sdkVersion-win-x64.zip"
$archiveUri = [Uri]$archive.url
$downloadUrl = "https://dotnetcli.blob.core.windows.net$($archiveUri.AbsolutePath)"
$existingHash = if (Test-Path -LiteralPath $archivePath) { (Get-FileHash -LiteralPath $archivePath -Algorithm SHA512).Hash } else { '' }
if ($existingHash -ne $archive.hash) {
    Write-Host "Downloading/resuming official .NET SDK $sdkVersion into workspace"
    & curl.exe --fail --show-error --location --retry 3 --retry-delay 2 --connect-timeout 15 --continue-at - --output $archivePath $downloadUrl
    if ($LASTEXITCODE -eq 33) {
        Write-Host 'Download endpoint did not honor byte ranges; downloading the archive afresh.'
        & curl.exe --fail --show-error --location --retry 3 --retry-delay 2 --connect-timeout 15 --output $archivePath $downloadUrl
    }
    if ($LASTEXITCODE -ne 0) { throw 'SDK archive download failed; rerun to resume.' }
}
$actualHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA512).Hash
if ($actualHash -ne $archive.hash) { throw 'SDK archive SHA512 verification failed.' }
Expand-Archive -LiteralPath $archivePath -DestinationPath $toolDirectory -Force
$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
$env:DOTNET_GENERATE_ASPNET_CERTIFICATE = 'false'
$env:DOTNET_CLI_HOME = Join-Path $workspace '.tools/dotnet-home'
& $toolExecutable --version
if ($LASTEXITCODE -ne 0) { throw 'Installed SDK did not start.' }
$availableSdks = @(& $toolExecutable --list-sdks)
if ($LASTEXITCODE -ne 0 -or -not ($availableSdks | Where-Object { $_ -match ('^' + [regex]::Escape($Channel) + '\.\d+\s') })) { throw "Installed SDK does not match requested channel $Channel." }
