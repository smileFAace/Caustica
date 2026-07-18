# Build Caustica and install into the HMCL instance (no DLSS_SDK required if natives
# can be reused from an existing/backup jar).
param(
	[string]$HmclRoot = "D:\gms\HMCL-3.15.2",
	[switch]$SkipCompile
)

$ErrorActionPreference = "Stop"
$ProjectRoot = $PSScriptRoot
$ModsDir = Join-Path $HmclRoot ".minecraft\mods"
$DstJar = Join-Path $ModsDir "caustica-0.1.0.jar"
$SrcJar = Join-Path $ProjectRoot "build\libs\caustica-0.1.0.jar"
$NativesGen = Join-Path $ProjectRoot "build\generated\ngx-natives"
$ShimDll = Join-Path $NativesGen "caustica\natives\windows-x64\ngxshim.dll"

function Find-Java25Home {
	$candidates = @(
		"$env:USERPROFILE\AppData\Roaming\.hmcl\java\windows-x86_64\mojang-java-runtime-epsilon",
		$env:JAVA_HOME
	) | Where-Object { $_ -and (Test-Path $_) }
	foreach ($c in $candidates) {
		$java = Join-Path $c "bin\java.exe"
		if (-not (Test-Path $java)) { continue }
		# Prefer path heuristic: HMCL's Mojang Java 25 runtime folder name.
		if ($c -match 'mojang-java-runtime-epsilon|jdk-25|java-25|jdk25') { return $c }
		$prev = $ErrorActionPreference
		$ErrorActionPreference = 'Continue'
		try {
			$ver = cmd /c "`"$java`" -version 2>&1"
			if ("$ver" -match 'version "25') { return $c }
		} finally {
			$ErrorActionPreference = $prev
		}
	}
	# Last resort: if the only candidate exists and is under .hmcl, use it.
	foreach ($c in $candidates) {
		if ($c -match '\\.hmcl\\java\\') { return $c }
	}
	throw "Java 25 not found. Set JAVA_HOME to a JDK 25 install (HMCL ships one under AppData\Roaming\.hmcl\java\...)."
}

Push-Location $ProjectRoot
try {
	if (-not $env:VULKAN_SDK) {
		if (Test-Path "E:\tools\develop\Vulkan SDK") {
			$env:VULKAN_SDK = "E:\tools\develop\Vulkan SDK"
		}
	}

	$env:JAVA_HOME = Find-Java25Home
	$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
	Write-Host "JAVA_HOME=$env:JAVA_HOME"
	Write-Host "VULKAN_SDK=$env:VULKAN_SDK"

	if (-not (Test-Path $ShimDll)) {
		$donor = $null
		if (Test-Path $DstJar) { $donor = $DstJar }
		$bak = Get-ChildItem $ModsDir -Filter "caustica-0.1.0.jar.bak*" -ErrorAction SilentlyContinue |
			Sort-Object LastWriteTime -Descending | Select-Object -First 1
		if ($bak) { $donor = $bak.FullName }
		if (-not $donor) {
			throw "No NGX natives available. Set DLSS_SDK and build once, or keep a backup jar with natives."
		}
		New-Item -ItemType Directory -Force -Path $NativesGen | Out-Null
		Push-Location $NativesGen
		try {
			& jar xf $donor caustica/natives
		} finally {
			Pop-Location
		}
		Write-Host "Extracted natives from $donor"
	}

	if (-not $SkipCompile) {
		& .\gradlew.bat generateShaderRecords compileJava compileShaders processResources jar -x bundleNgxNatives
		if ($LASTEXITCODE -ne 0) { throw "gradle build failed: $LASTEXITCODE" }
	}

	if (-not (Test-Path $SrcJar)) { throw "Missing build output: $SrcJar" }
	if (-not (Test-Path $ModsDir)) { throw "Missing mods dir: $ModsDir" }

	if (Test-Path $DstJar) {
		$bakPath = Join-Path $ModsDir ("caustica-0.1.0.jar.bak-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
		Copy-Item -Force $DstJar $bakPath
		Write-Host "Backup: $bakPath"
	}

	Copy-Item -Force $SrcJar $DstJar
	Write-Host "Installed: $DstJar"
	Write-Host "Done. Restart Minecraft from HMCL (26.2-Fabric, Vulkan)."
} finally {
	Pop-Location
}
