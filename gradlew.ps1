#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Gradle startup script for Windows PowerShell.
.DESCRIPTION
    PowerShell port of the standard Gradle wrapper (gradlew.bat).
    Locates java.exe, assembles JVM options, and invokes the Gradle wrapper jar.
.EXAMPLE
    .\gradlew.ps1 build
    .\gradlew.ps1 clean --stacktrace
#>
$env:GRADLE_USER_HOME='E:\.gradle\'
$ErrorActionPreference = 'Stop'
# 统一控制台输入输出编码为 UTF-8，避免中文错误信息乱码
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    [Console]::InputEncoding  = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch {
    # 某些非交互终端可能不支持，忽略即可
}
# --- 脚本定位 ---
$APP_BASE_NAME = [System.IO.Path]::GetFileNameWithoutExtension($PSCommandPath)
if ([string]::IsNullOrEmpty($APP_BASE_NAME)) { $APP_BASE_NAME = 'gradlew' }

$DIRNAME = $PSScriptRoot
if ([string]::IsNullOrEmpty($DIRNAME)) { $DIRNAME = '.' }

$APP_HOME = (Resolve-Path -LiteralPath $DIRNAME).Path

# 默认 JVM 选项（与 bat 中的 DEFAULT_JVM_OPTS 对应）
[string[]]$DEFAULT_JVM_OPTS = @('-Xmx64m', '-Xms64m')

# 把 "a b  c" 形式的选项字符串拆成字符串数组
function Split-JvmOpts([string]$s) {
    if ([string]::IsNullOrWhiteSpace($s)) { return [string[]]@() }
    return [string[]](@($s -split '\s+' | Where-Object { $_ -ne '' }))
}

# --- 查找 java.exe ---
$JAVA_EXE = $null

if (-not [string]::IsNullOrEmpty($env:JAVA_HOME)) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $candidate)) {
        Write-Host "ERROR: JAVA_HOME is set to an invalid directory: $($env:JAVA_HOME)" -ForegroundColor Red
        Write-Host "Please set the JAVA_HOME variable in your environment to match the location of your Java installation." -ForegroundColor Red
        exit 1
    }
    $JAVA_EXE = $candidate
} else {
    $cmd = Get-Command -Name 'java.exe' -ErrorAction SilentlyContinue
    if ($cmd) { $JAVA_EXE = $cmd.Source }
}

if ([string]::IsNullOrEmpty($JAVA_EXE) -or -not (Test-Path -LiteralPath $JAVA_EXE)) {
    Write-Host "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH." -ForegroundColor Red
    Write-Host "Please set the JAVA_HOME variable in your environment to match the location of your Java installation." -ForegroundColor Red
    exit 1
}

# --- 组装 JVM 参数 ---
# 统一用字符串数组拼接，避免 List[string].AddRange 对 Object[] 的泛型类型不匹配
[string[]]$jvmArgs = @()
$jvmArgs += $DEFAULT_JVM_OPTS
$jvmArgs += (Split-JvmOpts $env:JAVA_OPTS)
$jvmArgs += (Split-JvmOpts $env:GRADLE_OPTS)
$jvmArgs += "-Dorg.gradle.appname=$APP_BASE_NAME"
$jvmArgs += '-classpath'
$jvmArgs += ''
$jvmArgs += '-jar'
$jvmArgs += (Join-Path $APP_HOME 'gradle\wrapper\gradle-wrapper.jar')

# --- 执行 Gradle Wrapper ---
# 原生命令的非零退出码不应触发 PowerShell 异常，退出码由 $LASTEXITCODE 捕获
$oldEA = $ErrorActionPreference
$ErrorActionPreference = 'Continue'

$oldNative = $null
if (Get-Variable -Name 'PSNativeCommandUseErrorActionPreference' -ErrorAction SilentlyContinue) {
    $oldNative = $PSNativeCommandUseErrorActionPreference
    $PSNativeCommandUseErrorActionPreference = $false
}

try {
    & $JAVA_EXE @jvmArgs @args
    $exitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $oldEA
    if ($null -ne $oldNative) {
        $PSNativeCommandUseErrorActionPreference = $oldNative
    }
}

if ($exitCode -ne 0) { exit $exitCode }
exit 0