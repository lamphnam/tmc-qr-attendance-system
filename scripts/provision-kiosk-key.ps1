param(
    [string] $EnvFile = (Join-Path (Split-Path -Parent $PSScriptRoot) '.env')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$safeError = 'Unable to provision the local dev AES key; check file permissions. Key values are hidden.'
$lock = $null
$temporaryFile = $null
$keyBytes = $null

function Test-WhitelistKey([string] $Value) {
    $decoded = $null
    try {
        # The codec accepts standard Base64. Do not allow dotenv interpolation or embedded whitespace.
        if ($Value -notmatch '^[A-Za-z0-9+/]{43}=$') { return $false }
        $decoded = [Convert]::FromBase64String($Value)
        return $decoded.Length -eq 32
    } catch {
        return $false
    } finally {
        if ($null -ne $decoded) { [Array]::Clear($decoded, 0, $decoded.Length) }
    }
}

try {
    $envPath = [IO.Path]::GetFullPath($EnvFile)
    $safeError = 'Key provisioning target must be a local .env file; templates and source files are not allowed.'
    if ([IO.Path]::GetFileName($envPath) -ne '.env') { throw 'invalid env target' }
    $safeError = 'Unable to provision the local dev AES key; check file permissions. Key values are hidden.'
    # Serialize invocations so two blank-file provisions cannot silently rotate one another's key.
    $lock = [IO.FileStream]::new($envPath + '.kiosk-key.lock', [IO.FileMode]::OpenOrCreate,
        [IO.FileAccess]::Write, [IO.FileShare]::None, 4096, [IO.FileOptions]::DeleteOnClose)
    $exists = [IO.File]::Exists($envPath)
    if ($exists) {
        $contents = [IO.File]::ReadAllText($envPath)
    } else {
        $contents = [IO.File]::ReadAllText((Join-Path (Split-Path -Parent $PSScriptRoot) '.env.example'))
    }
    $pattern = '(?m)^[\t ]*(?:export[\t ]+)?KIOSK_WHITELIST_KEY_BASE64[\t ]*=[^\r\n]*'
    $assignments = [regex]::Matches($contents, $pattern)
    $safeError = 'Invalid KIOSK_WHITELIST_KEY_BASE64 in local env; expected one Base64-encoded 32-byte key. File unchanged.'
    if ($assignments.Count -gt 1) { throw 'invalid key configuration' }
    if ($assignments.Count -eq 1) {
        $value = ($assignments[0].Value -split '=', 2)[1].Trim()
        if ($value -match '^(["''])(.*?)\1[\t ]*(?:#.*)?$') {
            $value = $Matches[2]
        } else {
            $value = ($value -replace '[\t ]+#.*$', '').Trim()
        }
        if ($value.Length -gt 0) {
            if (-not (Test-WhitelistKey $value)) { throw 'invalid key configuration' }
            Write-Output 'Existing valid local dev AES key reused; file unchanged (value hidden).'
            exit 0
        }
    }

    $safeError = 'Unable to provision the local dev AES key; check file permissions. Key values are hidden.'
    $keyBytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($keyBytes) } finally { $random.Dispose() }
    $assignment = 'KIOSK_WHITELIST_KEY_BASE64=' + [Convert]::ToBase64String($keyBytes)
    if ($assignments.Count -eq 1) {
        $entry = $assignments[0]
        $contents = $contents.Substring(0, $entry.Index) + $assignment + $contents.Substring($entry.Index + $entry.Length)
    } else {
        $contents = $contents.TrimEnd("`r", "`n") + [Environment]::NewLine + $assignment + [Environment]::NewLine
    }
    # Write completely before replacing .env; no backup containing an extra secret is left behind.
    $temporaryFile = $envPath + '.' + [guid]::NewGuid().ToString('N') + '.tmp'
    [IO.File]::WriteAllText($temporaryFile, $contents, [Text.UTF8Encoding]::new($false))
    # PowerShell 5.1 coerces $null to an empty string for this .NET string argument.
    if ($exists) { [IO.File]::Replace($temporaryFile, $envPath, [NullString]::Value) }
    else { [IO.File]::Move($temporaryFile, $envPath) }
    $temporaryFile = $null
    Write-Output 'Local dev AES key provisioned in env file (value hidden). Preserve it for SQLite recovery.'
} catch {
    Write-Error -Message $safeError -ErrorAction Continue
    exit 1
} finally {
    if ($null -ne $keyBytes) { [Array]::Clear($keyBytes, 0, $keyBytes.Length) }
    if ($null -ne $temporaryFile -and [IO.File]::Exists($temporaryFile)) { [IO.File]::Delete($temporaryFile) }
    if ($null -ne $lock) { $lock.Dispose() }
}
