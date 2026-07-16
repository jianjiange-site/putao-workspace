@echo off
setlocal enabledelayedexpansion

set "WS=%~dp0.."

echo ========================================
echo Putao Services 启动脚本
echo ========================================
echo.
echo 选择要启动的服务:
echo   1. user-service
echo   2. post-service
echo   3. match-service
echo   4. im-service
echo   5. mobile-gateway
echo   6. payment-service
echo   7. 启动所有服务
echo   0. 退出
echo.
set /p choice="请输入选项: "

set "SPRING_PROFILES_ACTIVE=dev"
set "NACOS_SERVER_ADDR=38.76.188.242:8848"
set "NACOS_NAMESPACE=putao-dating-dev"
set "NACOS_USERNAME=nacos"
set "NACOS_PASSWORD=jianjiange"

if "%choice%"=="1" goto user-service
if "%choice%"=="2" goto post-service
if "%choice%"=="3" goto match-service
if "%choice%"=="4" goto im-service
if "%choice%"=="5" goto mobile-gateway
if "%choice%"=="6" goto payment-service
if "%choice%"=="7" goto all
goto end

:user-service
echo 启动 user-service...
cd /d "%WS%\dating-server\user-service"
mvn spring-boot:run -Dspring-boot.run.profiles=%SPRING_PROFILES_ACTIVE%
goto end

:post-service
echo 启动 post-service...
cd /d "%WS%\dating-server\post-service"
mvn spring-boot:run -Dspring-boot.run.profiles=%SPRING_PROFILES_ACTIVE%
goto end

:match-service
echo 启动 match-service...
cd /d "%WS%\dating-server\match-service"
mvn spring-boot:run -Dspring-boot.run.profiles=%SPRING_PROFILES_ACTIVE%
goto end

:im-service
echo 启动 im-service...
cd /d "%WS%\dating-server\im-service"
mvn spring-boot:run -Dspring-boot.run.profiles=%SPRING_PROFILES_ACTIVE%
goto end

:mobile-gateway
echo 启动 mobile-gateway...
cd /d "%WS%\dating-server\mobile-gateway"
mvn spring-boot:run -Dspring-boot.run.profiles=%SPRING_PROFILES_ACTIVE%
goto end

:payment-service
echo 启动 payment-service...
cd /d "%WS%\dating-server\payment-service"
mvn spring-boot:run -Dspring-boot.run.profiles=%SPRING_PROFILES_ACTIVE%
goto end

:all
echo 启动所有服务(请在多个终端中分别运行以下命令)...
echo.
echo 在终端 1: cd %WS%\dating-server\user-service ^&^& mvn spring-boot:run -Dspring-boot.run.profiles=%SPRING_PROFILES_ACTIVE%
echo 在终端 2: cd %WS%\dating-server\post-service ^&^& mvn spring-boot:run -Dspring-boot.run.profiles=%SPRING_PROFILES_ACTIVE%
echo 在终端 3: cd %WS%\dating-server\match-service ^&^& mvn spring-boot:run -Dspring-boot.run.profiles=%SPRING_PROFILES_ACTIVE%
echo 在终端 4: cd %WS%\dating-server\im-service ^&^& mvn spring-boot:run -Dspring-boot.run.profiles=%SPRING_PROFILES_ACTIVE%
echo 在终端 5: cd %WS%\dating-server\mobile-gateway ^&^& mvn spring-boot:run -Dspring-boot.run.profiles=%SPRING_PROFILES_ACTIVE%
echo 在终端 6: cd %WS%\dating-server\payment-service ^&^& mvn spring-boot:run -Dspring-boot.run.profiles=%SPRING_PROFILES_ACTIVE%
echo.
pause

:end
