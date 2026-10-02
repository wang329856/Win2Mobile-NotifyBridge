[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$destination = Join-Path $PSScriptRoot '../../src/Bridge.Windows/Assets'
New-Item -ItemType Directory -Force -Path $destination | Out-Null
function RoundedPath([single]$x, [single]$y, [single]$w, [single]$h, [single]$r) {
    $p = [Drawing.Drawing2D.GraphicsPath]::new()
    $d = 2 * $r
    $p.AddArc($x,$y,$d,$d,180,90)
    $p.AddArc(($x+$w-$d),$y,$d,$d,270,90)
    $p.AddArc(($x+$w-$d),($y+$h-$d),$d,$d,0,90)
    $p.AddArc($x,($y+$h-$d),$d,$d,90,90)
    $p.CloseFigure()
    return ,$p
}
function RenderIcon([int]$size) {
    # Render every size from the same vector geometry at 4x for crisp small icons.
    $bitmap = [Drawing.Bitmap]::new(($size*4),($size*4))
    $g = [Drawing.Graphics]::FromImage($bitmap)
    $g.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.ScaleTransform(($size*4/128.0),($size*4/128.0))
    $bg = RoundedPath 2 2 124 124 30
    $gradient = [Drawing.Drawing2D.LinearGradientBrush]::new([Drawing.Rectangle]::new(0,0,128,128),[Drawing.ColorTranslator]::FromHtml('#7870EF'),[Drawing.ColorTranslator]::FromHtml('#5143C4'),45.0)
    $white = [Drawing.SolidBrush]::new([Drawing.Color]::White)
    $teal = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml('#75E4D0'))
    $pen = [Drawing.Pen]::new([Drawing.Color]::White,5)
    $pen.StartCap = $pen.EndCap = [Drawing.Drawing2D.LineCap]::Round
    $screen = RoundedPath 20 35 57 40 6
    $phone = RoundedPath 78 44 30 57 7
    try {
        $g.Clear([Drawing.Color]::Transparent)
        $g.FillPath($gradient,$bg)
        $g.DrawPath($pen,$screen)
        $g.DrawLine($pen,48,76,48,87)
        $g.DrawLine($pen,35,88,60,88)
        # Solid phone silhouette keeps the two device forms distinct at 16px.
        $g.FillPath($white,$phone)
        $g.FillRectangle($gradient,83,51,20,37)
        $g.FillEllipse($gradient,90,93,6,3)
        # A notification travelling from the monitor to the phone.
        $g.FillEllipse($teal,65,23,20,20)
        $g.DrawLine($pen,71,32,77,32)
    } finally {
        $phone.Dispose(); $screen.Dispose(); $bg.Dispose(); $gradient.Dispose()
        $white.Dispose(); $teal.Dispose(); $pen.Dispose(); $g.Dispose()
    }
    $result = [Drawing.Bitmap]::new($size,$size)
    $downsample = [Drawing.Graphics]::FromImage($result)
    $downsample.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $downsample.DrawImage($bitmap,0,0,$size,$size)
    $downsample.Dispose(); $bitmap.Dispose()
    return ,$result
}
$sizes = @(16,24,32,48,64,128,256)
$images = foreach($size in $sizes) {
    $bitmap = RenderIcon $size
    $stream = [IO.MemoryStream]::new()
    try { $bitmap.Save($stream,[Drawing.Imaging.ImageFormat]::Png); ,$stream.ToArray() }
    finally { $stream.Dispose(); $bitmap.Dispose() }
}
$file = [IO.File]::Create((Join-Path $destination 'Win2Mobile.ico'))
$writer = [IO.BinaryWriter]::new($file)
try {
    $writer.Write([uint16]0); $writer.Write([uint16]1); $writer.Write([uint16]$sizes.Count)
    $offset = 6 + 16*$sizes.Count
    for($i=0;$i -lt $sizes.Count;$i++) {
        $dimension = if($sizes[$i] -eq 256){0}else{$sizes[$i]}
        $writer.Write([byte]$dimension); $writer.Write([byte]$dimension)
        $writer.Write([byte]0); $writer.Write([byte]0)
        $writer.Write([uint16]1); $writer.Write([uint16]32)
        $writer.Write([uint32]$images[$i].Length); $writer.Write([uint32]$offset)
        $offset += $images[$i].Length
    }
    foreach($bytes in $images) { $writer.Write([byte[]]$bytes) }
} finally { $writer.Dispose() }
foreach($asset in @(@('BrandLogo.png',256),@('Square150x150Logo.png',150),@('Square44x44Logo.png',44),@('StoreLogo.png',50))) {
    $bitmap = RenderIcon $asset[1]
    try { $bitmap.Save((Join-Path $destination $asset[0]),[Drawing.Imaging.ImageFormat]::Png) }
    finally { $bitmap.Dispose() }
}
