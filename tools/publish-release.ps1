#requires -Version 5.1
<#
.SYNOPSIS
  构建、校验并发布 WildSearch 到 GitHub Releases，作为应用内自动更新源。

.DESCRIPTION
  - 从 app/build.gradle.kts 读取版本号，从 gradle.properties 读取 owner/repo。
  - -Bootstrap 可初始化 Git、提交源码、创建公开仓库并推送 main。
  - 构建 Release APK，校验签名证书指纹、APK 版本和 SHA-256。
  - 创建 GitHub Release（tag 为 v<version>）并上传 APK 与 .sha256。
  - 校验远端 latest release 的 tag、附件大小和 digest。

  Token 必须是 fine-grained PAT（Contents: Read and write）；未设置时脚本会隐藏输入。
  绝不能把 Token、签名密钥或密码提交到仓库。

.EXAMPLE
  .\tools\publish-release.ps1 -Bootstrap

.EXAMPLE
  $env:GITHUB_TOKEN = 'github_pat_...'
  .\tools\publish-release.ps1

.EXAMPLE
  .\tools\publish-release.ps1 -DryRun   # 只构建并生成 dist，不访问 GitHub
#>
[CmdletBinding()]
param(
    [string]$Version,
    [string]$Repo,
    [string]$NotesFile,
    [string]$Token = $env:GITHUB_TOKEN,
    [switch]$SkipBuild,
    [switch]$OnlineBuild,
    [switch]$DryRun,
    [switch]$Bootstrap
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Fail([string]$message) { throw "发布失败：$message" }
function Get-HttpStatus($errorRecord) {
    try { return [int]$errorRecord.Exception.Response.StatusCode } catch { return 0 }
}
function Invoke-Git([string[]]$arguments) {
    & git @arguments
    if ($LASTEXITCODE -ne 0) { Fail "git $($arguments[0]) 失败（exit $LASTEXITCODE）。" }
}
function Read-TokenIfNeeded {
    if ($Token -or $DryRun) { return }
    if (-not [Environment]::UserInteractive) { Fail '缺少 GITHUB_TOKEN。' }
    $secure = Read-Host 'GitHub Token（输入不显示）' -AsSecureString
    $Token = [Net.NetworkCredential]::new('', $secure).Password
    if (-not $Token) { Fail '未输入 GitHub Token。' }
}

$gradlePropsPath = Join-Path $root 'gradle.properties'
if (-not (Test-Path $gradlePropsPath)) { Fail '找不到 gradle.properties。' }
if (-not $Repo) {
    $match = [regex]::Match((Get-Content $gradlePropsPath -Raw), '(?m)^\s*wildsearch\.update\.repo\s*=\s*(\S+)\s*$')
    if ($match.Success) { $Repo = $match.Groups[1].Value.Trim() }
}
if (-not $Repo -or $Repo -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') {
    Fail '无法确定更新仓库，请检查 gradle.properties 的 wildsearch.update.repo 或传 -Repo owner/repo。'
}
$owner = $Repo.Split('/')[0]
$repoName = $Repo.Split('/')[1]

$buildGradlePath = Join-Path $root 'app\build.gradle.kts'
$buildGradle = Get-Content $buildGradlePath -Raw
$versionMatch = [regex]::Match($buildGradle, 'versionName\s*=\s*"([^"]+)"')
$codeMatch = [regex]::Match($buildGradle, 'versionCode\s*=\s*(\d+)')
if (-not $versionMatch.Success -or -not $codeMatch.Success) { Fail '无法从 app/build.gradle.kts 读取版本号。' }
$fileVersion = $versionMatch.Groups[1].Value
$versionCode = [int]$codeMatch.Groups[1].Value
if (-not $Version) { $Version = $fileVersion }
if ($Version -ne $fileVersion) {
    Fail "传入版本 $Version 与 app/build.gradle.kts 的 $fileVersion 不一致；请先同步版本号。"
}
$tag = "v$Version"

Read-TokenIfNeeded
$headers = $null
if ($Token) {
    $headers = @{
        Authorization          = "Bearer $Token"
        Accept                 = 'application/vnd.github+json'
        'User-Agent'           = 'WildSearch-Publish'
        'X-GitHub-Api-Version' = '2022-11-28'
    }
}

if ($Bootstrap -and -not $DryRun) {
    if (-not $Token) { Fail 'Bootstrap 需要 GitHub Token 来创建仓库并推送。' }
    Write-Host "==> 初始化 Git 仓库 $Repo"
    $login = (Invoke-RestMethod -Uri 'https://api.github.com/user' -Headers $headers -TimeoutSec 60).login
    if ($login -ne $owner) { Fail "Token 属于 $login，但目标仓库是 $Repo；请修正仓库名或 Token。" }

    if (-not (Test-Path (Join-Path $root '.git'))) { Invoke-Git @('init', '-b', 'main') }
    if (-not (& git config user.name 2>$null)) { Invoke-Git @('config', 'user.name', $owner) }
    if (-not (& git config user.email 2>$null)) { Invoke-Git @('config', 'user.email', "$owner@users.noreply.github.com") }
    Invoke-Git @('config', 'core.autocrlf', 'false')

    $repoExists = $true
    $repoMeta = $null
    try {
        $repoMeta = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo" -Headers $headers -TimeoutSec 60
    } catch {
        if ((Get-HttpStatus $_) -eq 404) { $repoExists = $false } else { Fail "读取仓库信息失败：$($_.Exception.Message)" }
    }
    if ($repoExists -and $repoMeta.private) {
        Fail '目标仓库是 private。应用内更新使用匿名 GitHub API，必须使用 public 仓库。'
    }
    if ($repoExists -and [long]$repoMeta.size -gt 0) {
        Fail '目标仓库已有内容。请先在 GitHub 上清空，或移除 -Bootstrap 后手动合并并推送。'
    }
    if (-not $repoExists) {
        Write-Host "==> 创建公开仓库 $Repo"
        $body = @{
            name        = $repoName
            description = 'WildSearch Android app with in-app GitHub Releases updates'
            private     = $false
            has_issues  = $true
            has_wiki    = $false
        } | ConvertTo-Json
        Invoke-RestMethod -Method Post -Uri 'https://api.github.com/user/repos' -Headers $headers `
            -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 60 | Out-Null
    }

    $origin = & git remote get-url origin 2>$null
    if (-not $origin) {
        Invoke-Git @('remote', 'add', 'origin', "https://github.com/$Repo.git")
    } elseif ($origin -notmatch [regex]::Escape($Repo)) {
        Fail "origin 已指向 $origin，与目标 $Repo 不一致。"
    }

    Invoke-Git @('add', '-A')
    Invoke-Git @('update-index', '--chmod=+x', 'gradlew')
    $pending = & git status --porcelain
    if ($pending) {
        Write-Host '==> 提交源码'
        Invoke-Git @('commit', '-m', "WildSearch $Version")
    }
    if (-not (& git rev-parse --verify HEAD 2>$null)) { Fail '仓库没有可推送的提交。' }

    Write-Host '==> 推送 main'
    $basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("x-access-token:$Token"))
    Invoke-Git @('-c', "http.extraHeader=Authorization: Basic $basic", 'push', '-u', 'origin', 'main')
}

if (-not (Test-Path (Join-Path $root '.git'))) { Fail '当前目录不是 Git 仓库，请加 -Bootstrap 或先初始化并提交源码。' }
$differences = & git status --porcelain
if ($differences) { Fail "工作区有未提交改动，请先提交：`n$($differences -join "`n")" }
$origin = & git remote get-url origin 2>$null
if (-not $origin) { Fail '未配置 git remote origin。' }
if (-not $DryRun -and -not $headers) { Fail '缺少 GITHUB_TOKEN。' }

if (-not $DryRun) {
    try {
        $releaseMeta = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/tags/$tag" -Headers $headers -TimeoutSec 60
        Fail "Release $tag 已存在，拒绝覆盖：$($releaseMeta.html_url)"
    } catch {
        if ($_.Exception.Message -like '发布失败：*') { throw }
        if ((Get-HttpStatus $_) -ne 404) {
            Fail "无法确认 Release $tag 是否存在：$($_.Exception.Message)"
        }
    }
}

$apk = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'
if (-not $SkipBuild) {
    Write-Host "==> 构建 Release APK $Version (versionCode=$versionCode)"
    $gradleArguments = @()
    $isolatedHome = Join-Path $root 'audit\2026-11-23\generated\gradle-verification'
    if (-not $env:WILDSEARCH_GRADLE_HOME -and (Test-Path $isolatedHome)) {
        $env:WILDSEARCH_GRADLE_HOME = $isolatedHome
    }
    if ($env:WILDSEARCH_GRADLE_HOME) { $gradleArguments += @('--gradle-user-home', $env:WILDSEARCH_GRADLE_HOME) }
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        $gradleArguments += "-Dorg.gradle.java.home=$env:JAVA_HOME"
    }
    if (-not $OnlineBuild -and $env:WILDSEARCH_GRADLE_HOME) { $gradleArguments += '--offline' }
    & (Join-Path $root 'gradlew.bat') @gradleArguments :app:assembleRelease --no-daemon
    if ($LASTEXITCODE -ne 0) { Fail 'Release 构建失败。' }
}
if (-not (Test-Path $apk)) { Fail "找不到 APK：$apk" }

# 证书指纹固定为项目正式签名；指纹变化说明用错了密钥，必须拒绝发布。
$expectedCertificate = '143ed30be35a199085cd079306d72c30afe06843934d5e63a442daa3830cc140'
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) {
    $localProperties = Join-Path $root 'local.properties'
    if (Test-Path $localProperties) {
        $match = [regex]::Match((Get-Content $localProperties -Raw), '(?m)^\s*sdk\.dir\s*=\s*(.+)\s*$')
        if ($match.Success) { $sdk = $match.Groups[1].Value.Trim().Replace('\\', '\').Replace('\:', ':').Trim('"') }
    }
}
if (-not $sdk -or -not (Test-Path (Join-Path $sdk 'build-tools'))) {
    Write-Warning '未找到 Android SDK build-tools，跳过签名与 APK 元数据校验。'
} else {
    $buildTools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory |
        Sort-Object Name -Descending |
        Where-Object { Test-Path (Join-Path $_.FullName 'apksigner.bat') } |
        Select-Object -First 1
    if (-not $buildTools) {
        Write-Warning '未找到 apksigner，跳过签名证书校验。'
    } else {
        $apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
        $certOutput = & $apksigner verify --print-certs $apk 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0) { Fail "APK 签名校验失败：`n$certOutput" }
        $certMatch = [regex]::Match($certOutput, 'SHA-256 digest:\s*([0-9a-fA-F:]+)')
        if (-not $certMatch.Success) { Fail '无法读取 APK 签名证书 SHA-256。' }
        $actualCertificate = ($certMatch.Groups[1].Value -replace ':', '').ToLowerInvariant()
        if ($actualCertificate -ne $expectedCertificate) {
            Fail "签名证书指纹为 $actualCertificate，期望 $expectedCertificate。拒绝发布错误签名的 APK。"
        }
        Write-Host "    签名证书 SHA-256：$actualCertificate"

        $aapt2 = Join-Path $buildTools.FullName 'aapt2.exe'
        if (Test-Path $aapt2) {
            $badging = & $aapt2 dump badging $apk 2>$null | Out-String
            if ($badging -match "versionCode='(\d+)'") {
                if ([int]$Matches[1] -ne $versionCode) { Fail "APK versionCode=$($Matches[1]) 与源码 $versionCode 不一致。" }
            }
            if ($badging -match "versionName='([^']+)'") {
                if ($Matches[1] -ne $Version) { Fail "APK versionName=$($Matches[1]) 与源码 $Version 不一致。" }
            }
        }
    }
}

$sha256 = (Get-FileHash -Algorithm SHA256 $apk).Hash.ToLowerInvariant()
$size = (Get-Item $apk).Length
Write-Host "    APK：$apk"
Write-Host "    大小：$size 字节"
Write-Host "    SHA-256：$sha256"

$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Path $dist -Force | Out-Null
$assetName = "WildSearch-$Version.apk"
$assetPath = Join-Path $dist $assetName
Copy-Item $apk $assetPath -Force
$checksumPath = "$assetPath.sha256"
[IO.File]::WriteAllText($checksumPath, "$sha256  $assetName`n", [Text.UTF8Encoding]::new($false))

if ($DryRun) {
    Write-Host "==> DryRun 完成：已生成 $assetPath，未创建标签或 Release。"
    exit 0
}

$notesPath = $NotesFile
if (-not $notesPath) {
    $candidate = Join-Path $root "docs\release-notes\$Version.md"
    if (Test-Path $candidate) { $notesPath = $candidate }
}
$notes = if ($notesPath) { Get-Content $notesPath -Raw } else { "WildSearch $Version`n`n- 自动更新基线版本。" }
$notes = $notes.TrimEnd() + "`n`n**APK SHA-256**：``$sha256``"

Write-Host "==> 创建 GitHub Release $tag"
$releaseBody = @{
    tag_name   = $tag
    name       = "WildSearch $Version"
    body       = $notes
    draft      = $false
    prerelease = $false
} | ConvertTo-Json -Depth 4
try {
    $release = Invoke-RestMethod -Method Post `
        -Uri "https://api.github.com/repos/$Repo/releases" `
        -Headers $headers `
        -ContentType 'application/json; charset=utf-8' `
        -Body ([Text.Encoding]::UTF8.GetBytes($releaseBody)) `
        -TimeoutSec 60
} catch {
    Fail "创建 Release 失败：$($_.Exception.Message)"
}
Write-Host "    $($release.html_url)"

function Remove-CreatedRelease {
    try {
        Invoke-RestMethod -Method Delete -Uri "https://api.github.com/repos/$Repo/releases/$($release.id)" -Headers $headers -TimeoutSec 60 | Out-Null
    } catch { }
    try {
        Invoke-RestMethod -Method Delete -Uri "https://api.github.com/repos/$Repo/git/refs/tags/$tag" -Headers $headers -TimeoutSec 60 | Out-Null
    } catch { }
}

function Send-ReleaseAsset([string]$name, [string]$path, [string]$contentType) {
    $uploadBase = $release.upload_url -replace '\{.*$', ''
    $url = "$uploadBase?name=$([uri]::EscapeDataString($name))"
    Invoke-RestMethod -Method Post -Uri $url -Headers $headers -ContentType $contentType -InFile $path -TimeoutSec 600
}

try {
    Write-Host "==> 上传 $assetName"
    Send-ReleaseAsset $assetName $assetPath 'application/vnd.android.package-archive' | Out-Null
    Write-Host "==> 上传 $assetName.sha256"
    Send-ReleaseAsset "$assetName.sha256" $checksumPath 'text/plain; charset=utf-8' | Out-Null

    Write-Host '==> 校验远端 latest 元数据'
    $latest = $null
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        try {
            $latest = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/latest" -Headers $headers -TimeoutSec 60
            if ($latest.tag_name -eq $tag) { break }
        } catch { }
        Start-Sleep -Seconds 3
    }
    if (-not $latest -or $latest.tag_name -ne $tag) {
        Fail "latest release 未指向 $tag（当前：$($latest.tag_name)）。"
    }
    $remoteAsset = $latest.assets | Where-Object { $_.name -eq $assetName } | Select-Object -First 1
    if (-not $remoteAsset) { Fail "latest release 中找不到 $assetName。" }
    if ($remoteAsset.size -ne $size) { Fail "远端附件大小 $($remoteAsset.size) 与本地 $size 不一致。" }
    if ($remoteAsset.digest) {
        if ($remoteAsset.digest.ToLowerInvariant() -ne "sha256:$sha256") {
            Fail "远端 digest $($remoteAsset.digest) 与本地 SHA-256 不一致。"
        }
        Write-Host "    digest：$($remoteAsset.digest)"
    } else {
        Write-Warning 'GitHub 暂未返回附件 digest；应用将退回大小 + APK 文件头校验。'
    }
} catch {
    Remove-CreatedRelease
    Fail "上传或校验失败，已清理本次 Release：$($_.Exception.Message)"
}

Write-Host ''
Write-Host "发布完成：https://github.com/$Repo/releases/tag/$tag"
Write-Host "应用内更新源：https://api.github.com/repos/$Repo/releases/latest"