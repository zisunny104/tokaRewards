param([string]$Maven='mvn',[string]$Repository=(Join-Path (Split-Path $PSScriptRoot -Parent) 'plugin-modernization/build/m2'))
$ErrorActionPreference='Stop'
Push-Location $PSScriptRoot
try {
  & $Maven "-Dmaven.repo.local=$Repository" clean install
  if($LASTEXITCODE -ne 0){throw '編譯或測試失敗'}
  $pom=[xml](Get-Content -Raw pom.xml);$name=$pom.project.artifactId;$version=$pom.project.version
  New-Item -ItemType Directory -Force output | Out-Null
  Copy-Item "target/$name-$version.jar" output -Force
  $central=Join-Path (Split-Path $PSScriptRoot -Parent) 'plugin-modernization/output'
  New-Item -ItemType Directory -Force $central | Out-Null
  Copy-Item "target/$name-$version.jar" $central -Force
  Get-FileHash "output/$name-$version.jar"
}finally{Pop-Location}
