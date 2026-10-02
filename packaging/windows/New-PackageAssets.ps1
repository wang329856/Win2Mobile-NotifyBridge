[CmdletBinding()]
param([Parameter(Mandatory)][string]$Destination)
$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot '../../src/Bridge.Windows/Assets'
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
foreach ($name in @('Square150x150Logo.png','Square44x44Logo.png','StoreLogo.png')) {
    Copy-Item -LiteralPath (Join-Path $source $name) -Destination (Join-Path $Destination $name)
}