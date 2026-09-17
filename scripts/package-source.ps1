$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$projectRoot = Split-Path $PSScriptRoot -Parent
$destination = Join-Path $projectRoot 'dist'
New-Item -ItemType Directory -Force $destination | Out-Null
$archivePath = Join-Path $destination 'spot-matching-source.zip'
# Allowlist source inputs. Never package databases, runtime journals, logs, or generated build output.
$files = [System.Collections.Generic.List[System.IO.FileInfo]]::new()
foreach ($name in @('pom.xml', 'README.md', 'AGENTS.md', '.gitignore', 'mvnw', 'mvnw.cmd')) {
    $files.Add((Get-Item -LiteralPath (Join-Path $projectRoot $name)))
}
foreach ($name in @('.mvn', 'scripts')) {
    Get-ChildItem -LiteralPath (Join-Path $projectRoot $name) -File -Recurse | ForEach-Object { $files.Add($_) }
}
foreach ($module in @('matching-core', 'matching-protocol', 'matching-persistence', 'matching-server', 'matching-mock')) {
    $modulePath = Join-Path $projectRoot $module
    Get-ChildItem -LiteralPath $modulePath -File | Where-Object { $_.Name -eq 'pom.xml' -or $_.Extension -eq '.md' } | ForEach-Object { $files.Add($_) }
    foreach ($name in @('src', 'database')) {
        $path = Join-Path $modulePath $name
        if (Test-Path -LiteralPath $path) {
            Get-ChildItem -LiteralPath $path -File -Recurse -Force | ForEach-Object { $files.Add($_) }
        }
    }
}
$stream = [System.IO.File]::Open($archivePath, [System.IO.FileMode]::Create)
$zip = [System.IO.Compression.ZipArchive]::new($stream, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in $files) {
        $relative = $file.FullName.Substring($projectRoot.Length + 1).Replace('\', '/')
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $file.FullName, "spot-matching/$relative") | Out-Null
    }
} finally { $zip.Dispose(); $stream.Dispose() }
Get-FileHash -LiteralPath $archivePath -Algorithm SHA256
Write-Output "Source archive: $archivePath ($($files.Count) files)"
