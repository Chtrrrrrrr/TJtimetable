# Generates the full adaptive-icon raster set from the same geometry as the vector sources.
#
# Android Studio's Image Asset wizard is not available here, so the layers are rasterised
# directly: the artwork is simple rectangles and a rounded card, which means drawing them with
# GDI+ is exact rather than an approximation of the vector.
#
# Run from the repository root:  .\tools\dev\make-icons.ps1

$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.Drawing

$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$Res = Join-Path $Root 'app\src\main\res'

# --- artwork, in the 108x108 adaptive-icon coordinate space -------------------
# Foreground geometry is copied verbatim from drawable/ic_launcher_foreground.xml.
$Canvas = 108.0
$CardX0 = 29.0; $CardY0 = 30.0; $CardX1 = 79.0; $CardY1 = 78.0; $CardRadius = 5.0
$Blocks = @(
    @(35.0, 38.0, 51.0, 48.0),
    @(56.0, 38.0, 73.0, 58.0),
    @(35.0, 53.0, 51.0, 70.0),
    @(56.0, 63.0, 73.0, 70.0)
)

$BackgroundColor = [System.Drawing.Color]::FromArgb(255, 0x0D, 0x11, 0x17)
$BlockColor = [System.Drawing.Color]::FromArgb(255, 0x2F, 0x81, 0xF7)
$CardColor = [System.Drawing.Color]::White

# density bucket -> scale factor (mdpi is 1x, so 108dp becomes 108px)
$Densities = [ordered]@{
    'mdpi'    = 1.0
    'hdpi'    = 1.5
    'xhdpi'   = 2.0
    'xxhdpi'  = 3.0
    'xxxhdpi' = 4.0
}

function New-RoundedCardPath {
    param([double]$S, [System.Drawing.Color]$Color)
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $x0 = $CardX0 * $S; $y0 = $CardY0 * $S
    $x1 = $CardX1 * $S; $y1 = $CardY1 * $S
    $r = $CardRadius * $S
    $d = $r * 2
    $path.AddArc($x0, $y0, $d, $d, 180, 90)
    $path.AddArc($x1 - $d, $y0, $d, $d, 270, 90)
    $path.AddArc($x1 - $d, $y1 - $d, $d, $d, 0, 90)
    $path.AddArc($x0, $y1 - $d, $d, $d, 90, 90)
    $path.CloseFigure()
    return $path
}

function New-LayerBitmap {
    param([double]$S)
    $size = [int][Math]::Round($Canvas * $S)
    $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    return @{ Bitmap = $bmp; Graphics = $g; Scale = $S }
}

function Save-Png {
    param($Bitmap, [string]$Path)
    $dir = Split-Path -Parent $Path
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $Bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
}

# --- background: full bleed, which is what adaptive icons expect ---------------
foreach ($d in $Densities.Keys) {
    $S = $Densities[$d]
    $layer = New-LayerBitmap -S $S
    $brush = New-Object System.Drawing.SolidBrush($BackgroundColor)
    $layer.Graphics.FillRectangle($brush, 0, 0, $layer.Bitmap.Width, $layer.Bitmap.Height)
    $brush.Dispose()
    $layer.Graphics.Dispose()
    Save-Png -Bitmap $layer.Bitmap -Path (Join-Path $Res "mipmap-$d\ic_launcher_background.png")
    $layer.Bitmap.Dispose()
    Write-Host "[icons] mipmap-$d/ic_launcher_background.png"
}

# --- foreground: the card and its class blocks, transparent elsewhere ----------
foreach ($d in $Densities.Keys) {
    $S = $Densities[$d]
    $layer = New-LayerBitmap -S $S
    $cardPath = New-RoundedCardPath -S $S -Color $CardColor
    $cardBrush = New-Object System.Drawing.SolidBrush($CardColor)
    $layer.Graphics.FillPath($cardBrush, $cardPath)
    $blockBrush = New-Object System.Drawing.SolidBrush($BlockColor)
    foreach ($b in $Blocks) {
        $x = $b[0] * $S; $y = $b[1] * $S
        $w = ($b[2] - $b[0]) * $S; $h = ($b[3] - $b[1]) * $S
        $layer.Graphics.FillRectangle($blockBrush, [single]$x, [single]$y, [single]$w, [single]$h)
    }
    $blockBrush.Dispose(); $cardBrush.Dispose(); $cardPath.Dispose()
    $layer.Graphics.Dispose()
    Save-Png -Bitmap $layer.Bitmap -Path (Join-Path $Res "mipmap-$d\ic_launcher_foreground.png")
    $layer.Bitmap.Dispose()
    Write-Host "[icons] mipmap-$d/ic_launcher_foreground.png"
}

# --- monochrome: ONE flat silhouette for themed icons (Android 13+ / 荣耀通透图标) ----------
#
# The system does not use this as a picture. It treats the layer as an alpha mask and repaints
# every opaque pixel in the wallpaper-derived tint. So the layer has to survive being reduced to
# a single colour, and two rules follow from that:
#
#   1. Colour carries no information here. The four class blocks are GitHub accent blue in the
#      foreground, but that blue is discarded, so drawing them white on a white card produced a
#      plain rounded rectangle — a themed icon that reads as a blank blob.
#   2. Only the ALPHA channel is kept, so the blocks have to be holes. The card is drawn white and
#      the block rectangles are then XORed out to transparent, which leaves the block positions
#      as see-through windows in the tinted card. That is what makes the themed icon still look
#      like a timetable instead of a blob.
#
# XOR (rather than drawing transparent) is what punches the hole: filling with a transparent
# brush over existing pixels is a no-op in GDI+.
foreach ($d in $Densities.Keys) {
    $S = $Densities[$d]
    $layer = New-LayerBitmap -S $S
    $cardPath = New-RoundedCardPath -S $S -Color $CardColor
    $brush = New-Object System.Drawing.SolidBrush($CardColor)
    $layer.Graphics.FillPath($brush, $cardPath)

    $hole = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::Black)
    $layer.Graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
    foreach ($b in $Blocks) {
        $x = [single]($b[0] * $S); $y = [single]($b[1] * $S)
        $w = [single](($b[2] - $b[0]) * $S); $h = [single](($b[3] - $b[1]) * $S)
        $layer.Graphics.FillRectangle([System.Drawing.Brushes]::Transparent, $x, $y, $w, $h)
    }
    $layer.Graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceOver
    $hole.Dispose(); $brush.Dispose(); $cardPath.Dispose()
    $layer.Graphics.Dispose()
    Save-Png -Bitmap $layer.Bitmap -Path (Join-Path $Res "mipmap-$d\ic_launcher_monochrome.png")
    $layer.Bitmap.Dispose()
    Write-Host "[icons] mipmap-$d/ic_launcher_monochrome.png (silhouette with block holes)"
}

Write-Host '[icons] done'
