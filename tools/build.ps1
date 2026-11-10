# my-pycharm 构建脚本：javac 直编（PyCharm 自带 JBR javac 25）+ python 打包（无需 Gradle/网络）
# 用法：pwsh tools\build.ps1 [-SkipTest]
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$ide = 'D:\programs\PyCharm 2026.3'
$javac = "$ide\jbr\bin\javac.exe"   # 平台类是 Java 25（class 69），必须用 JDK 25 编译；直接用 PyCharm 自带 JBR 的 javac
$cp = "$ide\lib\*;$ide\plugins\platform-vcs-plugin\lib\modules\*"
$classes = "$root\build\classes"

New-Item -ItemType Directory -Force $classes | Out-Null
Remove-Item "$classes\*" -Recurse -Force -ErrorAction SilentlyContinue   # 清理陈旧 class（源码删改后残留会混进产物）
$sources = Get-ChildItem "$root\src\main\java" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sourcesFile = "$root\build\sources.txt"
$sources | Set-Content -Encoding utf8 $sourcesFile

Write-Host "== javac ($($sources.Count) files)"
# 注意：本机执行环境会把含 '=' 的参数从 '=' 处切开（-J-Dxxx=yyy 会碎），所以 javac 参数文件里不放 '='，
# 错误输出经 cmd 重定向到文件后按 GBK 解码阅读。
& $javac -encoding UTF-8 --release 25 -nowarn -cp $cp -d $classes "@$sourcesFile" 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    $errFile = "$root\build\err.raw"
    cmd /c "tools\javac.cmd 2>$errFile"
    $gbk = [System.Text.Encoding]::GetEncoding(936)
    [System.IO.File]::ReadAllText($errFile, $gbk) | Write-Host
    throw "javac failed: $LASTEXITCODE"
}

if (!$SkipTest) {
    Write-Host "== PureSelfTest"
    # 用 JBR 的 java 跑（PATH 上的 java 是 21，读不了 Java 25 类）；输出落盘后按 GBK 解码，避免控制台乱码
    $testRaw = "$root\build\test.raw"
    cmd /c "`"$ide\jbr\bin\java.exe`" -cp `"$classes`" local.autoclaw.mypycharm.PureSelfTest > `"$testRaw`" 2>&1"
    $gbk = [System.Text.Encoding]::GetEncoding(936)
    [System.IO.File]::ReadAllText($testRaw, $gbk) | Write-Host
    if ($LASTEXITCODE -ne 0) { throw "PureSelfTest failed: $LASTEXITCODE" }
}

Write-Host "== package"
& python "$PSScriptRoot\package.py" $root $classes
if ($LASTEXITCODE -ne 0) { throw "package failed: $LASTEXITCODE" }

Write-Host "== done"
