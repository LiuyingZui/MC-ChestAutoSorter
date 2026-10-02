# Dev-only helper: the runClient window comes up minimized when Gradle is launched from a
# background shell, and a minimized window cannot render (Screenshot.grab would capture nothing).
# Wait for the Minecraft window, then restore and focus it.
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class Win32Focus {
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int nCmdShow);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
}
"@

$deadline = (Get-Date).AddSeconds(300)
while ((Get-Date) -lt $deadline) {
  $p = Get-Process | Where-Object { $_.MainWindowTitle -like "*Minecraft*" -and $_.MainWindowHandle -ne 0 } | Select-Object -First 1
  if ($p) {
    [Win32Focus]::ShowWindow($p.MainWindowHandle, 9) | Out-Null  # SW_RESTORE
    [Win32Focus]::ShowWindow($p.MainWindowHandle, 5) | Out-Null  # SW_SHOW
    [Win32Focus]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
    [Win32Focus]::BringWindowToTop($p.MainWindowHandle) | Out-Null
    Write-Output "focused pid=$($p.Id) title=$($p.MainWindowTitle)"
    exit 0
  }
  Start-Sleep -Milliseconds 1500
}
Write-Output "no Minecraft window found within 300s"
exit 1
