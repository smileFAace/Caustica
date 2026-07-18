# Smoke-test Caustica: deploy → launch isolated client → watch log for crash / RT bring-up.
# Exit 0 = pass, 1 = fail, 2 = timeout.
param(
	[string]$HmclRoot = "D:\gms\HMCL-3.15.2",
	[int]$TimeoutSec = 120,
	[switch]$SkipDeploy,
	[switch]$SkipCompile
)

$ErrorActionPreference = "Stop"
$ProjectRoot = $PSScriptRoot
$McDir = Join-Path $HmclRoot ".minecraft"
$VersionId = "26.2-Fabric"
$VersionDir = Join-Path $McDir "versions\$VersionId"
$VersionJson = Join-Path $VersionDir "$VersionId.json"
$ClientJar = Join-Path $VersionDir "$VersionId.jar"
$Natives = Join-Path $VersionDir "natives-windows-x86_64"
$Java = Join-Path $env:USERPROFILE "AppData\Roaming\.hmcl\java\windows-x86_64\mojang-java-runtime-epsilon\bin\java.exe"
$SmokeDir = Join-Path $ProjectRoot "build\smoke-gamedir"
$LogPath = Join-Path $SmokeDir "logs\latest.log"
$CrashDir = Join-Path $SmokeDir "crash-reports"
$StdoutPath = Join-Path $ProjectRoot "build\smoke-stdout.txt"
$StderrPath = Join-Path $ProjectRoot "build\smoke-stderr.txt"

function Write-Step($msg) { Write-Host "==> $msg" }

if (-not (Test-Path $Java)) { throw "Java not found: $Java" }
if (-not (Test-Path $VersionJson)) { throw "Version json missing: $VersionJson" }
if (-not (Test-Path $ClientJar)) { throw "Client jar missing: $ClientJar" }

if (-not $SkipDeploy) {
	Write-Step "Deploy"
	$deployArgs = @()
	if ($SkipCompile) { $deployArgs += "-SkipCompile" }
	& (Join-Path $ProjectRoot "deploy-hmcl.ps1") @deployArgs
	if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw "deploy-hmcl.ps1 failed: $LASTEXITCODE" }
}

# Isolated game dir: share assets/libraries/mods/versions via junction/symlink where possible.
Write-Step "Prepare isolated gameDir $SmokeDir"
New-Item -ItemType Directory -Force -Path $SmokeDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $SmokeDir "logs") | Out-Null
foreach ($name in @("assets", "libraries", "versions", "mods")) {
	$link = Join-Path $SmokeDir $name
	$target = Join-Path $McDir $name
	if (Test-Path $link) { continue }
	if (-not (Test-Path $target)) { throw "Missing $target" }
	cmd /c mklink /J "`"$link`"" "`"$target`"" | Out-Null
}
# Fresh config dir so we don't fight a live session's toml lock
$configLink = Join-Path $SmokeDir "config"
if (-not (Test-Path $configLink)) {
	New-Item -ItemType Directory -Force -Path $configLink | Out-Null
	$srcToml = Join-Path $McDir "config\caustica.toml"
	if (Test-Path $srcToml) { Copy-Item $srcToml (Join-Path $configLink "caustica.toml") }
}

# Build classpath strictly from version JSON library names (avoid fat jars / duplicates).
Write-Step "Build classpath from $VersionId.json"
$version = Get-Content $VersionJson -Raw | ConvertFrom-Json
$cp = New-Object System.Collections.Generic.List[string]
foreach ($lib in $version.libraries) {
	$name = [string]$lib.name
	if (-not $name) { continue }
	# skip classifier natives entries if any
	if ($name -match ":natives") { continue }
	$parts = $name.Split(":")
	if ($parts.Length -lt 3) { continue }
	$group = $parts[0].Replace(".", "\")
	$artifact = $parts[1]
	$ver = $parts[2]
	$jarName = "$artifact-$ver.jar"
	$rel = Join-Path $group (Join-Path $artifact (Join-Path $ver $jarName))
	$path = Join-Path (Join-Path $McDir "libraries") $rel
	if (Test-Path $path) {
		$cp.Add($path)
	} else {
		Write-Host "WARN missing library jar: $path"
	}
}
$cp.Add($ClientJar)
$classpath = ($cp | Select-Object -Unique) -join ";"

$log4j = Join-Path $VersionDir "log4j2.xml"
if (-not (Test-Path $log4j)) { $log4j = "" }

$javaArgs = New-Object System.Collections.Generic.List[string]
$javaArgs.Add("-Xmx4G")
$javaArgs.Add("-Xss16m")
$javaArgs.Add("--enable-native-access=ALL-UNNAMED")
$javaArgs.Add("-Dfile.encoding=UTF-8")
$javaArgs.Add("-Djava.library.path=$Natives\java")
$javaArgs.Add("-Djna.tmpdir=$Natives\jna")
$javaArgs.Add("-Dorg.lwjgl.system.SharedLibraryExtractPath=$Natives\lwjgl")
$javaArgs.Add("-Dio.netty.native.workdir=$Natives\netty")
if ($log4j) { $javaArgs.Add("-Dlog4j.configurationFile=$log4j") }
$javaArgs.Add("-Dminecraft.client.jar=$ClientJar")
$javaArgs.Add("-cp"); $javaArgs.Add($classpath)
$javaArgs.Add("net.fabricmc.loader.impl.launch.knot.KnotClient")
$javaArgs.AddRange(@(
	"--username", "SmokeBot",
	"--version", $VersionId,
	"--gameDir", $SmokeDir,
	"--assetsDir", (Join-Path $McDir "assets"),
	"--assetIndex", "32",
	"--uuid", "00000000000000000000000000000000",
	"--accessToken", "0",
	"--width", "854",
	"--height", "480",
	"--graphicsBackend", "vulkan"
))

# Clear previous smoke log
if (Test-Path $LogPath) { Remove-Item -Force $LogPath }
$crashBefore = @()
if (Test-Path $CrashDir) {
	$crashBefore = @(Get-ChildItem $CrashDir -Filter "crash-*.txt" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name)
}

Write-Step "Launch KnotClient"
$proc = Start-Process -FilePath $Java -ArgumentList $javaArgs.ToArray() `
	-WorkingDirectory $SmokeDir `
	-RedirectStandardOutput $StdoutPath `
	-RedirectStandardError $StderrPath `
	-PassThru

Write-Host "PID=$($proc.Id) timeout=${TimeoutSec}s log=$LogPath"

$deadline = (Get-Date).AddSeconds($TimeoutSec)
$ok = $false
$fail = $false
$reason = ""
$logOffset = 0

function Get-NewLogText {
	if (-not (Test-Path $LogPath)) { return "" }
	try {
		$fs = [System.IO.File]::Open($LogPath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
		try {
			if ($fs.Length -le $script:logOffset) { return "" }
			$fs.Seek($script:logOffset, [System.IO.SeekOrigin]::Begin) | Out-Null
			$sr = New-Object System.IO.StreamReader($fs)
			return $sr.ReadToEnd()
		} finally { $fs.Close() }
	} catch { return "" }
}

while ((Get-Date) -lt $deadline) {
	Start-Sleep -Seconds 2
	if ($proc.HasExited) {
		# Read remaining log before deciding
		$chunk = Get-NewLogText
		if ($chunk -match "RT bring-up OK" -and $chunk -match "Caustica client initialized") {
			$ok = $true
			$reason = "process exited but RT bring-up already succeeded"
		} else {
			$fail = $true
			$reason = "process exited early code=$($proc.ExitCode)"
		}
		break
	}

	$chunk = Get-NewLogText
	if ($chunk) {
		$script:logOffset = (Get-Item $LogPath).Length
		if ($chunk -match "Mixin transformation of .* failed|Critical injection failure|Game crashed|duplicate ASM classes|Rendering backend .* failed") {
			$fail = $true
			$reason = "log matched crash pattern"
			break
		}
		if ($chunk -match "Caustica client initialized" -and $chunk -match "RT bring-up OK") {
			$ok = $true
			$reason = "Caustica client + RT bring-up OK"
			break
		}
	}

	if (Test-Path $CrashDir) {
		$crashAfter = @(Get-ChildItem $CrashDir -Filter "crash-*.txt" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name)
		$newCrashes = @($crashAfter | Where-Object { $_ -notin $crashBefore })
		if ($newCrashes.Count -gt 0) {
			$fail = $true
			$reason = "new crash report: $($newCrashes -join ', ')"
			break
		}
	}
}

if (-not $proc.HasExited) {
	Write-Step "Stopping PID $($proc.Id)"
	Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
	Get-CimInstance Win32_Process -Filter "ParentProcessId=$($proc.Id)" -ErrorAction SilentlyContinue |
		ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
	Start-Sleep -Seconds 1
}

$tail = ""
if (Test-Path $LogPath) {
	$tail = Get-Content $LogPath -Tail 50 -ErrorAction SilentlyContinue | Out-String
}

Write-Host "---- result ----"
if ($ok) {
	Write-Host "PASS: $reason"
	Write-Host $tail
	exit 0
}
if ($fail) {
	Write-Host "FAIL: $reason"
	Write-Host $tail
	if (Test-Path $StderrPath) {
		Write-Host "---- stderr (tail) ----"
		Get-Content $StderrPath -Tail 40
	}
	exit 1
}
Write-Host "TIMEOUT after ${TimeoutSec}s. Last log:"
Write-Host $tail
if (Test-Path $StderrPath) {
	Write-Host "---- stderr (tail) ----"
	Get-Content $StderrPath -Tail 40
}
exit 2
