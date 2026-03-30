# From repo root:
#   .\scripts\run.ps1              — run the app (needs: mvn clean package)
#   .\scripts\run.ps1 -BuildExe    — build portable .exe (runs Maven jpackage)

param(
    [switch] $BuildExe
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $root

if ($BuildExe) {
    mvn -Pdist clean package
    $exe = Get-ChildItem -Path (Join-Path $root "target\dist") -Filter "*.exe" -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $exe) {
        Write-Error "Build finished but no .exe under target\dist. Check Maven output."
    }
    Write-Host ""
    Write-Host "Executable:" $exe.FullName
    Invoke-Item (Split-Path -Parent $exe.FullName)
    exit 0
}

$target = Join-Path $root "target"
$jar = Get-ChildItem -Path $target -Filter "xml-compare-desktop-*.jar" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch "sources|javadoc" } |
    Select-Object -First 1

if (-not $jar) {
    Write-Error "No JAR in target\. Run: mvn clean package"
}

$lib = Join-Path $target "lib"
if (-not (Test-Path $lib)) {
    Write-Error "No target\lib. Run: mvn clean package"
}

Set-Location $target
& java --module-path "lib" --add-modules javafx.controls -cp $jar.Name com.xmlcompare.app.XmlCompareApp
