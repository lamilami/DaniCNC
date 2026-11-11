param([string]$JdkHome = $env:JAVA_HOME)

$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT' -or -not [Environment]::Is64BitProcess) {
    throw 'Build this package on Windows x64 with 64-bit PowerShell and a 64-bit JDK.'
}
if (-not $JdkHome -or -not (Test-Path (Join-Path $JdkHome 'bin/jpackage.exe'))) {
    throw 'Set JAVA_HOME or pass -JdkHome pointing to a full Windows x64 JDK (23 or newer).'
}
$projectDir = Split-Path $PSScriptRoot -Parent
Push-Location $projectDir
try {
    $env:JAVA_HOME = $JdkHome
    [xml]$pom = Get-Content -LiteralPath 'pom.xml'
    $version = [string]$pom.project.version
    $artifact = [string]$pom.project.artifactId
    $nativeVersion = ($version -split '-')[0]
    if ($nativeVersion -notmatch '^\d+(\.\d+){0,2}$') {
        throw ('Version cannot be represented as a Windows application version: ' + $version)
    }
    # Every build uses a fresh workspace; existing packages and user files are never deleted.
    $work = Join-Path $projectDir ('target/windows-package-' + [Guid]::NewGuid().ToString('N'))
    $inputDir = Join-Path $work 'input'
    $imageDir = Join-Path $work 'image'
    New-Item -ItemType Directory -Path $inputDir -Force | Out-Null
    & '.\mvnw.cmd' '-B' '-q' 'package' 'dependency:copy-dependencies' '-DincludeScope=runtime' ('-DoutputDirectory=' + $inputDir)
    if ($LASTEXITCODE -ne 0) { throw 'Maven build or tests failed.' }
    $jarName = "$artifact-$version.jar"
    Copy-Item -LiteralPath (Join-Path 'target' $jarName) -Destination $inputDir
    & (Join-Path $JdkHome 'bin/jpackage.exe') '--type' 'app-image' '--name' 'SimpleCAM' `
        '--app-version' $nativeVersion '--description' 'SimpleCAM 1830 - Simple 2D CAM for CNC hobbyists.' `
        '--input' $inputDir '--dest' $imageDir '--main-jar' $jarName `
        '--main-class' 'org.example.danicnc.LauncherKt' `
        '--add-modules' 'java.se,jdk.unsupported,jdk.crypto.ec' `
        '--java-options' '-Dfile.encoding=UTF-8'
    if ($LASTEXITCODE -ne 0) { throw 'jpackage failed.' }
    $appDir = Join-Path $imageDir 'SimpleCAM'
    Copy-Item -LiteralPath 'src/main/resources/org/example/danicnc/THIRD-PARTY-NOTICES.txt' -Destination $appDir
    Copy-Item -LiteralPath 'packaging/windows/START-HERE.txt' -Destination $appDir
    Copy-Item -LiteralPath 'samples' -Destination $appDir -Recurse
    $dist = Join-Path $projectDir 'target/dist'
    New-Item -ItemType Directory -Path $dist -Force | Out-Null
    $archive = Join-Path $dist "SimpleCAM-1830-$version-Windows-x64.zip"
    Compress-Archive -LiteralPath $appDir -DestinationPath $archive -CompressionLevel Optimal -Force
    $hash = Get-FileHash -LiteralPath $archive -Algorithm SHA256
    ($hash.Hash.ToLowerInvariant() + '  ' + (Split-Path $archive -Leaf)) |
        Set-Content -LiteralPath ($archive + '.sha256') -Encoding ASCII
    Write-Output ('APP_IMAGE=' + $appDir)
    Write-Output ('ZIP=' + $archive)
    Write-Output ('SHA256=' + $hash.Hash)
} finally {
    Pop-Location
}
