# One-off diagnostic: where is the Minecraft client area on the desktop, and is the point we click
# actually inside it? The probe derives OS points from the logged guiScale, so a wrong window rect
# would silently mis-aim every hover/click.
Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class W {
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out R r);
  [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref P p);
  [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out R r);
  [StructLayout(LayoutKind.Sequential)] public struct R { public int l, t, r, b; }
  [StructLayout(LayoutKind.Sequential)] public struct P { public int x, y; }
}
"@
$p = Get-Process | Where-Object { $_.MainWindowTitle -like '*Minecraft*' } | Select-Object -First 1
if (-not $p) { Write-Output 'no minecraft window'; exit }
$r = New-Object W+R
$null = [W]::GetWindowRect($p.MainWindowHandle, [ref]$r)
$c = New-Object W+R
$null = [W]::GetClientRect($p.MainWindowHandle, [ref]$c)
$o = New-Object W+P
$o.x = 0; $o.y = 0
$null = [W]::ClientToScreen($p.MainWindowHandle, [ref]$o)
Write-Output ("window  = " + $r.l + "," + $r.t + " .. " + $r.r + "," + $r.b + "  size=" + ($r.r - $r.l) + "x" + ($r.b - $r.t))
Write-Output ("client  = " + $c.r + "x" + $c.b)
Write-Output ("origin  = " + $o.x + "," + $o.y)
Write-Output ("clientEnd = " + ($o.x + $c.r) + "," + ($o.y + $c.b))
foreach ($scr in [System.Windows.Forms.Screen]::AllScreens) {
    Write-Output ("screen  = " + $scr.Bounds)
}
