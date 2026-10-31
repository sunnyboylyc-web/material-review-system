param([string]$Python='', [int]$McpPort=24573, [int]$SourcePort=24571, [switch]$SkipCompile)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (!$Python) { $Python=Join-Path $projectRoot '.local/venvs/mcp-d247/Scripts/python.exe' }
if (!(Test-Path -LiteralPath $Python)) { throw 'Create a dedicated Python environment and install tools/mcp_api/requirements.txt first.' }
foreach ($port in @($McpPort,$SourcePort)) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "Port $port is already in use; no process will be stopped." }
}
$javaDir=Join-Path $projectRoot '.local/toolchains/jdk-21.0.12.1+1'
$maven=Join-Path $projectRoot '.local/toolchains/apache-maven-3.9.16/bin/mvn.cmd'
$area=Join-Path $projectRoot '.local/d247-mcp'
$null=New-Item -ItemType Directory -Force -Path $area
Push-Location $projectRoot
try {
    if (!$SkipCompile) {
        $env:JAVA_HOME=$javaDir
        & $maven -o -B -ntp -C "-Dmaven.repo.local=$projectRoot/.local/maven-repository" -f backend/pom.xml test-compile -DskipTests
        if ($LASTEXITCODE -ne 0) { throw 'SYNTHETIC_CALCULATOR_COMPILE_FAILED' }
    }
    $paths=@('backend/target/classes','backend/target/test-classes',
        '.local/maven-repository/tools/jackson/core/jackson-databind/3.1.4/jackson-databind-3.1.4.jar',
        '.local/maven-repository/tools/jackson/core/jackson-core/3.1.4/jackson-core-3.1.4.jar',
        '.local/maven-repository/com/fasterxml/jackson/core/jackson-annotations/2.21/jackson-annotations-2.21.jar') | ForEach-Object { Join-Path $projectRoot $_ }
    foreach ($path in $paths) { if (!(Test-Path -LiteralPath $path)) { throw "Missing demo build dependency: $path" } }
    $java=Start-Process -FilePath (Join-Path $javaDir 'bin/java.exe') -ArgumentList @('-Dfile.encoding=UTF-8','-cp',('"'+($paths -join ';')+'"'),'cn.datadashboard.calculation.McpDashboardDemoMain',"$SourcePort") -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $area 'demo-source.log') -RedirectStandardError (Join-Path $area 'demo-source-error.log')
    $mcp=Start-Process -FilePath $Python -ArgumentList @('-B','-m','tools.mcp_api.server','--port',"$McpPort",'--source',"http://127.0.0.1:$SourcePort",'--synthetic-source') -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $area 'demo-mcp.log') -RedirectStandardError (Join-Path $area 'demo-mcp-error.log')
    @{sourcePid=$java.Id;mcpPid=$mcp.Id;endpoint="http://127.0.0.1:$McpPort/mcp";dataMode='SYNTHETIC';databaseStarted=$false} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $area 'demo-processes.json') -Encoding utf8
    Write-Output "Synthetic-only MCP: http://127.0.0.1:$McpPort/mcp"
} finally { Pop-Location }
