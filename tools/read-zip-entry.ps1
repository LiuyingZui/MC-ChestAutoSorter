param([string]$Zip, [string]$Entry, [string]$Out)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($Zip)
$e = $zip.Entries | Where-Object { $_.FullName -eq $Entry }
if ($null -eq $e) { Write-Output "MISSING"; $zip.Dispose(); exit 1 }
$sr = New-Object System.IO.StreamReader($e.Open())
$txt = $sr.ReadToEnd()
$sr.Close()
$zip.Dispose()
if ($Out) { [System.IO.File]::WriteAllText($Out, $txt); Write-Output ("WROTE " + $txt.Length) } else { Write-Output $txt }
