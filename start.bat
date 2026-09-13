@echo off
rem Starts the whole TechCup Futbol stack (PostgreSQL, MongoDB, backend, frontend) with Docker.
rem Double-click this file, or run it from a terminal in the project folder.
setlocal
cd /d "%~dp0"
title TechCup Futbol

where docker >nul 2>&1
if errorlevel 1 goto no_docker

docker info >nul 2>&1
if errorlevel 1 goto docker_stopped

echo.
echo Building and starting TechCup Futbol.
echo The first run downloads images and dependencies and can take several minutes.
echo.
docker compose --profile app up -d --build
if errorlevel 1 goto compose_failed

echo.
echo Waiting for the backend to be ready...
set /a tries=0
:wait_backend
curl -sf http://localhost:8080/actuator/health >nul 2>&1
if not errorlevel 1 goto ready
set /a tries+=1
if %tries% geq 90 goto not_ready
rem ping is used as a sleep because "timeout" fails when input is redirected.
ping -n 3 127.0.0.1 >nul
goto wait_backend

:ready
echo.
echo ============================================================
echo  TechCup Futbol is running
echo.
echo  Web app:  http://localhost:5173
echo  API:      http://localhost:8080/api
echo  Swagger:  http://localhost:8080/swagger-ui.html
echo.
echo  Administrator: admin@escuelaing.edu.co / Admin123*
echo.
echo  To stop it:  docker compose --profile app down
echo ============================================================
echo.
start "" http://localhost:5173
pause
exit /b 0

:no_docker
echo.
echo Docker is not installed or not on the PATH.
echo Install Docker Desktop from https://www.docker.com/products/docker-desktop/ and try again.
echo.
pause
exit /b 1

:docker_stopped
echo.
echo Docker is installed but not running.
echo Open Docker Desktop, wait until it says it is running, and try again.
echo.
pause
exit /b 1

:compose_failed
echo.
echo Docker Compose could not build or start the services. See the messages above.
echo A common cause is a port already in use: 5173, 8080, 5433 or 27018.
echo.
pause
exit /b 1

:not_ready
echo.
echo The containers started but the backend did not answer within 3 minutes.
echo Check its logs with:  docker compose logs backend
echo.
pause
exit /b 1
