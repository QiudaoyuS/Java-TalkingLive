@echo off
chcp 65001 >nul
cd /d "%~dp0"

if not exist out mkdir out

echo.
echo [1/3] 编译 ...
javac -encoding UTF-8 -d out src\talkinglive\*.java
if errorlevel 1 goto :fail

echo.
echo [2/3] 运行自检 ...
java -cp out talkinglive.SelfTest
if errorlevel 1 (
  echo.
  echo ---- 自检报告 ----
  type selftest-report.txt
  goto :fail
)
echo.
type selftest-report.txt

echo.
echo [3/3] 启动 Demo ...
echo.
echo  启动后桌面上只会出现一颗「悬浮球」，没有主窗口。
echo.
echo    右键悬浮球  -^> 弹出菜单（暂停监听 / 设置... / 查看日志 / 退出）
echo    菜单「设置...」 -^> 打开设置窗口（常规 / 日志 / 演示）
echo    左键悬浮球  -^> 手动开始或结束听写
echo    拖动悬浮球  -^> 移动位置
echo.
echo  想快速看整段流程：加 --auto 会自动循环演示并打开「演示」页签。
echo  想直接看设置界面：加 --settings。
echo.
java -Dfile.encoding=UTF-8 -cp out talkinglive.Demo %*
exit /b 0

:fail
echo.
echo ********************************************
echo 构建或自检失败，已中止。
echo ********************************************
pause
exit /b 1
