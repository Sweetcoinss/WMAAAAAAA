Add-Type -AssemblyName System.Drawing

# Les libelles sont lus dans strings.xml : la previsualisation utilise donc
# exactement les textes de l'application (arabe par defaut).
$xmlPath = 'C:\Users\RDP\swat4-android\android\app\src\main\res\values\strings.xml'
$xml = [xml][System.IO.File]::ReadAllText($xmlPath, [System.Text.Encoding]::UTF8)
$S = @{}
foreach ($s in $xml.SelectNodes('//string')) { $S[$s.GetAttribute('name')] = [string]$s.InnerText }
$T = @{}
foreach ($s in $xml.SelectNodes('//string-array')) { $T[$s.GetAttribute('name')] = @($s.SelectNodes('item') | ForEach-Object { [string]$_.InnerText }) }

$W = 1080
$H = 1920
$out = 'C:\Users\RDP\Desktop\SWAT4-interface-preview.png'

$bmp = [System.Drawing.Bitmap]::new($W, $H)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAlias

$ORANGE = [System.Drawing.Color]::FromArgb(255, 255, 122, 26)
$ORANGE_D = [System.Drawing.Color]::FromArgb(255, 255, 106, 18)
$WHITE = [System.Drawing.Color]::FromArgb(255, 255, 255, 255)
$TXT = [System.Drawing.Color]::FromArgb(255, 230, 237, 245)

$top = [System.Drawing.Color]::FromArgb(255, 22, 30, 41)
$bot = [System.Drawing.Color]::FromArgb(255, 5, 7, 11)
for ($y = 0; $y -lt $H; $y += 2) {
    $t = $y / [double]$H
    $col = [System.Drawing.Color]::FromArgb(255,
        [int](22 + (5 - 22) * $t), [int](30 + (7 - 30) * $t), [int](41 + (11 - 41) * $t))
    $g.FillRectangle([System.Drawing.SolidBrush]::new($col), 0, $y, $W, 2)
}

$hp = [System.Drawing.Drawing2D.GraphicsPath]::new()
$hp.AddEllipse([System.Drawing.Rectangle]::new(620, -260, 900, 900))
$halo = [System.Drawing.Drawing2D.PathGradientBrush]::new($hp)
$halo.CenterPoint = [System.Drawing.Point]::new(1070, 190)
$halo.CenterColor = [System.Drawing.Color]::FromArgb(40, 255, 122, 26)
$halo.SurroundColors = [System.Drawing.Color[]]@([System.Drawing.Color]::FromArgb(0, 255, 122, 26))
$g.FillEllipse($halo, 620, -260, 900, 900)

$sfCenter = [System.Drawing.StringFormat]::new()
$sfCenter.Alignment = [System.Drawing.StringAlignment]::Center
$sfCenter.LineAlignment = [System.Drawing.StringAlignment]::Center

function New-Font([double]$size, [System.Drawing.FontStyle]$style) {
    return [System.Drawing.Font]::new('Tahoma', $size, $style, [System.Drawing.GraphicsUnit]::Pixel)
}

function New-RoundPath([int]$x, [int]$y, [int]$w, [int]$h, [int]$r) {
    $p = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $p.AddArc($x, $y, $r, $r, 180, 90)
    $p.AddArc(($x + $w - $r), $y, $r, $r, 270, 90)
    $p.AddArc(($x + $w - $r), ($y + $h - $r), $r, $r, 0, 90)
    $p.AddArc($x, ($y + $h - $r), $r, $r, 90, 90)
    $p.CloseFigure()
    return $p
}

# --- embleme ---
$egp = [System.Drawing.Drawing2D.GraphicsPath]::new()
$egp.AddEllipse([System.Drawing.Rectangle]::new(390, 40, 300, 300))
$eg = [System.Drawing.Drawing2D.PathGradientBrush]::new($egp)
$eg.CenterPoint = [System.Drawing.Point]::new(540, 190)
$eg.CenterColor = [System.Drawing.Color]::FromArgb(55, 255, 122, 26)
$eg.SurroundColors = [System.Drawing.Color[]]@([System.Drawing.Color]::FromArgb(0, 255, 122, 26))
$g.FillEllipse($eg, 390, 40, 300, 300)

$pts = [System.Drawing.Point[]]@(
    [System.Drawing.Point]::new(540, 72), [System.Drawing.Point]::new(648, 108),
    [System.Drawing.Point]::new(648, 192), [System.Drawing.Point]::new(648, 232),
    [System.Drawing.Point]::new(600, 278), [System.Drawing.Point]::new(540, 300),
    [System.Drawing.Point]::new(480, 278), [System.Drawing.Point]::new(432, 232),
    [System.Drawing.Point]::new(432, 192), [System.Drawing.Point]::new(432, 108))
$shield = [System.Drawing.Drawing2D.GraphicsPath]::new()
$shield.AddPolygon($pts)
$g.FillPath([System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(255, 27, 36, 48)), $shield)
$g.DrawPath([System.Drawing.Pen]::new($ORANGE, 3), $shield)

$cross = [System.Drawing.Pen]::new($ORANGE, 5)
$g.DrawLine($cross, 470, 70, 470, 96)
$g.DrawLine($cross, 610, 70, 610, 96)
$g.DrawLine($cross, 470, 284, 470, 308)
$g.DrawLine($cross, 610, 284, 610, 308)
$brushOrange = [System.Drawing.SolidBrush]::new($ORANGE)
$g.FillPolygon($brushOrange, [System.Drawing.Point[]]@(
    [System.Drawing.Point]::new(540, 58), [System.Drawing.Point]::new(550, 80),
    [System.Drawing.Point]::new(530, 80)))
$g.FillPolygon($brushOrange, [System.Drawing.Point[]]@(
    [System.Drawing.Point]::new(540, 322), [System.Drawing.Point]::new(550, 300),
    [System.Drawing.Point]::new(530, 300)))
$g.FillPolygon($brushOrange, [System.Drawing.Point[]]@(
    [System.Drawing.Point]::new(412, 190), [System.Drawing.Point]::new(434, 200),
    [System.Drawing.Point]::new(434, 180)))
$g.FillPolygon($brushOrange, [System.Drawing.Point[]]@(
    [System.Drawing.Point]::new(668, 190), [System.Drawing.Point]::new(646, 200),
    [System.Drawing.Point]::new(646, 180)))

$brushWhite = [System.Drawing.SolidBrush]::new($WHITE)
$g.FillRectangle($brushWhite, 508, 118, 22, 96)
$g.FillRectangle($brushWhite, 474, 172, 90, 18)
$g.FillRectangle($brushWhite, 484, 190, 20, 34)

# --- titre ---
$brushT = [System.Drawing.SolidBrush]::new($WHITE)
$brushS = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(160, 200, 210, 220))
$brushTxt = [System.Drawing.SolidBrush]::new($TXT)
$brushMuted = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(170, 174, 187, 201))

$g.DrawString($S['app_name'], (New-Font 56 ([System.Drawing.FontStyle]::Bold)), $brushT,
    [System.Drawing.RectangleF]::new(0, 330, $W, 70), $sfCenter)
$g.DrawString($S['app_subtitle'], (New-Font 24 ([System.Drawing.FontStyle]::Regular)), $brushS,
    [System.Drawing.RectangleF]::new(0, 404, $W, 34), $sfCenter)

# --- carte ---
$cardPath = New-RoundPath 40 470 ($W - 80) 760 36
$g.FillPath([System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(20, 255, 255, 255)), $cardPath)
$g.DrawPath([System.Drawing.Pen]::new([System.Drawing.Color]::FromArgb(30, 255, 255, 255), 1), $cardPath)

$g.DrawString($S['status_runtime_missing'], (New-Font 29 ([System.Drawing.FontStyle]::Regular)), $brushTxt,
    [System.Drawing.RectangleF]::new(70, 505, ($W - 140), 140), $sfCenter)

# bouton principal
$btnPath = New-RoundPath 70 660 ($W - 140) 108 28
$g.FillPath([System.Drawing.SolidBrush]::new($ORANGE), $btnPath)
$g.SetClip($btnPath)
for ($y = 660; $y -lt 768; $y++) {
    $t = ($y - 660) / 108.0
    $a = [int](60 * $t * $t)
    $g.FillRectangle([System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb($a, 120, 45, 0)), 70, $y, ($W - 140), 1)
}
$g.ResetClip()
$g.DrawString($S['btn_download_runtime'], (New-Font 34 ([System.Drawing.FontStyle]::Bold)), $brushT,
    [System.Drawing.RectangleF]::new(70, 660, ($W - 140), 108), $sfCenter)

$sizeText = $S['runtime_size'] -replace '%1\$d', '191'
$g.DrawString($sizeText, (New-Font 22 ([System.Drawing.FontStyle]::Regular)), $brushS,
    [System.Drawing.RectangleF]::new(0, 782, $W, 32), $sfCenter)

# bouton secondaire
$secPath = New-RoundPath 70 840 ($W - 140) 108 24
$g.FillPath([System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(13, 255, 255, 255)), $secPath)
$g.DrawPath([System.Drawing.Pen]::new([System.Drawing.Color]::FromArgb(26, 255, 255, 255), 1), $secPath)
$g.DrawString($S['btn_pick'], (New-Font 30 ([System.Drawing.FontStyle]::Regular)), $brushTxt,
    [System.Drawing.RectangleF]::new(70, 840, ($W - 140), 108), $sfCenter)

# reglages
$driverLabel = 'Automatique'
if ($T.ContainsKey('graphics_drivers') -and $T['graphics_drivers'].Count -gt 0) { $driverLabel = $T['graphics_drivers'][0] }
$sum = $S['graphics_summary'] -replace '%1\$dp', '720' -replace '%2\$s', $driverLabel
$g.DrawString($sum, (New-Font 22 ([System.Drawing.FontStyle]::Regular)), $brushMuted,
    [System.Drawing.RectangleF]::new(70, 1320, ($W - 140), 44), $sfCenter)
$g.DrawString($S['btn_controller'], (New-Font 22 ([System.Drawing.FontStyle]::Regular)), $brushMuted,
    [System.Drawing.RectangleF]::new(70, 1380, ($W - 140), 44), $sfCenter)

$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose()
$bmp.Dispose()
Write-Output "previsualisation : $out"
