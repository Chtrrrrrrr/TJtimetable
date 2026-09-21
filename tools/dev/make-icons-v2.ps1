# Generates the complete modern adaptive-icon raster layer set.
#
#   .\tools\dev\make-icons-v2.ps1
#
# Why rasterise by hand: Android Studio's Image Asset wizard is not available here, and the
# artwork is only rounded rectangles and plain rectangles, so drawing it with GDI+ is exact
# rather than an approximation of a vector.
#
# Coordinate space is the adaptive icon's 108x108. The safe zone is the central 72x72
# (18..90), so no launcher mask can clip the artwork. Each density bucket is generated at
# 1.0 / 1.5 / 2.0 / 3.0 / 4.0, which makes 108dp 108px at mdpi.
#
# The three layers each have a job, and that is the whole point of a modern adaptive icon:
#
#   background   Full-bleed opaque brand colour. The launcher masks it into any shape, so it
#                must fill the canvas and must NOT carry a shape or transparency of its own --
#                a shaped background shows ragged edges under circle/squircle/teardrop masks.
#   foreground   Only the mark, everything else transparent, content inside the safe zone.
#   monochrome   Android 13+ themed icons (Honor's "colorful / transparent icon" is this).
#                The system keeps ONLY the alpha channel and repaints every opaque pixel with
#                a colour taken from the wallpaper. So it has to be a SOLID silhouette: punch
#                a hole in the middle and the themed icon becomes a hollow frame, which reads
#                as "the app never supported themed icons".
#
# The previous revision punched the four class blocks out as holes, which is exactly that
# failure. Here the blocks are filled as solid bumps instead: the alpha mask is one
# contiguous rounded rectangle with no interior voids.
#
# NOTE: comments in this file are deliberately ASCII only. Under Windows PowerShell 5.1 a
# UTF-8 source file without a BOM is decoded as ANSI, and a mangled multi-byte comment can
# swallow the following newline -- which silently eats the next line of code and produces a
# confusing "Unexpected token" parse error two lines further down. Keep it ASCII.

$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.Drawing

$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$Res = Join-Path $Root 'app\src\main\res'

# --- Artwork, in the 108x108 adaptive-icon coordinate space --------------------
$Canvas = 108.0
$Safe = 72.0
$SafeMin = ($Canvas - $Safe) / 2.0   # 18
$SafeMax = $SafeMin + $Safe          # 90

# The card: vertically and horizontally centred, comfortably inside the safe zone.
$CardX0 = 29.0; $CardY0 = 30.0; $CardX1 = 79.0; $CardY1 = 78.0; $CardRadius = 5.0

# Four class blocks (two columns, two rows). Deliberately unequal in height, the way a real
# timetable has courses of different lengths.
$Blocks = @(
    @(35.0, 38.0, 51.0, 48.0),
    @(56.0, 38.0, 73.0, 58.0),
    @(35.0, 53.0, 51.0, 70.0),
    @(56.0, 63.0, 73.0, 70.0)
)

# GitHub Primer dark canvas plus the accent colour.
$BackgroundColor = [System.Drawing.Color]::FromArgb(255, 0x0D, 0x11, 0x17)
$AccentColor     = [System.Drawing.Color]::FromArgb(255, 0x2F, 0x81, 0xF7)
$CardColor       = [System.Drawing.Color]::White

$Densities = [ordered]@{ 'mdpi' = 1.0; 'hdpi' = 1.5; 'xhdpi' = 2.0; 'xxhdpi' = 3.0; 'xxxhdpi' = 4.0 }

function New-LayerBitmap {
    param([double]$Scale)
    $size = [int][Math]::Round($Canvas * $Scale)
    $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    return @{ Bitmap = $bmp; Graphics = $g; Scale = $Scale }
}

function New-CardPath {
    param([double]$Scale, [double]$X0, [double]$Y0, [double]$X1, [double]$Y1, [double]$R)
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $x0 = $X0 * $Scale; $y0 = $Y0 * $Scale; $x1 = $X1 * $Scale; $y1 = $Y1 * $Scale
    $d = $R * 2 * $Scale
    $path.AddArc($x0, $y0, $d, $d, 180, 90)
    $path.AddArc($x1 - $d, $y0, $d, $d, 270, 90)
    $path.AddArc($x1 - $d, $y1 - $d, $d, $d, 0, 90)
    $path.AddArc($x0, $y1 - $d, $d, $d, 90, 90)
    $path.CloseFigure()
    return $path
}

function Add-Blocks {
    param($Graphics, $Brush, [double]$Scale)
    foreach ($b in $Blocks) {
        $Graphics.FillRectangle(
            $Brush,
            [single]($b[0] * $Scale), [single]($b[1] * $Scale),
            [single](($b[2] - $b[0]) * $Scale), [single](($b[3] - $b[1]) * $Scale))
    }
}

function Save-Png {
    param($Bitmap, [string]$Path)
    $dir = Split-Path -Parent $Path
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $Bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
}

# --- Self-check: the artwork must stay inside the safe zone --------------------
$xs = @($CardX0, $CardX1)
$ys = @($CardY0, $CardY1)
foreach ($b in $Blocks) { $xs += $b[0]; $xs += $b[2]; $ys += $b[1]; $ys += $b[3] }
$minX = ($xs | Measure-Object -Minimum).Minimum
$maxX = ($xs | Measure-Object -Maximum).Maximum
$minY = ($ys | Measure-Object -Minimum).Minimum
$maxY = ($ys | Measure-Object -Maximum).Maximum
if ($minX -lt $SafeMin -or $maxX -gt $SafeMax -or $minY -lt $SafeMin -or $maxY -gt $SafeMax) {
    throw "artwork escapes the safe zone: x $minX..$maxX  y $minY..$maxY  (safe $SafeMin..$SafeMax)"
}
Write-Host "[icons] artwork bbox x $minX..$maxX  y $minY..$maxY  (safe $SafeMin..$SafeMax of $Canvas)"

# --- background: full bleed brand colour ---------------------------------------
foreach ($d in $Densities.Keys) {
    $layer = New-LayerBitmap -Scale $Densities[$d]
    $brush = New-Object System.Drawing.SolidBrush($BackgroundColor)
    $layer.Graphics.FillRectangle($brush, 0, 0, $layer.Bitmap.Width, $layer.Bitmap.Height)
    $brush.Dispose(); $layer.Graphics.Dispose()
    Save-Png -Bitmap $layer.Bitmap -Path (Join-Path $Res "mipmap-$d\ic_launcher_background.png")
    $layer.Bitmap.Dispose()
    Write-Host "[icons] mipmap-$d/ic_launcher_background.png"
}

# --- foreground: white card plus accent blocks, transparent elsewhere ----------
foreach ($d in $Densities.Keys) {
    $layer = New-LayerBitmap -Scale $Densities[$d]
    $cardPath = New-CardPath -Scale $layer.Scale -X0 $CardX0 -Y0 $CardY0 -X1 $CardX1 -Y1 $CardY1 -R $CardRadius
    $cardBrush = New-Object System.Drawing.SolidBrush($CardColor)
    $layer.Graphics.FillPath($cardBrush, $cardPath)
    $blockBrush = New-Object System.Drawing.SolidBrush($AccentColor)
    Add-Blocks -Graphics $layer.Graphics -Brush $blockBrush -Scale $layer.Scale
    $blockBrush.Dispose(); $cardBrush.Dispose(); $cardPath.Dispose(); $layer.Graphics.Dispose()
    Save-Png -Bitmap $layer.Bitmap -Path (Join-Path $Res "mipmap-$d\ic_launcher_foreground.png")
    $layer.Bitmap.Dispose()
    Write-Host "[icons] mipmap-$d/ic_launcher_foreground.png"
}

# --- monochrome: a solid silhouette, never a hole ------------------------------
# Only alpha survives theming: opaque means "repaint me", transparent means "stay gone".
# Holes would hollow out the themed icon. The blocks are therefore painted in the SAME
# colour as the card, so the mask is one contiguous rounded rectangle with no interior void.
foreach ($d in $Densities.Keys) {
    $layer = New-LayerBitmap -Scale $Densities[$d]
    $path = New-CardPath -Scale $layer.Scale -X0 $CardX0 -Y0 $CardY0 -X1 $CardX1 -Y1 $CardY1 -R $CardRadius
    $brush = New-Object System.Drawing.SolidBrush($CardColor)
    $layer.Graphics.FillPath($brush, $path)
    Add-Blocks -Graphics $layer.Graphics -Brush $brush -Scale $layer.Scale
    $brush.Dispose(); $path.Dispose(); $layer.Graphics.Dispose()
    Save-Png -Bitmap $layer.Bitmap -Path (Join-Path $Res "mipmap-$d\ic_launcher_monochrome.png")
    $layer.Bitmap.Dispose()
    Write-Host "[icons] mipmap-$d/ic_launcher_monochrome.png (solid silhouette)"
}

# --- Self-check: no interior holes in the monochrome mask ----------------------
foreach ($d in @('mdpi', 'xxxhdpi')) {
    $verify = Join-Path $Res "mipmap-$d\ic_launcher_monochrome.png"
    $bmp = New-Object System.Drawing.Bitmap($verify)
    $interiorHoles = 0
    for ($y = 0; $y -lt $bmp.Height; $y++) {
        $rowOpaque = @()
        for ($x = 0; $x -lt $bmp.Width; $x++) { $rowOpaque += ($bmp.GetPixel($x, $y).A -gt 0) }
        $first = [Array]::IndexOf($rowOpaque, $true)
        $last = [Array]::LastIndexOf($rowOpaque, $true)
        if ($first -lt 0) { continue }
        for ($x = $first; $x -le $last; $x++) { if (-not $rowOpaque[$x]) { $interiorHoles++ } }
    }
    $bmp.Dispose()
    Write-Host "[icons] mipmap-$d monochrome interior transparent pixels: $interiorHoles"
    if ($interiorHoles -gt 0) { throw "monochrome has interior holes ($interiorHoles px) -- themed icons will look hollow" }
}

Write-Host '[icons] done'
