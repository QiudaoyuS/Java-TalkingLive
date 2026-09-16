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
echo  提示：Demo 会弹出两个窗口 ——
echo    * 「交互原型控制台」：用来触发各种场景（真实产品里没有这个窗口）
echo    * 深色浮动预览条：贴在「模拟光标处」输入框的下方，这是真实产品的界面
echo.
echo  请重点试一件事：浮窗弹出时，在下面那个白色输入框里打字，
echo  看光标会不会被抢走。抢走就是致命 bug。
echo.
echo  参数 --auto 可自动循环演示整段流程，不用手动点按钮。
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
