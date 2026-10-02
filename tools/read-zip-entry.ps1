param([string]$Zip, [string]$Entry, [string]$Out)
Add-Type -AssemblyName System.IO.Compression.FileSystem
# The local must not be named $zip: PowerShell variable names are case-insensitive, so it would
# overwrite the $Zip parameter and a failed OpenRead would surface as a confusing member-not-found.
# Resolve against PowerShell's location, not the .NET process one, so a relative path behaves
# the way the caller typed it.
$full = (Resolve-Path -LiteralPath $Zip).ProviderPath
$archive = [System.IO.Compression.ZipFile]::OpenRead($full)
try {
    $found = $archive.Entries | Where-Object { $_.FullName -eq $Entry }
    if ($null -eq $found) { Write-Output 'MISSING'; exit 1 }
    $reader = New-Object System.IO.StreamReader($found.Open())
    $text = $reader.ReadToEnd()
    $reader.Dispose()
    if ($Out) {
        [System.IO.File]::WriteAllText($Out, $text)
        Write-Output ('WROTE ' + $text.Length)
    } else {
        Write-Output $text
    }
} finally {
    $archive.Dispose()
}
