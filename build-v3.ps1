param(
    [ValidateSet('linux', 'windows')]
    [string]$Os = 'linux',
    [string]$Arch = 'amd64'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$GoExe = $env:LOC_GO_EXE
if ([string]::IsNullOrWhiteSpace($GoExe)) {
    $GoCommand = Get-Command go -ErrorAction SilentlyContinue
    if ($GoCommand) {
        $GoExe = $GoCommand.Source
    }
}
if ([string]::IsNullOrWhiteSpace($GoExe)) {
    $StandardGo = Join-Path $env:ProgramFiles 'Go\bin\go.exe'
    if (Test-Path -LiteralPath $StandardGo -PathType Leaf) {
        $GoExe = $StandardGo
    }
}

if ([string]::IsNullOrWhiteSpace($GoExe) -or -not (Test-Path -LiteralPath $GoExe -PathType Leaf)) {
    throw 'Go toolchain not found. Put go on PATH or set LOC_GO_EXE to go.exe.'
}

Push-Location $Root
try {
    New-Item -ItemType Directory -Force -Path .\bin | Out-Null

    $binaryName = if ($Os -eq 'windows') {
        "family-location-go-$Os-$Arch.exe"
    } else {
        "family-location-go-$Os-$Arch"
    }

    $outputPath = Join-Path (Join-Path $Root 'bin') $binaryName
    & $GoExe test ./...
    if ($LASTEXITCODE -ne 0) {
        throw "go test failed with exit code $LASTEXITCODE"
    }

    $env:GOOS = $Os
    $env:GOARCH = $Arch
    & $GoExe build -buildvcs=false -trimpath -ldflags '-s -w' -o $outputPath .\cmd\server
    if ($LASTEXITCODE -ne 0) {
        throw "go build failed with exit code $LASTEXITCODE"
    }

    Write-Output "build-v3 OK: $outputPath"
} finally {
    Pop-Location
}
