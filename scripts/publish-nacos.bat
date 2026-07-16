@echo off
setlocal enabledelayedexpansion

set "WS=%~dp0.."
set "NACOS=http://38.76.188.242:8848"
set "NS=putao-dating-dev"

echo ========================================
echo Nacos 配置发布脚本
echo ========================================
echo.

echo [1/2] 获取 Access Token...
for /f "tokens=*" %%i in ('curl.exe -sS -X POST "%NACOS/nacos/v1/auth/login" -d "username=nacos&password=jianjiange"') do set "RESPONSE=%%i"
echo %RESPONSE%

echo.
echo [2/2] 发布配置到 %NS% namespace...

for %%f in (user-service.yaml post-service.yaml match-service.yaml im-service.yaml mobile-gateway.yaml payment-service.yaml) do (
    echo      发布 %%f...
    set "CONTENT="
    for /f "usebackq delims=" %%c in ("%WS%\nacos\%%f") do set "CONTENT=!CONTENT!%%c"$"EOL$"
    REM Note: PowerShell-based encoding below
)

echo.
echo 开始发布配置...
powershell.exe -NoProfile -Command "
$token = (Invoke-RestMethod -Uri '%NACOS/nacos/v1/auth/login' -Method Post -Body 'username=nacos&password=jianjiange').accessToken
$ns = '%NS%'
$configDir = '%WS%\nacos'
Get-ChildItem -Path $configDir -Filter '*.yaml' | ForEach-Object {
    $file = $_.FullName
    $dataId = $_.Name
    $content = Get-Content -Raw $file
    $body = \"dataId=$dataId&group=DEFAULT_GROUP&tenant=$ns&type=yaml&content=$([Uri]::EscapeDataString($content))\"
    $result = Invoke-RestMethod -Uri \"$NACOS/nacos/v1/cs/configs?accessToken=$token\" -Method Post -Body $body
    Write-Host \"  $dataId : $result\"
}
"

echo.
echo ========================================
echo Nacos 配置发布完成！
echo ========================================
pause
