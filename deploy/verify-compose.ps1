param(
    [ValidateRange(2, 100)]
    [int]$Requests = 8
)

$ErrorActionPreference = 'Stop'
Import-Module "$PSScriptRoot/compose-verification.psm1" -Force
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$projectName = Get-ComposeProjectName -RepositoryRoot $repositoryRoot
$baseUrl = 'http://127.0.0.1:8080'
$compose = @(
    'compose',
    '-p', $projectName,
    '-f', (Join-Path $repositoryRoot 'compose.yaml'),
    '--env-file', (Join-Path $repositoryRoot '.env.compose')
)

$gatewayHealth = Invoke-WebRequest -Uri "$baseUrl/_gateway_health" -TimeoutSec 10
if ($gatewayHealth.StatusCode -ne 200) {
    throw "Gateway health returned HTTP $($gatewayHealth.StatusCode)."
}

$agentStatuses = @{}
foreach ($service in @('agent-1', 'agent-2')) {
    $containerIds = & docker @compose ps -q $service 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to find Compose container for $service."
    }

    $containerId = ($containerIds -join '').Trim()
    if (-not $containerId) {
        throw "Compose service $service is not running."
    }

    $healthLines = & docker inspect --format '{{.State.Health.Status}}' $containerId 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to read health status for $service."
    }

    $healthStatus = ($healthLines -join '').Trim()
    if ($healthStatus -ne 'healthy') {
        throw "Agent service $service reports health status '$healthStatus'."
    }

    $agentStatuses[$service] = $healthStatus
}

$verificationId = [guid]::NewGuid().ToString('D')
$verificationStartedAt = [DateTime]::UtcNow.ToString('o')
for ($requestNumber = 1; $requestNumber -le $Requests; $requestNumber++) {
    $headers = @{ 'X-Verification-ID' = $verificationId }
    $response = Invoke-WebRequest -Uri "$baseUrl/api/knowledge/category/tree" -Headers $headers -TimeoutSec 20
    if ($response.StatusCode -ne 200) {
        throw "Public knowledge request $requestNumber returned HTTP $($response.StatusCode)."
    }

    $agentResponse = & docker @compose exec -T gateway wget -q "--header=X-Verification-ID: $verificationId" -O - 'http://127.0.0.1:5000/api/agent/health/live' 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Agent health request $requestNumber failed through the internal gateway."
    }

    $agentHealth = ($agentResponse -join [Environment]::NewLine) | ConvertFrom-Json
    if ($agentHealth.status -ne 'ok') {
        throw "Agent health request $requestNumber returned an unexpected payload."
    }
}

$composeLogs = & docker @compose logs --since $verificationStartedAt gateway 2>&1
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to read gateway logs; run this script from the repository root.'
}

$logText = $composeLogs -join [Environment]::NewLine
$apiAddresses = @(Get-DistinctUpstreamAddresses -LogText $logText -Port 8080 -RequestId $verificationId)
$agentAddresses = @(Get-DistinctUpstreamAddresses -LogText $logText -Port 5000 -RequestId $verificationId)

if ($apiAddresses.Count -lt 2) {
    throw "Only $($apiAddresses.Count) Spring upstream address(es) appeared in gateway logs; expected both API replicas."
}

if ($agentAddresses.Count -lt 2) {
    throw "Only $($agentAddresses.Count) Agent upstream address(es) appeared in gateway logs; expected both Agent replicas."
}

Write-Output "Gateway health: HTTP 200"
Write-Output "Public API requests: $Requests"
Write-Output "Agent replicas healthy: $($agentStatuses.Count)"
Write-Output "Distinct Spring upstreams observed: $($apiAddresses.Count)"
Write-Output "Distinct Agent upstreams observed: $($agentAddresses.Count)"
