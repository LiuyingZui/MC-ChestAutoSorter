# Crop a screenshot region so the Read tool shows it at 1:1 instead of downsampling a 1600x1000 frame.
# Coordinates are CLIENT/canvas pixels times guiScale, i.e. the same space the probe aims in.
param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Parameter(Mandatory = $true)][int]$X,
    [Parameter(Mandatory = $true)][int]$Y,
    [Parameter(Mandatory = $true)][int]$W,
    [Parameter(Mandatory = $true)][int]$H,
    [double]$Scale = 1,
    [int]$Zoom = 1,
    [string]$Out = ''
)
Add-Type -AssemblyName System.Drawing
$src = (Resolve-Path $Path).Path
$bmp = [System.Drawing.Bitmap]::FromFile($src)
try {
    $x = [int]($X * $Scale); $y = [int]($Y * $Scale); $w = [int]($W * $Scale); $h = [int]($H * $Scale)
    if ($x -lt 0) { $x = 0 }
    if ($y -lt 0) { $y = 0 }
    if ($x + $w -gt $bmp.Width) { $w = $bmp.Width - $x }
    if ($y + $h -gt $bmp.Height) { $h = $bmp.Height - $y }
    $rect = New-Object System.Drawing.Rectangle($x, $y, $w, $h)
    $crop = $bmp.Clone($rect, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    try {
        if ($Zoom -gt 1) {
            # Nearest-neighbour keeps every game pixel visible, which is what a layout check needs;
            # a filtered upscale would hide 1px misalignments behind smoothing.
            $bw = [int]($crop.Width * $Zoom)
            $bh = [int]($crop.Height * $Zoom)
            $big = New-Object System.Drawing.Bitmap($bw, $bh)
            $g = [System.Drawing.Graphics]::FromImage($big)
            $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
            $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
            $g.DrawImage($crop, 0, 0, $big.Width, $big.Height)
            $g.Dispose()
            $crop.Dispose()
            $crop = $big
        }
        if (-not $Out) { $Out = (Join-Path (Split-Path $src) ((Split-Path -Leaf $src) -replace '\.png$', '')) + "_${X}_${Y}_${W}x${H}.png" }
        $crop.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
        Write-Output ("crop " + $rect + " -> " + $Out)
    }
    finally { $crop.Dispose() }
}
finally { $bmp.Dispose() }
