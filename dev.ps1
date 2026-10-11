param(
    [ValidateSet('start', 'stop', 'status', 'build')][string]$Action = 'start',
    [string]$JavaDirectory = '',
    [string]$NodeDirectory = 'D:\programs\scoop\persist\nvm\nodejs\nodejs',
    [ValidateRange(1, 65535)][int]$ApiPort = 8080,
    [ValidateRange(1, 65535)][int]$WebPort = 5173,
    [ValidateRange(1, 65535)][int]$BrowserPort = 8090,
    [switch]$SkipBuild,
    [switch]$Verification
)
$ErrorActionPreference = 'Stop'
$workspaceDirectory = $PSScriptRoot
$logDirectory = Join-Path $workspaceDirectory 'logs'
$stateFile = Join-Path $logDirectory 'dev-processes.json'
$jarFile = Join-Path $workspaceDirectory 'build\backend\libs\reader.jar'
$viteFile = Join-Path $workspaceDirectory 'web\node_modules\vite\bin\vite.js'
$browserFile = Join-Path $workspaceDirectory 'browser\server.mjs'
$processWorkingDirectory = $workspaceDirectory
if ($Verification) {
    $processWorkingDirectory = Join-Path $workspaceDirectory 'build\gap-live'
    $logDirectory = Join-Path $processWorkingDirectory 'logs'
    $stateFile = Join-Path $logDirectory 'dev-processes.json'
    if ($Action -eq 'start') {
        if (-not $SkipBuild) { throw '隔离联测使用已构建产物，请指定 -SkipBuild。' }
        New-Item -ItemType Directory -Force -Path $processWorkingDirectory | Out-Null
        $verificationJar = Join-Path $processWorkingDirectory 'reader-api.jar'
        if (-not (Get-NetTCPConnection -State Listen -LocalPort $ApiPort -ErrorAction SilentlyContinue)) {
            Copy-Item -LiteralPath $jarFile -Destination $verificationJar
        }
        $jarFile = $verificationJar
    }
}

function Get-OwnedProcess($entry) {
    if (-not $entry) { return $null }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($entry.id)" -ErrorAction SilentlyContinue
    if ($process -and $process.CommandLine -and $process.CommandLine.Contains($entry.marker)) { return $process }
    return $null
}

$processState = if (Test-Path -LiteralPath $stateFile) { Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json } else { $null }
if ($Action -eq 'status') {
    foreach ($service in @('api', 'web', 'browser')) {
        $entry = if ($processState) { $processState.$service } else { $null }
        $process = Get-OwnedProcess $entry
        if ($process) { Write-Output "$service PID=$($process.ProcessId) $($entry.url)" } else { Write-Output "$service stopped" }
    }
    exit 0
}
if ($Action -eq 'stop') {
    foreach ($service in @('web', 'api', 'browser')) {
        $entry = if ($processState) { $processState.$service } else { $null }
        $process = Get-OwnedProcess $entry
        if ($process) { Stop-Process -Id $process.ProcessId; Write-Output "Stopped $service PID=$($process.ProcessId)" }
    }
    if (Test-Path -LiteralPath $stateFile) { Remove-Item -LiteralPath $stateFile }
    exit 0
}
if ($Action -eq 'start' -and $processState) {
    $runningServices = @(@('api', 'web', 'browser') | Where-Object { Get-OwnedProcess $processState.$_ })
    if ($runningServices.Count -eq 3) {
        Write-Output "React: $($processState.web.url)/"
        Write-Output "API:   $($processState.api.url)/"
        exit 0
    }
    if ($runningServices.Count) { throw '已有部分服务运行，请先执行 .\dev.ps1 stop 再启动，以免覆盖运行中的 JAR。' }
}

if (-not $JavaDirectory) {
    $javaCandidates = @($env:JAVA_HOME, (Join-Path $env:USERPROFILE '.jdks\ms-21'))
    foreach ($candidate in $javaCandidates) {
        if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'bin\java.exe'))) {
            $releaseFile = Join-Path $candidate 'release'
            if ((Test-Path -LiteralPath $releaseFile) -and (Get-Content -LiteralPath $releaseFile -Raw) -match 'JAVA_VERSION="(2[1-9]|[3-9][0-9])\.') { $JavaDirectory = $candidate; break }
        }
    }
}
if (-not $JavaDirectory) { throw '需要 Java 21 JDK；通过 -JavaDirectory 指定。' }
$javaExe = Join-Path $JavaDirectory 'bin\java.exe'
$nodeExe = Join-Path $NodeDirectory 'node.exe'
$npmFile = Join-Path $NodeDirectory 'node_modules\npm\bin\npm-cli.js'
if (-not (Test-Path -LiteralPath $javaExe)) { throw '需要 Java 21 JDK；通过 -JavaDirectory 指定。' }
if (-not (Test-Path -LiteralPath $nodeExe) -or -not (Test-Path -LiteralPath $npmFile)) { throw '未找到 nvm Node/npm；通过 -NodeDirectory 指定。' }

if (-not $SkipBuild -or $Action -eq 'build') {
    $savedJavaDirectory = $env:JAVA_HOME
    $savedPath = $env:PATH
    Push-Location $workspaceDirectory
    try {
        $env:JAVA_HOME = $JavaDirectory
        $env:PATH = "$NodeDirectory;$JavaDirectory\bin;$savedPath"
        & .\gradlew.bat test bootJar
        if ($LASTEXITCODE -ne 0) { throw '后端构建失败。' }
        Push-Location (Join-Path $workspaceDirectory 'web')
        try {
            & $nodeExe $npmFile ci
            if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败。' }
            foreach ($task in @('lint', 'typecheck', 'test', 'build')) {
                & $nodeExe $npmFile run $task
                if ($LASTEXITCODE -ne 0) { throw "前端 $task 失败。" }
            }
        } finally { Pop-Location }
    } finally {
        $env:JAVA_HOME = $savedJavaDirectory
        $env:PATH = $savedPath
        Pop-Location
    }
}
if (-not $SkipBuild -or $Action -eq 'build') {
    Push-Location (Join-Path $workspaceDirectory 'browser')
    try {
        & $nodeExe $npmFile ci
        if ($LASTEXITCODE -ne 0) { throw '浏览器依赖安装失败。' }
        & $nodeExe $npmFile test
        if ($LASTEXITCODE -ne 0) { throw '浏览器回归失败。' }
    } finally { Pop-Location }
}
if ($Action -eq 'build') { exit 0 }
if (-not (Test-Path -LiteralPath $jarFile) -or -not (Test-Path -LiteralPath $viteFile)) { throw '缺少构建产物，请先运行 .\dev.ps1 build。' }
foreach ($port in @($ApiPort, $WebPort, $BrowserPort)) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "端口 $port 已被占用；先停止已有服务。" }
}
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$apiProcess = $null
$webProcess = $null
$browserProcess = $null
try {
    $savedBrowserToken = $env:READER_BROWSER_TOKEN
    $savedAppBrowserToken = $env:READER_APP_BROWSERTOKEN
    $savedBrowserPort = $env:READER_BROWSER_PORT
    $savedBrowserUrl = $env:READER_APP_BROWSERURL
    try {
        $tokenBytes = New-Object byte[] 32
        [System.Security.Cryptography.RandomNumberGenerator]::Fill($tokenBytes)
        $env:READER_BROWSER_TOKEN = [Convert]::ToHexString($tokenBytes)
        $env:READER_APP_BROWSERTOKEN = $env:READER_BROWSER_TOKEN
        $env:READER_BROWSER_PORT = "$BrowserPort"
        $env:READER_APP_BROWSERURL = "http://127.0.0.1:$BrowserPort"
        $browserProcess = Start-Process -FilePath $nodeExe -ArgumentList @("`"$browserFile`"") -WorkingDirectory (Join-Path $workspaceDirectory 'browser') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logDirectory 'browser.stdout.log') -RedirectStandardError (Join-Path $logDirectory 'browser.stderr.log')
        $apiProcess = Start-Process -FilePath $javaExe -ArgumentList @('-jar', "`"$jarFile`"", "--reader.server.port=$ApiPort") -WorkingDirectory $processWorkingDirectory -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logDirectory 'api.stdout.log') -RedirectStandardError (Join-Path $logDirectory 'api.stderr.log')
    } finally {
        $env:READER_BROWSER_TOKEN = $savedBrowserToken
        $env:READER_APP_BROWSERTOKEN = $savedAppBrowserToken
        $env:READER_BROWSER_PORT = $savedBrowserPort
        $env:READER_APP_BROWSERURL = $savedBrowserUrl
    }
    $savedApiProxy = $env:READER_API_PROXY
    $savedViteCi = $env:CI
    try {
        $env:READER_API_PROXY = "http://127.0.0.1:$ApiPort"
        # Vite otherwise exits when the launching shell closes its input pipe.
        $env:CI = 'true'
        $webProcess = Start-Process -FilePath $nodeExe -ArgumentList @("`"$viteFile`"", '--host', '0.0.0.0', '--port', "$WebPort", '--strictPort') -WorkingDirectory (Join-Path $workspaceDirectory 'web') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logDirectory 'web.stdout.log') -RedirectStandardError (Join-Path $logDirectory 'web.stderr.log')
    } finally { $env:READER_API_PROXY = $savedApiProxy; $env:CI = $savedViteCi }
    $newState = @{
        browser = @{ id = $browserProcess.Id; marker = $browserFile; url = "http://127.0.0.1:$BrowserPort" }
        api = @{ id = $apiProcess.Id; marker = $jarFile; url = "http://localhost:$ApiPort" }
        web = @{ id = $webProcess.Id; marker = $viteFile; url = "http://localhost:$WebPort" }
    }
    $newState | ConvertTo-Json | Set-Content -LiteralPath $stateFile -Encoding UTF8
    foreach ($url in @("http://127.0.0.1:$ApiPort/health", "http://127.0.0.1:$WebPort/", "http://127.0.0.1:$BrowserPort/health")) {
        $ready = $false
        for ($attempt = 0; $attempt -lt 60; $attempt++) {
            if ($apiProcess.HasExited -or $webProcess.HasExited -or $browserProcess.HasExited) { throw "服务提前退出，请查看 $logDirectory。" }
            try { $response = Invoke-WebRequest -Uri $url -TimeoutSec 5 -UseBasicParsing; $ready = $response.StatusCode -eq 200 } catch { }
            if ($ready) { break }
            Start-Sleep -Milliseconds 500
        }
        if (-not $ready) { throw "服务启动超时：$url，请查看 $logDirectory。" }
    }
    Write-Output "React: http://localhost:$WebPort/"
    Write-Output "API:   http://localhost:$ApiPort/"
    $verificationOption = if ($Verification) { ' -Verification' } else { '' }
    Write-Output "停止：.\dev.ps1 stop$verificationOption；状态：.\dev.ps1 status$verificationOption"
} catch {
    foreach ($process in @($webProcess, $apiProcess, $browserProcess)) {
        if ($process -and -not $process.HasExited) { Stop-Process -Id $process.Id -ErrorAction SilentlyContinue }
    }
    if (Test-Path -LiteralPath $stateFile) { Remove-Item -LiteralPath $stateFile }
    throw
}
