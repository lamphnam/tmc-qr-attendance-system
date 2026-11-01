<#
  Smoke test for a RUNNING stack (docker compose up --build -d --wait) - PowerShell edition of smoke.sh.
  Exit code 0 ONLY when every check passes.   Usage:  powershell -ExecutionPolicy Bypass -File scripts\smoke.ps1
  Override targets with $env:GATEWAY_URL, FRONTEND_URL, KIOSK_URL, MOCK_TURNSTILE_URL, MOCK_HR_URL.
#>
$ErrorActionPreference = 'Stop'

function Get-Setting($name, $hostPortVar, $defaultPort) {
  $explicit = [Environment]::GetEnvironmentVariable($name)
  if ($explicit) { return $explicit }
  $port = [Environment]::GetEnvironmentVariable($hostPortVar)
  if (-not $port) { $port = $defaultPort }
  return "http://127.0.0.1:$port"
}

$Gateway   = Get-Setting 'GATEWAY_URL'        'GATEWAY_HOST_PORT'        8080
$Frontend  = Get-Setting 'FRONTEND_URL'       'FRONTEND_HOST_PORT'       3000
$Kiosk     = Get-Setting 'KIOSK_URL'          'KIOSK_HOST_PORT'          8085
$Turnstile = Get-Setting 'MOCK_TURNSTILE_URL' 'MOCK_TURNSTILE_HOST_PORT' 8090
$Hr        = Get-Setting 'MOCK_HR_URL'        'MOCK_HR_HOST_PORT'        8091

$script:Passes = 0
$script:Fails = 0

# Windows PowerShell 5.1 returns .Content as byte[] for non-text media types such as Spring Actuator's
# application/vnd.spring-boot.actuator.v3+json, so decode it explicitly.
function ConvertTo-BodyText($content) {
  if ($content -is [byte[]]) { return [System.Text.Encoding]::UTF8.GetString($content) }
  return [string]$content
}

# Returns @{ Code = <int, 0 when nothing answered>; Body = <string> }; never throws on HTTP error statuses.
function Invoke-Http($method, $url, $body) {
  try {
    $args = @{ Uri = $url; Method = $method; UseBasicParsing = $true; TimeoutSec = 10 }
    if ($body) { $args.Body = $body; $args.ContentType = 'application/json' }
    $r = Invoke-WebRequest @args
    return @{ Code = [int]$r.StatusCode; Body = (ConvertTo-BodyText $r.Content) }
  } catch [System.Net.WebException] {
    $err = $_
    $resp = $err.Exception.Response
    if ($null -eq $resp) { return @{ Code = 0; Body = '' } }
    # In 5.1 the error body has usually been consumed already and lives in ErrorDetails; fall back to the stream.
    $text = $err.ErrorDetails.Message
    if (-not $text) {
      try {
        $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
        $text = $reader.ReadToEnd()
        $reader.Close()
      } catch { $text = '' }
    }
    return @{ Code = [int]$resp.StatusCode; Body = [string]$text }
  } catch {
    return @{ Code = 0; Body = '' }
  }
}

function Expect($label, $method, $url, $wantCode, $needle = $null, $data = $null) {
  $r = Invoke-Http $method $url $data
  if ($r.Code -ne $wantCode) {
    Write-Host "  FAIL  $label  (expected HTTP $wantCode, got $($r.Code): $url)"
    $script:Fails++
  } elseif ($needle -and -not $r.Body.Contains($needle)) {
    Write-Host "  FAIL  $label  (HTTP $($r.Code) but body lacks: $needle)"
    $script:Fails++
  } else {
    Write-Host "  PASS  $label"
    $script:Passes++
  }
}

Write-Host "Smoke test -> gateway $Gateway"
for ($i = 0; $i -lt 30; $i++) {
  if ((Invoke-Http 'GET' "$Gateway/gateway/health" $null).Code -eq 200) { break }
  Start-Sleep -Seconds 1
}

Write-Host '[1] gateway and frontend'
Expect 'gateway health'                         GET "$Gateway/gateway/health" 200 '"status":"UP"'
Expect 'frontend served through gateway'        GET "$Gateway/"               200 'id="root"'
Expect 'frontend served on its own port'        GET "$Frontend/"              200 'id="root"'
Expect 'frontend proxies /services to gateway'  GET "$Frontend/services/attendance/health" 200 '"status":"UP"'

Write-Host '[2] service health + real database connectivity (through the gateway)'
foreach ($svc in 'user-qr', 'gate-station', 'attendance', 'reporting') {
  Expect "${svc}: UP with PostgreSQL reachable" GET "$Gateway/services/$svc/health" 200 '"db":{"status":"UP"'
}
Expect 'kiosk: UP'                              GET "$Gateway/services/kiosk/health" 200 '"status":"UP"'
Expect 'kiosk: SQLite file opened and read'     GET "$Gateway/services/kiosk/health" 200 '"sqliteQueue":{"status":"UP"'
Expect 'kiosk: SQLite journal_mode is WAL'      GET "$Gateway/services/kiosk/health" 200 '"journalMode":"wal"'
Expect 'kiosk: SQLite synchronous is FULL'      GET "$Gateway/services/kiosk/health" 200 '"synchronous":"FULL"'
Expect 'mock-turnstile health (via gateway)'    GET "$Gateway/services/mock-turnstile/health" 200 '"status":"UP"'
Expect 'mock-hr health (via gateway)'           GET "$Gateway/services/mock-hr/health"        200 '"status":"UP"'

Write-Host '[3] direct host ports'
Expect 'kiosk-agent on host port'               GET "$Kiosk/actuator/health" 200 '"status":"UP"'
Expect 'mock-turnstile on host port'            GET "$Turnstile/health"      200 '"mock":true'
Expect 'mock-hr on host port'                   GET "$Hr/health"             200 '"mock":true'
Expect 'mock-hr serves synthetic employees'     GET "$Hr/mock/hr/employees"  200 '"EMP001"'

Write-Host '[4] every /api/v1 area is routed to its owning service (501 JSON = reached Spring, not an nginx 404)'
Expect 'user-qr    GET  /api/v1/qr/generate'            GET  "$Gateway/api/v1/qr/generate"            501 '"code":"NOT_IMPLEMENTED"'
Expect 'user-qr    GET  /api/v1/kiosk/whitelist'        GET  "$Gateway/api/v1/kiosk/whitelist"        501 '"code":"NOT_IMPLEMENTED"'
Expect 'gate       POST /api/v1/gates'                  POST "$Gateway/api/v1/gates"                  501 '"code":"NOT_IMPLEMENTED"' '{}'
Expect 'gate       GET  /api/v1/gates/BD-G01/status'    GET  "$Gateway/api/v1/gates/BD-G01/status"    501 '"code":"NOT_IMPLEMENTED"'
Expect 'attendance POST /api/v1/attendance/events'      POST "$Gateway/api/v1/attendance/events"     501 '"code":"NOT_IMPLEMENTED"' '{}'
Expect 'reporting  GET  /api/v1/reports/dashboard'      GET  "$Gateway/api/v1/reports/dashboard"      501 '"code":"NOT_IMPLEMENTED"'
Expect 'kiosk      POST /api/local/scan (via gateway)'  POST "$Gateway/api/local/scan"                501 '"code":"NOT_IMPLEMENTED"' '{}'

Write-Host '[5] gateway hygiene'
Expect 'unknown /api path is a JSON 404 (and cannot loop)' GET "$Gateway/api/v1/nothing-here"     404 '"code":"NOT_FOUND"'
Expect 'unknown service probe is a JSON 404'               GET "$Gateway/services/nothing/health" 404 '"code":"NOT_FOUND"'

Write-Host '[6] dev-only fixtures exist ONLY because the dev profile is on'
Expect 'central dev ingestion rejects an empty event' POST "$Gateway/api/dev/attendance/events" 400 '"code":"VALIDATION_ERROR"' '{}'
Expect 'kiosk dev queue readable'                     GET  "$Gateway/api/dev/kiosk/queue"      200 '"counts"'

Write-Host ''
if ($script:Fails -eq 0) {
  Write-Host "SMOKE PASSED: $($script:Passes) checks, 0 failed."
  exit 0
}
Write-Host "SMOKE FAILED: $($script:Fails) failed, $($script:Passes) passed."
Write-Host 'Hint: docker compose ps ; docker compose logs --tail=80 <service>'
exit 1
