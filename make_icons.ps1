Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = 'Stop'

$res = 'D:\AI_WS\dji-album-android\app\src\main\res'
$srcPath = 'D:\AI_WS\dji-album\build\icon.png'
$src = [System.Drawing.Bitmap]::FromFile($srcPath)

# 原始图里白色 dji 字标的包围盒（实测）
$gx = 66; $gy = 144; $gw = 380; $gh = 224

$densities = @(
    @{ dir = 'mipmap-mdpi';    scale = 1.0 },
    @{ dir = 'mipmap-hdpi';    scale = 1.5 },
    @{ dir = 'mipmap-xhdpi';   scale = 2.0 },
    @{ dir = 'mipmap-xxhdpi';  scale = 3.0 },
    @{ dir = 'mipmap-xxxhdpi'; scale = 4.0 }
)

function New-Canvas([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.Clear([System.Drawing.Color]::Transparent)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    return @($bmp, $g)
}

foreach ($d in $densities) {
    $s = $d.scale
    $dir = Join-Path $res $d.dir
    if (!(Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }

    # 1) 传统方形图标（48dp）：直接用桌面版原图，圆角与深色底完全一致
    $legacy = [int][math]::Round(48 * $s)
    $lb, $lg = New-Canvas $legacy
    $dest = New-Object System.Drawing.Rectangle(0, 0, $legacy, $legacy)
    $lg.DrawImage($src, $dest, 0, 0, $src.Width, $src.Height, [System.Drawing.GraphicsUnit]::Pixel)
    $lg.Dispose()
    $lb.Save((Join-Path $dir 'ic_launcher.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $lb.Dispose()

    # 2) 圆形图标：同样内容做圆形裁切
    $rb, $rg = New-Canvas $legacy
    $clip = New-Object System.Drawing.Drawing2D.GraphicsPath
    $clip.AddEllipse(0, 0, $legacy, $legacy)
    $rg.SetClip($clip)
    $rg.DrawImage($src, $dest, 0, 0, $src.Width, $src.Height, [System.Drawing.GraphicsUnit]::Pixel)
    $rg.Dispose()
    $rb.Save((Join-Path $dir 'ic_launcher_round.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $rb.Dispose()
    $clip.Dispose()

    # 3) 自适应图标前景（108dp 画布，字标宽度取 53.4/108，居中）：背景色由 ic_launcher_background 提供
    $canvas = [int][math]::Round(108 * $s)
    $fb, $fg = New-Canvas $canvas
    $gwPx = [double]$canvas * 0.494
    $ghPx = $gwPx * ($gh / $gw)
    $dx = ($canvas - $gwPx) / 2
    $dy = ($canvas - $ghPx) / 2
    $destRectF = New-Object System.Drawing.RectangleF([single]$dx, [single]$dy, [single]$gwPx, [single]$ghPx)
    $srcRectF = New-Object System.Drawing.RectangleF([single]$gx, [single]$gy, [single]$gw, [single]$gh)
    $fg.DrawImage($src, $destRectF, $srcRectF, [System.Drawing.GraphicsUnit]::Pixel)
    $fg.Dispose()
    $fb.Save((Join-Path $dir 'ic_launcher_foreground.png'), [System.Drawing.Imaging.ImageFormat]::Png)
    $fb.Dispose()

    "ok $($d.dir)"
}

$src.Dispose()
