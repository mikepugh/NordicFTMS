#Requires -Version 5.1
<#
.SYNOPSIS
Read-only NordicFTMS / iFIT / GlassOS diagnostics over an existing ADB connection.
.DESCRIPTION
Leave iFIT enabled and the equipment idle. NordicFTMS need not be running.
Place this file beside adb.exe, or supply -AdbPath. Connect/authorize ADB as you
did for installation. No administrator privileges or root are required.

Run in PowerShell: .\collect-glassos-diagnostics.ps1
If local execution policy blocks this reviewed script, run just this invocation:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\collect-glassos-diagnostics.ps1
This does not change the machine's saved execution policy. Do not run as admin.
With several connected devices: .\collect-glassos-diagnostics.ps1 -Serial SERIAL

The script only reads from Android. It does not connect to backend sockets,
start/stop apps, change settings, clear logs, reboot, or read private app files,
certificates, or keys. It writes one UTF-8 text report on the PC (Desktop by
default) and uploads nothing. ADB may start its normal local PC daemon.

Logs and service dumps can contain identifiers, addresses, and incidental
personal data. Review the report and send it privately to support.

Unavailable commands and permission errors remain in the report. Log buffers
are bounded snapshots, not complete history; absence is not proof of absence.
.PARAMETER AdbPath
Path to adb.exe. Defaults to this script's directory, the current directory,
then PATH.
.PARAMETER Serial
Exact device identifier from adb devices. Required if more than one device is
authorized. This script never connects to a new address automatically.
.PARAMETER OutputDirectory
Directory on the PC in which to create the report.
#>
[CmdletBinding()]
param(
    [string]$AdbPath,
    [string]$Serial,
    [string]$OutputDirectory
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$relevantNames = '(?i)ifit|glassos|valinor|mithlond|rivendell|gandalf|nordicftms|iconfitness|fitpro'
$relevantLogs = $relevantNames + '|(?i)\bgrpc\b'
$failedCommands = 0
$reportWriter = $null

function Invoke-AdbRead {
    param([string[]]$Arguments, [int]$TimeoutSeconds = 25)
    # All arguments are generated tokens or validated device/package identifiers.
    foreach ($argument in $Arguments) {
        if ($argument -match '[\s"\x00]') {
            throw "Unexpected whitespace or quote in ADB argument: $argument"
        }
    }
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $AdbPath
    $start.Arguments = '"' + ($Arguments -join '" "') + '"'
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $start.StandardErrorEncoding = [System.Text.Encoding]::UTF8
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $start
    $result = [pscustomobject]@{
        Command = 'adb ' + ($Arguments -join ' ')
        At = (Get-Date).ToString('o')
        ExitCode = -1
        Output = ''
        Error = ''
        TimedOut = $false
    }
    try {
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $result.TimedOut = $true
            # Terminate only this hung PC-side adb client, never the ADB server.
            $process.Kill()
            [void]$process.WaitForExit(5000)
        }
        if ($stdout.Wait(5000)) { $result.Output = $stdout.Result }
        if ($stderr.Wait(5000)) { $result.Error = $stderr.Result }
        if ($process.HasExited) { $result.ExitCode = $process.ExitCode }
    } catch {
        $result.Error += "`r`n$($_.Exception.Message)"
    } finally {
        $process.Dispose()
    }
    return $result
}

function Write-Report {
    param([string]$Text)
    $reportWriter.WriteLine($Text)
    $reportWriter.Flush()
}

function Read-Device {
    param([string[]]$Arguments, [switch]$FilterRelevant)
    $result = Invoke-AdbRead -Arguments (@('-s', $Serial) + $Arguments)
    Write-Report "`r`n=== $($Arguments -join ' ') ==="
    Write-Report "Captured: $($result.At); exit=$($result.ExitCode); timed_out=$($result.TimedOut)"
    if ($FilterRelevant) {
        $lines = @($result.Output -split '\r?\n' | Where-Object { $_ -match $relevantLogs })
        Write-Report 'Only lines matching backend/app names or gRPC are retained in this section.'
        if ($lines.Count) { Write-Report ($lines -join "`r`n") }
        else { Write-Report '(No matching lines in this bounded snapshot.)' }
    } else {
        Write-Report $result.Output
    }
    if ($result.Error) { Write-Report ("STDERR:`r`n" + $result.Error) }
    if ($result.ExitCode -ne 0 -or $result.TimedOut) {
        $script:failedCommands++
        Write-Report 'UNAVAILABLE/FAILED: do not interpret missing output as a negative finding.'
    }
    return $result
}

function Get-ProcessRows {
    param([string]$Text)
    $lines = @($Text -split '\r?\n' | Where-Object { $_.Trim() })
    if (-not $lines.Count) { return }
    $header = $lines[0].Trim() -split '\s+'
    $pidColumn = [Array]::IndexOf($header, 'PID')
    $uidColumn = [Array]::IndexOf($header, 'UID')
    if ($uidColumn -lt 0) { $uidColumn = [Array]::IndexOf($header, 'USER') }
    if ($pidColumn -lt 0 -or $uidColumn -lt 0) { return }
    foreach ($line in ($lines | Select-Object -Skip 1)) {
        $fields = $line.Trim() -split '\s+'
        if ($fields.Count -le [Math]::Max($pidColumn, $uidColumn)) { continue }
        if ($fields[$pidColumn] -notmatch '^\d+$') { continue }
        $uid = $fields[$uidColumn]
        if ($uid -match '^u(\d+)_a(\d+)$') {
            $uid = [string](100000 * [int]$Matches[1] + 10000 + [int]$Matches[2])
        }
        [pscustomobject]@{ ProcessId = $fields[$pidColumn]; Uid = $uid; Name = $fields[-1] }
    }
}

function Get-ProcessSnapshot {
    $snapshot = Read-Device -Arguments @('shell', 'ps', '-A', '-o', 'UID,PID,NAME')
    $rows = @(Get-ProcessRows $snapshot.Output)
    if ($snapshot.ExitCode -ne 0 -or -not $rows.Count) {
        $snapshot = Read-Device -Arguments @('shell', 'ps', '-A')
        $rows = @(Get-ProcessRows $snapshot.Output)
    }
    if (-not $rows.Count) {
        Write-Report 'Process inventory could not be parsed; PID log collection is incomplete.'
    }
    return $rows
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
    throw 'Cannot find adb.exe. Place this script beside adb.exe or supply -AdbPath.'
}
$AdbPath = (Resolve-Path -LiteralPath $AdbPath).Path
$deviceList = Invoke-AdbRead -Arguments @('devices', '-l')
if ($deviceList.ExitCode -ne 0 -or $deviceList.TimedOut) {
    throw "ADB device listing failed: $($deviceList.Error)"
}
$authorized = @($deviceList.Output -split '\r?\n' | ForEach-Object {
    if ($_ -match '^(\S+)\s+device(?:\s|$)') { $Matches[1] }
})
if (-not $Serial) {
    if ($authorized.Count -ne 1) {
        throw "Connect/authorize exactly one device, or supply -Serial. No data collected.`r`n$($deviceList.Output)"
    }
    $Serial = $authorized[0]
}
if ($Serial -notmatch '^[A-Za-z0-9_.:%\[\]-]+$' -or $authorized -cnotcontains $Serial) {
    throw 'The selected -Serial must exactly match an authorized device from adb devices.'
}
if (-not $OutputDirectory) { $OutputDirectory = [Environment]::GetFolderPath('Desktop') }
if (-not $OutputDirectory) { $OutputDirectory = (Get-Location).Path }
[void][System.IO.Directory]::CreateDirectory($OutputDirectory)
$filename = 'NordicFTMS-diagnostics-{0}-{1}.txt' -f (Get-Date -Format 'yyyyMMdd-HHmmss'), ([guid]::NewGuid().ToString('N').Substring(0, 8))
$reportPath = Join-Path $OutputDirectory $filename
$reportWriter = New-Object System.IO.StreamWriter($reportPath, $false, (New-Object System.Text.UTF8Encoding($false)))

try {
    Write-Host 'Reading diagnostics. Leave iFIT enabled and the equipment idle. No retry button needed.'
    Write-Report "NordicFTMS read-only collector v1`r`nPC time: $((Get-Date).ToString('o'))"
    Write-Report 'Private support report. Review before sharing. No automatic upload or redaction.'
    Write-Report 'Only Android reads are performed. Process names/UIDs are clues, not verified backend identities.'
    Write-Report 'Log buffers are finite. Old logs, unsupported commands and access restrictions can leave gaps.'
    Write-Report 'PID reuse and shared app UIDs can include other processes; correlate timestamps and process snapshots.'
    [void](Read-Device -Arguments @('shell', 'date'))

    $packageResult = Read-Device -Arguments @('shell', 'pm', 'list', 'packages', '-U') -FilterRelevant
    if ($packageResult.ExitCode -ne 0) {
        $packageResult = Read-Device -Arguments @('shell', 'pm', 'list', 'packages') -FilterRelevant
    }
    $packages = @()
    $appUids = @{}
    foreach ($line in ($packageResult.Output -split '\r?\n')) {
        if ($line -match '^package:([A-Za-z0-9_.]+)(?:\s+uid:(\d+))?\s*$') {
            $packageName = $Matches[1]
            $packageUid = $Matches[2]
            if ($packageName -match $relevantNames) {
                $packages += $packageName
                # Do not capture every system process when an iFIT app shares UID 1000.
                if ($packageUid -and [long]$packageUid -ge 10000) { $appUids[$packageUid] = $true }
            }
        }
    }
    $packages = @($packages | Sort-Object -Unique)
    Write-Report "Matched packages: $($packages -join ', ')"

    # Capture app logs early, before slower service dumps can age them out.
    $capturedPids = @{}
    for ($pass = 1; $pass -le 2; $pass++) {
        Write-Host "Reading process logs (snapshot $pass of 2)..."
        $processes = @(Get-ProcessSnapshot)
        $targets = @($processes | Where-Object { $_.Name -match $relevantNames -or $appUids.ContainsKey($_.Uid) })
        foreach ($target in $targets) {
            if ($capturedPids.ContainsKey($target.ProcessId)) { continue }
            if ($capturedPids.Count -ge 32) {
                Write-Report 'PID capture limit (32) reached; remaining process logs were not collected.'
                break
            }
            $capturedPids[$target.ProcessId] = $true
            Write-Report "`r`nPROCESS LOG: pid=$($target.ProcessId); uid=$($target.Uid); name=$($target.Name)"
            Write-Report 'Bounded logcat snapshot (-t 1500) filtered by PID, all tags/priorities, main and system buffers.'
            [void](Read-Device -Arguments @('logcat', '-d', '-b', 'main', '-b', 'system', '-v', 'threadtime', '-t', '1500', "--pid=$($target.ProcessId)", '*:V'))
        }
    }
    if (-not $capturedPids.Count) { Write-Report 'No matching running PIDs identified. This does not prove the backend is absent.' }

    Write-Host 'Reading recent system, lifecycle and crash logs...'
    Write-Report 'Recent name-matching system/app lines can reveal failures from processes that already exited.'
    [void](Read-Device -Arguments @('logcat', '-d', '-b', 'main', '-b', 'system', '-v', 'threadtime', '-t', '8000', '*:V') -FilterRelevant)
    Write-Report 'Name-matching lifecycle events, including process starts, deaths, crashes and ANRs.'
    [void](Read-Device -Arguments @('logcat', '-d', '-b', 'events', '-v', 'threadtime', '-t', '3000', 'am_proc_start:I', 'am_proc_died:I', 'am_crash:I', 'am_anr:I', '*:S') -FilterRelevant)
    Write-Report 'Recent crash buffer is intentionally unfiltered to preserve native/Java stack context; it may include other apps.'
    [void](Read-Device -Arguments @('logcat', '-d', '-b', 'crash', '-v', 'threadtime', '-t', '500', '*:V'))

    Write-Host 'Reading firmware, sockets and service state...'
    foreach ($property in @('ro.product.model', 'ro.product.manufacturer', 'ro.build.version.release', 'ro.build.version.sdk', 'ro.build.fingerprint', 'ro.build.version.security_patch')) {
        [void](Read-Device -Arguments @('shell', 'getprop', $property))
    }
    [void](Read-Device -Arguments @('shell', 'cat', '/proc/uptime'))
    [void](Read-Device -Arguments @('shell', 'service', 'list'))
    [void](Read-Device -Arguments @('shell', 'ss', '-lntp'))
    [void](Read-Device -Arguments @('shell', 'netstat', '-lnt'))
    foreach ($table in @('/proc/net/tcp', '/proc/net/tcp6', '/proc/net/unix')) {
        [void](Read-Device -Arguments @('shell', 'cat', $table))
    }
    foreach ($package in $packages) {
        Write-Host "Reading package and service details: $package"
        [void](Read-Device -Arguments @('shell', 'dumpsys', '-t', '10', 'package', $package))
        [void](Read-Device -Arguments @('shell', 'dumpsys', '-t', '10', 'activity', 'services', $package))
    }
    Write-Report "`r`nCollection completed: $((Get-Date).ToString('o')); unavailable/failed commands: $failedCommands"
} catch {
    Write-Report "`r`nCOLLECTION INCOMPLETE: $($_.Exception.Message)"
    Write-Warning "Collection interrupted. The partial report is still useful: $reportPath"
    throw
} finally {
    $reportWriter.Dispose()
}
Write-Host "Report created: $reportPath"
Write-Host 'Review it for personal information, then send the TXT file privately to support.'
