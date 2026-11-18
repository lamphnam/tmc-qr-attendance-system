Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$testRoot = Join-Path $repoRoot ('kiosk-agent/target/key-provision-tests/' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($testRoot)
$script = Join-Path $PSScriptRoot 'provision-kiosk-key.ps1'
$shell = (Get-Process -Id $PID).Path
$passed = 0
$failed = 0

function Invoke-Provision([string] $Path) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $shell
    $start.Arguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + $script + '" -EnvFile "' + $Path + '"'
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($start)
    try {
        $output = $process.StandardOutput.ReadToEnd() + $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        return @{ Code = $process.ExitCode; Text = $output }
    } finally { $process.Dispose() }
}

function Assert-True([bool] $Condition) {
    if (-not $Condition) { throw 'Assertion failed (key material hidden).' }
}

function Random-Key([int] $Length = 32) {
    $bytes = New-Object byte[] $Length
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $random.GetBytes($bytes)
        return [Convert]::ToBase64String($bytes)
    } finally { $random.Dispose(); [Array]::Clear($bytes, 0, $bytes.Length) }
}

function New-Env([string] $Name, [string] $Contents) {
    $dir = Join-Path $testRoot $Name
    [void][IO.Directory]::CreateDirectory($dir)
    $path = Join-Path $dir '.env'
    [IO.File]::WriteAllText($path, $Contents, [Text.UTF8Encoding]::new($false))
    return $path
}

function Read-Key([string] $Path) {
    $entry = [regex]::Match([IO.File]::ReadAllText($Path), '(?m)^KIOSK_WHITELIST_KEY_BASE64=([^\r\n]+)')
    Assert-True $entry.Success
    return $entry.Groups[1].Value
}

function Test-Case([string] $Name, [scriptblock] $Body) {
    try {
        & $Body
        $script:passed++
        Write-Output "PASS $Name"
    } catch {
        $script:failed++
        Write-Output "FAIL $Name (details hidden to protect test keys)"
    }
}

Test-Case 'new env gets one random 32-byte key without printing it' {
    $dir = Join-Path $testRoot 'new'
    [void][IO.Directory]::CreateDirectory($dir)
    $file = Join-Path $dir '.env'
    $result = Invoke-Provision $file
    Assert-True ($result.Code -eq 0)
    $key = Read-Key $file
    Assert-True ([Convert]::FromBase64String($key).Length -eq 32)
    Assert-True (-not $result.Text.Contains($key))
    Assert-True ([IO.File]::ReadAllText($file).Contains('KIOSK_WHITELIST_SYNC_ENABLED=false'))
}

Test-Case 'valid key is reused byte-for-byte across restarts' {
    $key = Random-Key
    $file = New-Env 'reuse' ("# retained`r`nKIOSK_WHITELIST_KEY_BASE64=" + $key + "`r`nPOSTGRES_DB=test`r`n")
    $before = [IO.File]::ReadAllText($file)
    foreach ($attempt in 1..2) {
        $result = Invoke-Provision $file
        Assert-True ($result.Code -eq 0 -and -not $result.Text.Contains($key))
        Assert-True ([IO.File]::ReadAllText($file) -ceq $before)
    }
}

Test-Case 'missing assignment preserves unrelated env settings' {
    $file = New-Env 'missing' "# keep`r`nPOSTGRES_DB=test`r`n"
    Assert-True ((Invoke-Provision $file).Code -eq 0)
    Assert-True ([IO.File]::ReadAllText($file).StartsWith("# keep`r`nPOSTGRES_DB=test"))
    Assert-True ([Convert]::FromBase64String((Read-Key $file)).Length -eq 32)
}

Test-Case 'blank assignment is provisioned once' {
    $file = New-Env 'blank' "KIOSK_WHITELIST_KEY_BASE64=`n"
    Assert-True ((Invoke-Provision $file).Code -eq 0)
    $key = Read-Key $file
    Assert-True ((Invoke-Provision $file).Code -eq 0)
    Assert-True ((Read-Key $file) -ceq $key)
}

Test-Case 'invalid Base64 fails without overwriting or echoing it' {
    $value = 'synthetic-invalid-key!'
    $contents = 'KIOSK_WHITELIST_KEY_BASE64=' + $value
    $file = New-Env 'invalid' $contents
    $result = Invoke-Provision $file
    Assert-True ($result.Code -eq 1 -and $result.Text.Contains('Invalid KIOSK_WHITELIST_KEY_BASE64'))
    Assert-True (-not $result.Text.Contains($value))
    Assert-True ([IO.File]::ReadAllText($file) -ceq $contents)
}

Test-Case 'wrong length fails without silently rotating it' {
    $key = Random-Key 16
    $contents = 'KIOSK_WHITELIST_KEY_BASE64=' + $key
    $file = New-Env 'length' $contents
    $result = Invoke-Provision $file
    Assert-True ($result.Code -eq 1 -and -not $result.Text.Contains($key))
    Assert-True ([IO.File]::ReadAllText($file) -ceq $contents)
}

Test-Case 'duplicate assignments fail without modifying the file' {
    $key = Random-Key
    $contents = "KIOSK_WHITELIST_KEY_BASE64=$key`nKIOSK_WHITELIST_KEY_BASE64=$key`n"
    $file = New-Env 'duplicate' $contents
    $result = Invoke-Provision $file
    Assert-True ($result.Code -eq 1 -and -not $result.Text.Contains($key))
    Assert-True ([IO.File]::ReadAllText($file) -ceq $contents)
}

Test-Case 'quoted valid key and inline comment are preserved' {
    $key = Random-Key
    $contents = 'export KIOSK_WHITELIST_KEY_BASE64="' + $key + '" # local only'
    $file = New-Env 'quoted' $contents
    $result = Invoke-Provision $file
    Assert-True ($result.Code -eq 0 -and -not $result.Text.Contains($key))
    Assert-True ([IO.File]::ReadAllText($file) -ceq $contents)
}

Test-Case 'a template or source file is never a provisioning target' {
    $file = Join-Path $testRoot '.env.example'
    [IO.File]::WriteAllText($file, 'KIOSK_WHITELIST_KEY_BASE64=')
    Assert-True ((Invoke-Provision $file).Code -eq 1)
    Assert-True ([IO.File]::ReadAllText($file) -ceq 'KIOSK_WHITELIST_KEY_BASE64=')
}

Write-Output "Tests: PASS=$passed FAIL=$failed ERROR=0 SKIP=0"
if ($failed -gt 0) { exit 1 }
