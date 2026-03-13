@echo off
cd /d "%~dp0"
java --enable-native-access=ALL-UNNAMED -cp "Autoplay.jar;gson-2.10.1.jar;jna-5.13.0.jar;jna-platform-5.13.0.jar" Autoplay
pause
