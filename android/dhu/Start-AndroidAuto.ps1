param([ValidateSet('ev6-wide', 'standard-landscape')][string]$Screen = 'ev6-wide')

$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$headUnit = Join-Path $PSScriptRoot '../../build/android-auto-dhu/desktop-head-unit.exe'
if (-not (Test-Path -LiteralPath $adb) -or -not (Test-Path -LiteralPath $headUnit)) {
    throw 'Android platform-tools or Google Desktop Head Unit is missing.'
}

$device = & $adb -d get-serialno
if ($LASTEXITCODE -ne 0) { throw 'Connect one Android phone by USB and authorize USB debugging.' }

$port = & $adb -s $device forward 'tcp:0' 'tcp:5277'
if ($LASTEXITCODE -ne 0 -or $port -notmatch '^\d+$') {
    throw 'Could not connect to the Android Auto head unit server.'
}

Push-Location -LiteralPath $PSScriptRoot
try {
    & $headUnit "--adb=$port" -c "$Screen.ini"
} finally {
    Pop-Location
    & $adb -s $device forward --remove "tcp:$port" | Out-Null
}
