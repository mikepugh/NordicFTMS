#Requires -Version 5.1
<#
.SYNOPSIS
Collecte les diagnostics NordicRower via une connexion ADB existante.
.DESCRIPTION
Placez ce script dans le dossier de adb.exe. Apres le test Bluetooth et la
recherche des capacites dans NordicRower, executez :
  .\collect-nordicrower-diagnostics.ps1
Si Windows bloque ce script que vous avez examine :
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\collect-nordicrower-diagnostics.ps1
Cette commande ne change pas la strategie d'execution enregistree.
Avec plusieurs appareils : ajoutez -Serial NUMERO_DE_L_APPAREIL.

Lecture seule sur Android : aucun changement de resistance, aucune commande
materielle, aucun lancement/arret d'application, aucun effacement, aucun root,
aucun redemarrage. ADB peut demarrer son serveur habituel sur le PC.
Les fichiers prives sont lus uniquement pour com.nordicrower.app via run-as,
disponible dans l'APK experimental debuggable. Aucun fichier iFIT n'est lu.
Les journaux sont copies dans un dossier puis un ZIP sur le PC. Aucun envoi
automatique. Les dumps USB/Bluetooth/systeme peuvent contenir des identifiants
et des informations d'autres appareils/apps : examinez le ZIP et envoyez-le
uniquement au support, pas dans une issue publique GitHub.

Les erreurs et les fichiers manquants sont conserves. Une absence de donnees
n'est pas une preuve d'absence de capacite. Les notifications Bluetooth
terminees ne prouvent pas que EXR/RowerTrain a interprete les valeurs.
.PARAMETER AdbPath
Chemin de adb.exe (par defaut : dossier du script, dossier courant, puis PATH).
.PARAMETER Serial
Identifiant exact de l'appareil deja autorise dans adb devices.
.PARAMETER OutputDirectory
Dossier de sortie sur le PC (par defaut : dossier du script).
#>
[CmdletBinding()]
param([string]$AdbPath, [string]$Serial, [string]$OutputDirectory)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$failed = 0

function Invoke-AdbRead {
    param([string[]]$Arguments, [int]$TimeoutSeconds = 25)
    foreach ($argument in $Arguments) {
        if ($argument -match '[\s"\x00]') { throw "Argument ADB invalide : $argument" }
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
    $result = [pscustomobject]@{ ExitCode = -1; Output = ''; Error = ''; TimedOut = $false }
    try {
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $result.TimedOut = $true
            # Stop only this hung PC-side adb client, not the server or Android app.
            $process.Kill()
            [void]$process.WaitForExit(5000)
        }
        if ($stdout.Wait(5000)) { $result.Output = $stdout.Result }
        if ($stderr.Wait(5000)) { $result.Error = $stderr.Result }
        if ($process.HasExited) { $result.ExitCode = $process.ExitCode }
    } catch { $result.Error += $_.Exception.Message }
    finally { $process.Dispose() }
    return $result
}

function Save-Read {
    param([string]$Name, [string[]]$Arguments)
    $result = Invoke-AdbRead -Arguments (@('-s', $Serial) + $Arguments)
    $text = "PC time: $((Get-Date).ToString('o'))`r`nCommand: adb " + ($Arguments -join ' ') +
        "`r`nexit=$($result.ExitCode) timed_out=$($result.TimedOut)`r`n`r`n" + $result.Output
    if ($result.Error) { $text += "`r`nSTDERR:`r`n" + $result.Error }
    if ($result.ExitCode -ne 0 -or $result.TimedOut) {
        $script:failed++
        $text += "`r`nUNAVAILABLE/FAILED: missing output is not a negative capability finding."
    }
    [System.IO.File]::WriteAllText((Join-Path $folder $Name), $text, (New-Object System.Text.UTF8Encoding($false)))
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
    throw 'adb.exe introuvable. Placez ce script dans son dossier ou indiquez -AdbPath.'
}
$AdbPath = (Resolve-Path -LiteralPath $AdbPath).Path
$devices = Invoke-AdbRead -Arguments @('devices', '-l')
if ($devices.ExitCode -ne 0 -or $devices.TimedOut) { throw "ADB indisponible : $($devices.Error)" }
$authorized = @($devices.Output -split '\r?\n' | ForEach-Object {
    if ($_ -match '^(\S+)\s+device(?:\s|$)') { $Matches[1] }
})
if (-not $Serial) {
    if ($authorized.Count -ne 1) {
        throw "Autorisez un seul appareil ou indiquez -Serial. Rien collecte.`r`n$($devices.Output)"
    }
    $Serial = $authorized[0]
}
if ($Serial -notmatch '^[A-Za-z0-9_.:%\[\]-]+$' -or $authorized -cnotcontains $Serial) {
    throw '-Serial doit correspondre exactement a un appareil deja autorise dans adb devices.'
}
if (-not $OutputDirectory) { $OutputDirectory = $PSScriptRoot }
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
$name = 'NordicRower-diagnostics-{0}-{1}' -f (Get-Date -Format 'yyyyMMdd-HHmmss'), ([guid]::NewGuid().ToString('N').Substring(0, 8))
$folder = Join-Path $OutputDirectory $name
[void][System.IO.Directory]::CreateDirectory($folder)
$zip = Join-Path $OutputDirectory "$name.zip"

Write-Host 'Collecte en lecture seule. Les applications et les reglages ne seront pas modifies.'
try {
    foreach ($file in @('discovery.txt', 'status.txt', 'journal.txt', 'journal-1.txt', 'journal-2.txt')) {
        Save-Read -Name $file -Arguments @('shell', 'run-as', 'com.nordicrower.app', 'cat', "files/diagnostics/$file")
    }
    Save-Read -Name 'logcat.txt' -Arguments @('logcat', '-d', '-b', 'main', '-b', 'system', '-v', 'threadtime',
        '-t', '5000', 'NordicRower:V', 'BluetoothGattServer:V', 'BluetoothLeAdvertiser:V', 'AndroidRuntime:E', '*:S')
    Save-Read -Name 'crash.txt' -Arguments @('logcat', '-d', '-b', 'crash', '-v', 'threadtime', '-t', '200', '*:V')
    Save-Read -Name 'usb.txt' -Arguments @('shell', 'dumpsys', '-t', '10', 'usb')
    Save-Read -Name 'bluetooth.txt' -Arguments @('shell', 'dumpsys', '-t', '10', 'bluetooth_manager')
    Save-Read -Name 'package.txt' -Arguments @('shell', 'dumpsys', '-t', '10', 'package', 'com.nordicrower.app')
    Save-Read -Name 'service.txt' -Arguments @('shell', 'dumpsys', '-t', '10', 'activity', 'services', 'com.nordicrower.app')
    Save-Read -Name 'wolf-enabled.txt' -Arguments @('shell', 'pm', 'list', 'packages', '-e', 'com.ifit.standalone')
    Save-Read -Name 'android.txt' -Arguments @('shell', 'getprop', 'ro.build.fingerprint')
    Save-Read -Name 'console-time.txt' -Arguments @('shell', 'date')
} catch {
    $failed++
    [System.IO.File]::WriteAllText((Join-Path $folder 'collection-error.txt'), $_.Exception.ToString())
    Write-Warning 'Collecte incomplete ; le rapport partiel reste utile.'
}
$summary = @"
NordicRower diagnostic collector v1
PC time: $((Get-Date).ToString('o'))
Unavailable/failed reads: $failed (old rotated journals may legitimately not exist)
No Android settings/apps/logs/controller targets were changed. No automatic upload.
run-as requires the experimental debuggable APK. A run-as error is not a hardware capability finding.
discovery.txt: feature declarations, firmware identity, resistance limits, current targets/modes, errors.
journal*.txt: persistent app history across restarts (bounded to roughly 3 MiB).
status.txt: last saved session state, telemetry and BLE notification counters (not guaranteed current after a crash).
logcat/crash/dumps: recent system state, may contain incidental personal data from other apps/devices.
DECLARED_NOT_WRITE_TESTED: candidate feature, NOT proof of write acceptance or native ERG operation.
NOT_DECLARED: absent from a complete feature list; UNKNOWN: missing/incomplete reply, never assume unsupported.
Bluetooth notification completion does not prove EXR/RowerTrain interpreted the rower data.
Review this ZIP and send it privately with app name/version, test time, and what appeared on screen.
"@
[System.IO.File]::WriteAllText((Join-Path $folder 'collection.txt'), $summary, (New-Object System.Text.UTF8Encoding($false)))
Compress-Archive -LiteralPath $folder -DestinationPath $zip
Write-Host "ZIP cree : $zip"
Write-Host 'Examinez son contenu puis envoyez ce ZIP au support, meme si le diagnostic a echoue.'
