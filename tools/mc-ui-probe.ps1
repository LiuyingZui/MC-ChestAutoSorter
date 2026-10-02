<#
  Black-box input driver for the Minecraft client window.

  Everything here goes through the real Win32 input path (SetCursorPos / mouse_event / keybd_event),
  so the game sees what a player's hand produces. That is the only way to answer questions a log
  cannot: does a tooltip actually appear under a real hover, does the panel really sit above a JEI
  recipe page, does the layout hold at another window size or GUI scale.

  GUI coordinates come from the game's own diagnostics in run/logs/latest.log:
    ui.button spot=... bounds=X,Y WxH ... screen=WxH   -> the inventory opener
    ui.geom   screen=WxH ... rects[close=X,Y WxH ...]  -> panel controls
  The virtual canvas is scaled to the window by guiScale, so OS pixel = client origin + canvas * scale,
  with scale derived from clientWidth / canvasWidth rather than trusted from the log.

  Usage:
    powershell -NoProfile -File tools/mc-ui-probe.ps1 focus
    powershell -NoProfile -File tools/mc-ui-probe.ps1 resize 1280 800
    powershell -NoProfile -File tools/mc-ui-probe.ps1 info
    powershell -NoProfile -File tools/mc-ui-probe.ps1 hover  open
    powershell -NoProfile -File tools/mc-ui-probe.ps1 click  close
    powershell -NoProfile -File tools/mc-ui-probe.ps1 key    f2
    powershell -NoProfile -File tools/mc-ui-probe.ps1 run    "/setblock ~ ~-1 ~ chest"
    powershell -NoProfile -File tools/mc-ui-probe.ps1 wheeldn 200 120 3
    powershell -NoProfile -File tools/mc-ui-probe.ps1 wheelup 200 120 3
    powershell -NoProfile -File tools/mc-ui-probe.ps1 clickpoint 214 120
#>
param(
    [Parameter(Position = 0)][string]$Verb = 'info',
    [Parameter(Position = 1)][string]$A,
    [Parameter(Position = 2)][string]$B,
    [Parameter(Position = 3)][string]$C = '1',
    [string]$Log = '',
    [string]$TitlePattern = '*Minecraft*'
)

# $PSScriptRoot is not reliably set inside a param default, so resolve it here.
if (-not $Log) {
    $here = Split-Path -Parent ($MyInvocation.MyCommand.Path)
    $Log = Join-Path (Join-Path $here '..') 'run\logs\latest.log'
}

Add-Type -AssemblyName System.Windows.Forms

$sources = @'
using System;
using System.Runtime.InteropServices;
public static class Win {
    public delegate bool EnumProc(IntPtr h, IntPtr l);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool attach);
    [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint dx, uint dy, int data, IntPtr extra);
    [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, IntPtr extra);
    [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out Rect r);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out Rect r);
    [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref Point p);
    [DllImport("user32.dll")] public static extern bool MoveWindow(IntPtr h, int x, int y, int w, int h2, bool repaint);
    [StructLayout(LayoutKind.Sequential)] public struct Rect { public int left, top, right, bottom; }
    [StructLayout(LayoutKind.Sequential)] public struct Point { public int x, y; }
    public static IntPtr Find(uint[] wanted) {
        IntPtr hit = IntPtr.Zero;
        EnumWindows((h, l) => {
            uint pid; GetWindowThreadProcessId(h, out pid);
            if (hit == IntPtr.Zero && Array.Exists(wanted, t => t == pid) && IsWindowVisible(h)) hit = h;
            return true;
        }, IntPtr.Zero);
        return hit;
    }
}
'@
if (-not ('Win' -as [type])) { Add-Type -TypeDefinition $sources }

$MOUSEEVENTF_MOVE = 0x0001
$MOUSEEVENTF_LEFTDOWN = 0x0002
$MOUSEEVENTF_LEFTUP = 0x0004
$MOUSEEVENTF_WHEEL = 0x0800
$KEYEVENTF_KEYUP = 0x0002

function Get-McWindow {
    $procs = @(Get-Process java, javaw -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowTitle -like $TitlePattern })
    if ($procs.Count -eq 0) { throw "no window matching $TitlePattern" }
    $hwnd = [Win]::Find([uint32[]]@($procs.Id))
    if ($hwnd -eq [IntPtr]::Zero) { throw "process found but no visible window" }
    return [pscustomobject]@{ Hwnd = $hwnd; Pid = $procs[0].Id; Title = $procs[0].MainWindowTitle }
}

function Read-LogTail {
    if (-not (Test-Path $Log)) { throw "no log at $Log" }
    # Tail rather than the whole file: the log grows every tick and only the newest geometry matters.
    $lines = @()
    $fs = [System.IO.File]::Open($Log, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    try {
        $len = [Math]::Min(160000, $fs.Length)
        $fs.Seek(-$len, [System.IO.SeekOrigin]::End) | Out-Null
        $buf = New-Object byte[] $len
        [void]$fs.Read($buf, 0, $len)
        $text = [System.Text.Encoding]::UTF8.GetString($buf)
        $lines = @($text -split "`r?`n")
    } finally { $fs.Dispose() }
    return $lines
}

function Get-LatestMatch([string[]]$lines, [string]$pattern) {
    # Newest wins: an earlier run's geometry is exactly the wrong answer after a resize.
    for ($i = $lines.Count - 1; $i -ge 0; $i--) {
        if ($lines[$i] -match $pattern) { return $lines[$i] }
    }
    return $null
}

function Get-CanvasSize {
    $lines = Read-LogTail
    # ui.geom is re-logged on every relayout (open, 刷新, resize), so the newest ui.geom line is the
    # current geometry; ui.button is the fallback for a panel that has never been opened yet.
    $line = Get-LatestMatch $lines 'ui\.(geom|button) .*?screen=(\d+)x(\d+)'
    if (-not $line) { throw 'no ui.geom / ui.button line in the log tail - open a screen first' }
    $m = [regex]::Match($line, 'screen=(\d+)x(\d+)')
    $out = [pscustomobject]@{ W = [int]$m.Groups[1].Value; H = [int]$m.Groups[2].Value
                              Scale = 0; Source = '' }
    $g = [regex]::Match($line, 'guiScale=(\d+)')
    if ($g.Success) { $out.Scale = [int]$g.Groups[1].Value }
    $out.Source = if ($line -match 'ui\.geom') { 'ui.geom' } else { 'ui.button' }
    return $out
}

function Resolve-Target([string]$name) {
    $lines = Read-LogTail
    if ($name -eq 'open') {
        $line = Get-LatestMatch $lines 'ui\.button .*?bounds=(-?\d+),(-?\d+) (\d+)x(\d+)'
        if ($line) {
            $m = [regex]::Match($line, 'bounds=(-?\d+),(-?\d+) (\d+)x(\d+)')
            return [pscustomobject]@{ X = [int]$m.Groups[1].Value; Y = [int]$m.Groups[2].Value
                                      W = [int]$m.Groups[3].Value; H = [int]$m.Groups[4].Value; Line = $line }
        }
        throw "target '$name' not present in the log tail"
    }
    $line = Get-LatestMatch $lines 'ui\.geom '
    if ($line) {
        $m = [regex]::Match($line, ('(^|[ =])' + [regex]::Escape($name) + '=(-?\d+),(-?\d+) (\d+)x(\d+)'))
        if ($m.Success) {
            return [pscustomobject]@{ X = [int]$m.Groups[2].Value; Y = [int]$m.Groups[3].Value
                                      W = [int]$m.Groups[4].Value; H = [int]$m.Groups[5].Value; Line = $line }
        }
    }
    throw "target '$name' not present in the log tail"
}

function Convert-ToScreenPoint([int]$cx, [int]$cy) {
    $w = Get-McWindow
    $client = New-Object Win+Rect
    [void][Win]::GetClientRect($w.Hwnd, [ref]$client)
    $origin = New-Object Win+Point
    [void][Win]::ClientToScreen($w.Hwnd, [ref]$origin)
    $canvas = Get-CanvasSize
    $ratioX = $client.right / $canvas.W
    $ratioY = $client.bottom / $canvas.H
    # The game's own integer scale beats a derived ratio: MC floors the cursor by it, so aiming must too.
    $scale = if ($canvas.Scale -gt 0) { $canvas.Scale } else { [Math]::Max(1, [int][Math]::Round($ratioX)) }
    $px = [int]($origin.x + $cx * $scale)
    $py = [int]($origin.y + $cy * $scale)
    return [pscustomobject]@{
        Hwnd = $w.Hwnd; Pid = $w.Pid
        Client = "$($client.right)x$($client.bottom)"; Canvas = "$($canvas.W)x$($canvas.H) [$($canvas.Source)]"
        ScaleX = [Math]::Round($ratioX, 3); ScaleY = [Math]::Round($ratioY, 3)
        RoundedScale = $scale; Os = "$px,$py"; CanvasPt = "$cx,$cy"
    }
}

function Invoke-Foreground([IntPtr]$hwnd) {
    # Windows refuses SetForegroundWindow for a background process, and a key sent to the wrong
    # window would land in the terminal instead of the game, so the request is verified by
    # GetForegroundWindow and retried with the documented unblock tricks (ALT press + AttachThreadInput).
    [void][Win]::ShowWindow($hwnd, 9)          # SW_RESTORE
    [void][Win]::SetForegroundWindow($hwnd)
    [void][Win]::BringWindowToTop($hwnd)
    if ([Win]::GetForegroundWindow() -ne $hwnd) {
        [Win]::keybd_event(0xA4, 0, 0, [IntPtr]::Zero)   # VK_LMENU down/up releases the foreground lock
        [Win]::keybd_event(0xA4, 0, $KEYEVENTF_KEYUP, [IntPtr]::Zero)
        $fg = [Win]::GetForegroundWindow()
        $fgPid = [uint32]0
        $fgThread = [Win]::GetWindowThreadProcessId($fg, [ref]$fgPid)
        [void][Win]::AttachThreadInput($fgThread, [Win]::GetCurrentThreadId(), $true)
        [void][Win]::SetForegroundWindow($hwnd)
        [void][Win]::BringWindowToTop($hwnd)
        [void][Win]::AttachThreadInput($fgThread, [Win]::GetCurrentThreadId(), $false)
    }
    Start-Sleep -Milliseconds 250
    return [pscustomobject]@{ Foreground = ([Win]::GetForegroundWindow() -eq $hwnd) }
}

function Invoke-Pointer([int]$cx, [int]$cy, [bool]$click) {
    $w = Get-McWindow
    $fg = Invoke-Foreground $w.Hwnd
    if (-not $fg.Foreground) { Write-Output 'probe WARNING: window is not the foreground window' }
    $p = Convert-ToScreenPoint $cx $cy
    Write-Output ("probe pid=" + $p.Pid + " client=" + $p.Client + " canvas=" + $p.Canvas +
        " scale=" + $p.ScaleX + "/" + $p.ScaleY + " pt=" + $p.CanvasPt + " -> os=" + $p.Os)
    [void][Win]::SetCursorPos([int]($p.Os.Split(',')[0]), [int]($p.Os.Split(',')[1]))
    [Win]::mouse_event($MOUSEEVENTF_MOVE, 0, 0, 0, [IntPtr]::Zero)
    Start-Sleep -Milliseconds 250
    if ($click) {
        [Win]::mouse_event($MOUSEEVENTF_LEFTDOWN, 0, 0, 0, [IntPtr]::Zero)
        Start-Sleep -Milliseconds 90
        [Win]::mouse_event($MOUSEEVENTF_LEFTUP, 0, 0, 0, [IntPtr]::Zero)
        Write-Output "probe clicked os=$($p.Os)"
    }
}

$keys = @{ e = 0x45; f2 = 0x71; r = 0x52; escape = 0x1B; esc = 0x1B; i = 0x49; tab = 0x09; q = 0x51; t = 0x54; enter = 0x0D }

switch ($Verb) {
    'info' {
        $p = Convert-ToScreenPoint 0 0
        Write-Output ("probe client=" + $p.Client + " canvas=" + $p.Canvas + " scale=" + $p.ScaleX + "/" + $p.ScaleY)
    }
    'focus' {
        $w = Get-McWindow
        $fg = Invoke-Foreground $w.Hwnd
        Write-Output "probe focused pid=$($w.Pid) foreground=$($fg.Foreground) title=$($w.Title)"
    }
    'resize' {
        $wantW = [int]$A; $wantH = [int]$B
        $w = Get-McWindow
        $wr = New-Object Win+Rect
        $cr = New-Object Win+Rect
        [void][Win]::GetWindowRect($w.Hwnd, [ref]$wr)
        [void][Win]::GetClientRect($w.Hwnd, [ref]$cr)
        $frameW = ($wr.right - $wr.left) - $cr.right
        $frameH = ($wr.bottom - $wr.top) - $cr.bottom
        [void][Win]::MoveWindow($w.Hwnd, $wr.left, $wr.top, $wantW + $frameW, $wantH + $frameH, $true)
        Start-Sleep -Milliseconds 600
        $cr2 = New-Object Win+Rect
        [void][Win]::GetClientRect($w.Hwnd, [ref]$cr2)
        Write-Output "probe resized client=$($cr2.right)x$($cr2.bottom) frame=+$frameW,+$frameH"
    }
    'hover' {
        $t = Resolve-Target $A
        Invoke-Pointer ($t.X + [int]($t.W / 2)) ($t.Y + [int]($t.H / 2)) $false
    }
    'click' {
        $t = Resolve-Target $A
        Invoke-Pointer ($t.X + [int]($t.W / 2)) ($t.Y + [int]($t.H / 2)) $true
    }
    'clickpoint' {
        Invoke-Pointer ([int]$A) ([int]$B) $true
    }
    'hoverpoint' {
        Invoke-Pointer ([int]$A) ([int]$B) $false
    }
    'key' {
        $vk = $keys[$A.ToLower()]
        if (-not $vk) { throw "unknown key '$A' (known: $($keys.Keys -join ' '))" }
        $w = Get-McWindow
        $fg = Invoke-Foreground $w.Hwnd
        if (-not $fg.Foreground) { throw 'refusing to send a key: the game window is not in the foreground' }
        [Win]::keybd_event([byte]$vk, 0, 0, [IntPtr]::Zero)
        Start-Sleep -Milliseconds 70
        [Win]::keybd_event([byte]$vk, 0, $KEYEVENTF_KEYUP, [IntPtr]::Zero)
        Write-Output "probe key $A (vk=$vk) sent to pid=$($w.Pid)"
    }
    'run' {
        # Type a chat command through the real keyboard path so the test world can be prepared
        # (e.g. place a chest) without a human at the desk. SendKeys reads '~' as ENTER, so the
        # tilde is brace-escaped before sending.
        Add-Type -AssemblyName System.Windows.Forms
        $w = Get-McWindow
        $fg = Invoke-Foreground $w.Hwnd
        if (-not $fg.Foreground) { throw 'refusing to type: the game window is not in the foreground' }
        [Win]::keybd_event([byte]0x54, 0, 0, [IntPtr]::Zero)
        Start-Sleep -Milliseconds 70
        [Win]::keybd_event([byte]0x54, 0, $KEYEVENTF_KEYUP, [IntPtr]::Zero)
        Start-Sleep -Milliseconds 400
        $text = $A.Replace('~', '{~}')
        [System.Windows.Forms.SendKeys]::SendWait($text)
        Start-Sleep -Milliseconds 400
        [System.Windows.Forms.SendKeys]::SendWait('{ENTER}')
        Write-Output "probe run sent: $A"
    }
    { $_ -eq 'wheeldn' -or $_ -eq 'wheelup' } {
        # $A=canvasX $B=canvasY $C=notches. Minecraft moves the list by "scroll - signum(scrollY)"
        # and GLFW reports a downward notch as negative, so the count is positive here: '-3' on the
        # command line would be parsed by PowerShell as a parameter name, not a number.
        $notches = [int]$C
        if ($notches -le 0) { $notches = 1 }
        if ($Verb -eq 'wheeldn') { $notches = -$notches }
        $w = Get-McWindow
        $fg = Invoke-Foreground $w.Hwnd
        if (-not $fg.Foreground) { throw 'refusing to scroll: the game window is not in the foreground' }
        $p = Convert-ToScreenPoint ([int]$A) ([int]$B)
        [void][Win]::SetCursorPos([int]($p.Os.Split(',')[0]), [int]($p.Os.Split(',')[1]))
        Start-Sleep -Milliseconds 200
        for ($i = 0; $i -lt [Math]::Abs($notches); $i++) {
            [Win]::mouse_event($MOUSEEVENTF_WHEEL, 0, 0, [int]($notches * 120), [IntPtr]::Zero)
            Start-Sleep -Milliseconds 120
        }
        Write-Output ("probe wheel at " + $p.Os + " notches=" + $notches)
    }
    default { throw "unknown verb '$Verb'" }
}
