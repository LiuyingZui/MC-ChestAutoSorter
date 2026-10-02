# Checks what actually shipped: test-only classes must be absent from the release jar, and the
# fix's own artefacts (planner class + new translation keys) must be present in both languages.
param([string]$Jar = 'E:\PCL\mod_develop\ChestAutoSorter\release\chestautosorter-0.1.0.jar')
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($Jar)
try {
    $names = @($zip.Entries | ForEach-Object { $_.FullName })
    Write-Output ("entries=" + $names.Count)
    foreach ($n in @('com/chestautosorter/gametest/CasGameTests.class',
                     'com/chestautosorter/client/UiHarness.class',
                     'com/chestautosorter/core/SortPlanner.class')) {
        if ($names -contains $n) { Write-Output ("HAS   " + $n) } else { Write-Output ("MISS  " + $n) }
    }
    foreach ($lang in @('zh_cn', 'en_us')) {
        $entry = $zip.GetEntry("assets/chestautosorter/lang/$lang.json")
        if (-not $entry) { Write-Output "MISS  lang/$lang.json"; continue }
        $reader = New-Object System.IO.StreamReader($entry.Open())
        $text = $reader.ReadToEnd()
        $reader.Close()
        $json = $text | ConvertFrom-Json
        $keys = @('chestautosorter.result.layout_spread', 'chestautosorter.result.layout_mixed',
                  'chestautosorter.result.done_spread', 'chestautosorter.button.open')
        foreach ($k in $keys) {
            $v = $json.$k
            if ($v) { Write-Output ("$lang OK  $k = $v") } else { Write-Output ("$lang MISSING KEY $k") }
        }
        # CJK in en_us would mean someone hard-coded Chinese into the English file. Built from code
        # points because Windows PowerShell reads .ps1 as ANSI, which mangles literal CJK.
        $cjk = '[' + [char]0x4E00 + '-' + [char]0x9FFF + ']'
        if ($lang -eq 'en_us' -and $text -match $cjk) { Write-Output 'en_us FAIL contains CJK' }
    }
}
finally { $zip.Dispose() }
