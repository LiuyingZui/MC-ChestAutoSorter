<#
  Ask the running Minecraft client to quit the way a player does: WM_CLOSE on its window.

  Why this exists: killing the JVM (Stop-Process, or the agent harness cancelling a background task)
  leaves <gameDir>/saves/<world>/session.lock held and the region files unwritten, so the very next
  run fails with "Failed to read level cas-test data". Posting WM_CLOSE lets Minecraft save and exit
  cleanly, which is the only safe way to end an unattended run.

  Usage:  powershell -NoProfile -File tools/close-mc-windows.ps1 [-TitlePattern *Minecraft*] [-WaitSeconds 45]
#>
param(
    [string]$TitlePattern = '*Minecraft*',
    [int]$WaitSeconds = 45
)

Add-Type -AssemblyName System.Windows.Forms

$sources = @'
using System;
using System.Runtime.InteropServices;
public static class Win {
    public delegate bool EnumProc(IntPtr h, IntPtr l);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr l);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint msg, IntPtr w, IntPtr l);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
    public static System.Collections.Generic.List<IntPtr> Collect(uint[] wanted) {
        var hits = new System.Collections.Generic.List<IntPtr>();
        EnumWindows((h, l) => {
            uint pid; GetWindowThreadProcessId(h, out pid);
            if (Array.Exists(wanted, t => t == pid) && IsWindowVisible(h)) hits.Add(h);
            return true;
        }, IntPtr.Zero);
        return hits;
    }
}
'@
if (-not ('Win' -as [type])) { Add-Type -TypeDefinition $sources }

$const_WM_CLOSE = 0x0010

$targets = @(Get-Process java, javaw -ErrorAction SilentlyContinue |
    Where-Object { $_.MainWindowTitle -like $TitlePattern })

if ($targets.Count -eq 0) {
    Write-Output 'close: no Minecraft window found, nothing to do'
    exit 0
}

foreach ($proc in $targets) {
    Write-Output ("close: pid=" + $proc.Id + " title=[" + $proc.MainWindowTitle + "]")
    foreach ($hwnd in [Win]::Collect([uint32[]]@($proc.Id))) {
        [void][Win]::PostMessage($hwnd, $const_WM_CLOSE, [IntPtr]::Zero, [IntPtr]::Zero)
        Write-Output ("close: posted WM_CLOSE to " + $hwnd)
    }
}

$deadline = (Get-Date).AddSeconds($WaitSeconds)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 1000
    $alive = @(Get-Process -Id $targets.Id -ErrorAction SilentlyContinue)
    if ($alive.Count -eq 0) {
        Write-Output 'close: all target processes exited (world saved, session.lock released)'
        exit 0
    }
}
Write-Output ('close: STILL RUNNING after ' + $WaitSeconds + 's - do not kill it, inspect the window')
exit 1
