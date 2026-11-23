param(
    [string]$SigningDirectory = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.android\wildsearch'),
    [string]$Jdk = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
if (-not $Jdk -or -not (Test-Path (Join-Path $Jdk 'bin\keytool.exe'))) { throw 'JDK 不可用：请设置 JAVA_HOME 或传 -Jdk <JDK 根目录>。' }
$signingRoot = [IO.Path]::GetFullPath($SigningDirectory)
$signingKey = Join-Path $signingRoot 'wildsearch-release.p12'
$signingConfig = Join-Path $signingRoot 'release-signing.properties'
if ((Test-Path -LiteralPath $signingKey) -or (Test-Path -LiteralPath $signingConfig)) {
    throw 'Existing signing material found. Refusing to overwrite or regenerate it.'
}
New-Item -ItemType Directory -Path $signingRoot -Force | Out-Null
$signingIdentity = [Security.Principal.WindowsIdentity]::GetCurrent().User
$signingAcl = Get-Acl -LiteralPath $signingRoot
$signingAcl.SetAccessRuleProtection($true, $false)
$signingAcl.SetOwner($signingIdentity)
foreach ($signingPrincipal in @($signingIdentity, [Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
    $signingRule = [Security.AccessControl.FileSystemAccessRule]::new(
        $signingPrincipal, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'
    )
    $signingAcl.AddAccessRule($signingRule)
}
Set-Acl -LiteralPath $signingRoot -AclObject $signingAcl
$signingPassword = -join ([Security.Cryptography.RandomNumberGenerator]::GetBytes(32) | ForEach-Object { $_.ToString('x2') })
$signingPreviousPassword = $env:WILDSEARCH_KEYTOOL_PASSWORD
$signingTemporaryKey = Join-Path $signingRoot ('wildsearch-release.' + [guid]::NewGuid().ToString('N') + '.p12')
try {
    $env:WILDSEARCH_KEYTOOL_PASSWORD = $signingPassword
    & (Join-Path $Jdk 'bin\keytool.exe') -genkeypair -noprompt -keystore $signingTemporaryKey `
        -storetype PKCS12 -alias wildsearch -keyalg RSA -keysize 3072 -validity 10950 `
        -dname 'CN=WildSearch' -storepass:env WILDSEARCH_KEYTOOL_PASSWORD -keypass:env WILDSEARCH_KEYTOOL_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Key generation failed; any temporary material has been retained for inspection.' }
    # Move-Item without Force refuses to replace an existing destination.
    Move-Item -LiteralPath $signingTemporaryKey -Destination $signingKey
    $signingContent = "storeFile=$($signingKey.Replace('\', '/'))`nstoreType=PKCS12`nkeyAlias=wildsearch`nstorePassword=$signingPassword`nkeyPassword=$signingPassword`n"
    $signingStream = [IO.FileStream]::new($signingConfig, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
    try {
        $signingBytes = [Text.UTF8Encoding]::new($false).GetBytes($signingContent)
        $signingStream.Write($signingBytes, 0, $signingBytes.Length)
    } finally { $signingStream.Dispose() }
    Write-Output 'Created project Release signing material; passwords were not printed.'
    Write-Output $signingKey
    Write-Output $signingConfig
} finally {
    if ($null -eq $signingPreviousPassword) { Remove-Item Env:WILDSEARCH_KEYTOOL_PASSWORD -ErrorAction SilentlyContinue }
    else { $env:WILDSEARCH_KEYTOOL_PASSWORD = $signingPreviousPassword }
    $signingPassword = $null
    $signingContent = $null
    $signingBytes = $null
}
