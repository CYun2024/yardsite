@echo off
rem 投影解析自检（Windows）。生成构造好的 .litematic/.nbt/.schem，再逐项断言。
setlocal
cd /d "%~dp0"
if not exist "out\classes" mkdir "out\classes"

python gen.py || goto :fail
dir /s /b "..\..\src\main\java\dev\yard\site\schematic\*.java" > "out\sources.txt"
javac -encoding UTF-8 -d "out\classes" @"out\sources.txt" Check.java || goto :fail
java -Dstdout.encoding=UTF-8 -cp "out\classes" check.Check "out" || goto :fail
exit /b 0

:fail
echo 自检失败
exit /b 1
