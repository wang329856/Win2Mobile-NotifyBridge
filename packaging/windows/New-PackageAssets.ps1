[CmdletBinding()]
param([Parameter(Mandatory)][string]$Destination)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
foreach ($asset in @(@('Square150x150Logo.png', 150), @('Square44x44Logo.png', 44), @('StoreLogo.png', 50))) {
    $size = [int]$asset[1]
    $bitmap = [System.Drawing.Bitmap]::new($size, $size)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $brush = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::White)
    $pen = [System.Drawing.Pen]::new([System.Drawing.Color]::White, [Math]::Max(2, $size / 22))
    try {
        $graphics.Clear([System.Drawing.Color]::FromArgb(37, 99, 235))
        $graphics.DrawRectangle($pen, [int]($size * .15), [int]($size * .26), [int]($size * .40), [int]($size * .32))
        $graphics.FillRectangle($brush, [int]($size * .28), [int]($size * .61), [int]($size * .16), [int]($size * .04))
        $graphics.DrawRectangle($pen, [int]($size * .67), [int]($size * .20), [int]($size * .20), [int]($size * .56))
        $bitmap.Save((Join-Path $Destination $asset[0]), [System.Drawing.Imaging.ImageFormat]::Png)
    } finally { $pen.Dispose(); $brush.Dispose(); $graphics.Dispose(); $bitmap.Dispose() }
}
