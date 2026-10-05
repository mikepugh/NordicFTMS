#Requires -Version 5.1
<#
.SYNOPSIS
Copy the installed iFIT Wolf and ERU APKs through an existing ADB connection.
.DESCRIPTION
Place this script beside adb.exe and connect/authorize the console as for APK
installation. Leave the equipment idle and iFIT enabled. No administrator
privileges, root, NordicFTMS retry or debug-logging toggle are needed.

Run in PowerShell:
  .\collect-ifit-apks.ps1
If execution policy blocks the reviewed script, this applies only to one run:
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\collect-ifit-apks.ps1
It does not change the saved execution policy. Do not override managed policy.

Only pm path and adb pull are used on the selected Android device. Nothing is
installed, stopped, disabled, deleted or changed there. No private app-data
directories, saved logins, or user databases are copied. The installed program
APKs (including splits) and a checksum manifest are saved in a new PC folder
and ZIP on the Desktop. Nothing is uploaded. Send the ZIP privately to support.
ADB may start its normal PC-side daemon. APKs may be large; allow time to copy.
.PARAMETER AdbPath
Path to adb.exe; searched beside this script, in the current folder, then PATH.
.PARAMETER Serial
Exact authorized identifier from adb devices; required if several are connected.
.PARAMETER OutputDirectory
PC output parent directory; defaults to Desktop, or the current folder if absent.
#>
[CmdletBinding()]
param(
    [string]$AdbPath,
    [string]$Serial,
    [string]$OutputDirectory
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

function Invoke-AdbCapture {
    param([string[]]$Arguments)
    # Native stderr is diagnostic output, not a PowerShell terminating error.
    $ErrorActionPreference = 'Continue'
    $PSNativeCommandUseErrorActionPreference = $false
    $lines = @(& $AdbPath @Arguments 2>&1)
    $code = $LASTEXITCODE
    return [pscustomobject]@{
        ExitCode = $code
        Output = ($lines | ForEach-Object { "$_" }) -join "`r`n"
    }
}

if (-not $AdbPath) {
    foreach ($candidate in @((Join-Path $PSScriptRoot 'adb.exe'), (Join-Path (Get-Location) 'adb.exe'))) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { $AdbPath = $candidate; break }
    }
    if (-not $AdbPath) {
        $command = Get-Command adb -CommandType Application -ErrorAction SilentlyContinue
        if ($command) { $AdbPath = $command.Source }
    }
}
if (-not $AdbPath -or -not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw 'Cannot find adb.exe. Put this script beside adb.exe or supply -AdbPath.'
}
$AdbPath = (Resolve-Path -LiteralPath $AdbPath).Path
$devices = Invoke-AdbCapture -Arguments @('devices', '-l')
if ($devices.ExitCode -ne 0) { throw "ADB device listing failed:`r`n$($devices.Output)" }
$authorized = @($devices.Output -split '\r?\n' | ForEach-Object {
    if ($_ -match '^(\S+)\s+device(?:\s|$)') { $Matches[1] }
})
if (-not $Serial) {
    if ($authorized.Count -ne 1) {
        throw "Connect/authorize exactly one device, or supply -Serial. No APKs copied.`r`n$($devices.Output)"
    }
    $Serial = $authorized[0]
}
if ($Serial -notmatch '^[A-Za-z0-9_.:%\[\]-]+$' -or $authorized -cnotcontains $Serial) {
    throw 'The selected -Serial must exactly match an authorized device from adb devices.'
}

if (-not $OutputDirectory) { $OutputDirectory = [Environment]::GetFolderPath('Desktop') }
if (-not $OutputDirectory) { $OutputDirectory = (Get-Location).Path }
$name = 'NordicFTMS-iFIT-APKs-{0}-{1}' -f (Get-Date -Format 'yyyyMMdd-HHmmss'), ([guid]::NewGuid().ToString('N').Substring(0, 8))
$folder = Join-Path $OutputDirectory $name
[void][System.IO.Directory]::CreateDirectory($folder)
$zipPath = "$folder.zip"
$partialZipPath = "$folder.partial.zip"
$manifest = @()

try {
    foreach ($package in @('com.ifit.standalone', 'com.ifit.eru')) {
        Write-Host "Locating installed APKs: $package"
        $result = Invoke-AdbCapture -Arguments @('-s', $Serial, 'shell', 'pm', 'path', $package)
        if ($result.ExitCode -ne 0) { throw "Could not locate ${package}:`r`n$($result.Output)" }
        $paths = @($result.Output -split '\r?\n' | ForEach-Object {
            if ($_ -match '^package:(/[^\r\n]+\.apk)\s*$') { $Matches[1] }
        } | Sort-Object -Unique)
        if (-not $paths.Count) { throw "No APK paths returned for ${package}:`r`n$($result.Output)" }

        $destination = Join-Path $folder $package
        [void][System.IO.Directory]::CreateDirectory($destination)
        $names = @{}
        foreach ($path in $paths) {
            # Accept installed-code locations only, never private user-data paths.
            if ($path -notmatch '^/(?:data/app/|system/|system_ext/|product/|vendor/|odm/|oem/|mnt/expand/[^/]+/app/)' -or
                $path -match '/\.\.?/' -or $path -match '[\x00-\x1f]') {
                throw "Unexpected installed APK location: $path"
            }
            $fileName = ($path -split '/')[-1]
            if ($fileName -notmatch '^[A-Za-z0-9_.-]+\.apk$' -or $names.ContainsKey($fileName)) {
                throw "Unsafe or duplicate APK filename: $fileName"
            }
            $names[$fileName] = $true
            $localPath = Join-Path $destination $fileName
            Write-Host "Copying $package / $fileName (large APKs may take several minutes)..."
            $copy = Invoke-AdbCapture -Arguments @('-s', $Serial, 'pull', $path, $localPath)
            if ($copy.ExitCode -ne 0) { throw "Failed to copy ${path}:`r`n$($copy.Output)" }
            if (-not (Test-Path -LiteralPath $localPath -PathType Leaf)) {
                throw "ADB reported success but the APK was not created: $localPath"
            }
            $file = Get-Item -LiteralPath $localPath
            if ($file.Length -le 0) { throw "Downloaded APK is empty: $localPath" }
            $manifest += [pscustomobject]@{
                Package = $package
                SourcePath = $path
                File = "$package/$fileName"
                Bytes = $file.Length
                SHA256 = (Get-FileHash -LiteralPath $localPath -Algorithm SHA256).Hash
            }
        }
    }
    $metadata = [pscustomobject]@{
        CollectorVersion = 1
        ExportedAt = (Get-Date).ToString('o')
        Files = @($manifest)
    }
    $metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $folder 'manifest.json') -Encoding UTF8
    Write-Host 'Creating the ZIP on this PC...'
    Compress-Archive -LiteralPath $folder -DestinationPath $partialZipPath -CompressionLevel NoCompression
    Move-Item -LiteralPath $partialZipPath -Destination $zipPath
} catch {
    Write-Warning "Export incomplete. Do not send a partial ZIP. Details: $($_.Exception.Message)"
    Write-Warning "Any files already copied remain here: $folder"
    throw
}
Write-Host "Completed: $($manifest.Count) APK files copied. No changes made to the console."
Write-Host "Send this ZIP privately to support: $zipPath"
