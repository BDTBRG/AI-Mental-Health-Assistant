$ErrorActionPreference = 'Stop'
Import-Module "$PSScriptRoot/compose-verification.psm1" -Force

Describe 'Get-DistinctUpstreamAddresses' {
    It 'counts only successful Agent upstreams and parses retry chains' {
        $requestId = '11111111-1111-4111-8111-111111111111'
        $logText = @"
2026-10-14T12:00:00+08:00 probe=$requestId method=GET status=200 upstream=172.20.0.2:8080 upstream_status=200 request_time=0.002
2026-10-14T12:00:01+08:00 probe=$requestId method=GET status=200 upstream=172.20.0.3:5000 upstream_status=200 request_time=0.003
2026-10-14T12:00:02+08:00 probe=$requestId method=GET status=502 upstream=172.20.0.4:5000, 172.20.0.5:5000 upstream_status=502, 502 request_time=0.100
2026-10-14T12:00:03+08:00 probe=$requestId method=GET status=200 upstream=172.20.0.6:5000, 172.20.0.7:5000 upstream_status=502, 200 request_time=0.100
2026-10-14T12:00:04+08:00 probe=22222222-2222-4222-8222-222222222222 method=GET status=200 upstream=172.20.0.8:5000 upstream_status=200 request_time=0.003
"@

        $addresses = @(Get-DistinctUpstreamAddresses -LogText $logText -Port 5000 -RequestId $requestId)

        $addresses.Count | Should Be 2
        ($addresses -contains '172.20.0.3:5000') | Should Be $true
        ($addresses -contains '172.20.0.7:5000') | Should Be $true
        ($addresses -contains '172.20.0.4:5000') | Should Be $false
        ($addresses -contains '172.20.0.5:5000') | Should Be $false
        ($addresses -contains '172.20.0.6:5000') | Should Be $false
    }

    It 'returns no backends when the log has no matching upstream port' {
        $addresses = @(Get-DistinctUpstreamAddresses -LogText 'probe=11111111-1111-4111-8111-111111111111 status=200 upstream=172.20.0.2:8080 upstream_status=200 request_time=0.002' -Port 5000 -RequestId '11111111-1111-4111-8111-111111111111')

        $addresses.Count | Should Be 0
    }
}

Describe 'Get-ComposeProjectName' {
    It 'creates a stable checkout-specific name' {
        $repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
        $parentRoot = Split-Path $repositoryRoot -Parent

        $name = Get-ComposeProjectName -RepositoryRoot $repositoryRoot
        $sameName = Get-ComposeProjectName -RepositoryRoot $repositoryRoot
        $otherName = Get-ComposeProjectName -RepositoryRoot $parentRoot

        $name | Should Match '^ningdu-mind-[0-9a-f]{16}$'
        $sameName | Should Be $name
        $otherName | Should Not Be $name
    }
}

Describe 'Compose Qdrant startup gate' {
    It 'uses the Agent image and a bounded /readyz startup gate' {
        $composeFile = Join-Path $PSScriptRoot '..\compose.yaml'
        $envFile = Join-Path $PSScriptRoot '.env.compose.example'
        $projectName = 'ningdu-mind-pester-test'
        $originalHttpProxy = $env:HTTP_PROXY
        $originalHttpsProxy = $env:HTTPS_PROXY
        try {
            $env:HTTP_PROXY = 'http://build-proxy.invalid:8080'
            $env:HTTPS_PROXY = 'http://build-proxy.invalid:8080'
            $configLines = & docker compose -p $projectName -f $composeFile --env-file $envFile config --format json 2>&1
        } finally {
            if ($null -eq $originalHttpProxy) {
                Remove-Item Env:HTTP_PROXY -ErrorAction SilentlyContinue
            } else {
                $env:HTTP_PROXY = $originalHttpProxy
            }
            if ($null -eq $originalHttpsProxy) {
                Remove-Item Env:HTTPS_PROXY -ErrorAction SilentlyContinue
            } else {
                $env:HTTPS_PROXY = $originalHttpsProxy
            }
        }
        $LASTEXITCODE | Should Be 0
        $config = ($configLines -join [Environment]::NewLine) | ConvertFrom-Json

        ($config.services.qdrant.PSObject.Properties.Name -contains 'healthcheck') | Should Be $false
        $config.services.'qdrant-ready'.image | Should Be "ningdu-mind-agent:$projectName"
        $config.services.'agent-1'.image | Should Be "ningdu-mind-agent:$projectName"
        $config.services.'agent-2'.image | Should Be "ningdu-mind-agent:$projectName"
        $config.services.'api-1'.image | Should Be "ningdu-mind-server:$projectName"
        $config.services.'api-2'.image | Should Be "ningdu-mind-server:$projectName"
        $config.services.gateway.image | Should Be "ningdu-mind-web:$projectName"
        $config.services.'qdrant-ready'.build.dockerfile | Should Be 'agent/Dockerfile'
        $config.services.'api-1'.build.dockerfile | Should Be 'server/Dockerfile'
        $config.services.gateway.build.dockerfile | Should Be 'Dockerfile.frontend'
        $agentBuildArgs = @($config.services.'qdrant-ready'.build.args.PSObject.Properties.Name)
        $apiBuildArgs = @($config.services.'api-1'.build.args.PSObject.Properties.Name)
        $gatewayBuildArgs = @($config.services.gateway.build.args.PSObject.Properties.Name)
        ($agentBuildArgs -contains 'HTTP_PROXY') | Should Be $true
        ($agentBuildArgs -contains 'HTTPS_PROXY') | Should Be $true
        ($apiBuildArgs -contains 'HTTP_PROXY') | Should Be $true
        ($apiBuildArgs -contains 'HTTPS_PROXY') | Should Be $true
        ($gatewayBuildArgs -contains 'HTTP_PROXY') | Should Be $true
        ($gatewayBuildArgs -contains 'HTTPS_PROXY') | Should Be $true
        foreach ($replica in @('agent-1', 'agent-2', 'api-2')) {
            ($config.services.$replica.PSObject.Properties.Name -contains 'build') | Should Be $false
            $config.services.$replica.pull_policy | Should Be 'never'
        }
        ($config.services.'qdrant-ready'.entrypoint -join ' ') | Should Be 'python -c'
        $config.services.'qdrant-ready'.restart | Should Be 'no'
        $config.services.'qdrant-ready'.depends_on.qdrant.condition | Should Be 'service_started'
        (($config.services.'qdrant-ready'.command -join ' ') -match '/readyz') | Should Be $true
        $config.services.'agent-1'.depends_on.'qdrant-ready'.condition | Should Be 'service_completed_successfully'
        $config.services.'agent-2'.depends_on.'qdrant-ready'.condition | Should Be 'service_completed_successfully'

        $qdrantCommand = $config.services.'qdrant-ready'.command -join [Environment]::NewLine
        $qdrantCommand | Should Match 'while time\.monotonic\(\) < deadline'
        $qdrantCommand | Should Match 'else:\s+raise RuntimeError'
        $composeSource = Get-Content -LiteralPath $composeFile -Raw
        ($composeSource -match '(?m)^name:\s+') | Should Be $false
    }
}

Describe 'Compose runbook safety' {
    It 'does not overwrite an existing local environment file' {
        $readme = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'README.md') -Raw
        $readme | Should Match 'Copy-Item deploy/\.env\.compose\.example \.env\.compose -NoClobber'
    }
}
