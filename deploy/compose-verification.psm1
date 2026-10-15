function Get-DistinctUpstreamAddresses {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string]$LogText,

        [Parameter(Mandatory)]
        [ValidateRange(1, 65535)]
        [int]$Port,

        [Parameter(Mandatory)]
        [ValidatePattern('^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$')]
        [string]$RequestId
    )

    $addresses = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase
    )
    $pattern = 'probe=(?<requestId>[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\s+method=.*?\s+upstream=(?<addresses>.*?)\s+upstream_status=(?<statuses>.*?)\s+request_time='

    foreach ($match in [regex]::Matches($LogText, $pattern)) {
        if (-not $match.Groups['requestId'].Value.Equals($RequestId, [System.StringComparison]::OrdinalIgnoreCase)) {
            continue
        }

        $upstreamAddresses = [regex]::Split($match.Groups['addresses'].Value, '\s*,\s*|\s+:\s+')
        $upstreamStatuses = [regex]::Split($match.Groups['statuses'].Value, '\s*,\s*|\s+:\s+')
        $attemptCount = [Math]::Min($upstreamAddresses.Count, $upstreamStatuses.Count)

        for ($attempt = 0; $attempt -lt $attemptCount; $attempt++) {
            $address = $upstreamAddresses[$attempt].Trim()
            $status = $upstreamStatuses[$attempt].Trim()
            if ($status -match '^2[0-9]{2}$' -and $address -match ':[0-9]+$' -and $address.EndsWith(":$Port")) {
                [void]$addresses.Add($address)
            }
        }
    }

    return @($addresses)
}

function Get-ComposeProjectName {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string]$RepositoryRoot
    )

    $canonicalPath = (Resolve-Path -LiteralPath $RepositoryRoot -ErrorAction Stop).Path
    if ($env:OS -eq 'Windows_NT') {
        $canonicalPath = $canonicalPath.ToLowerInvariant()
    }

    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $pathBytes = [System.Text.Encoding]::UTF8.GetBytes($canonicalPath)
        $fingerprint = [BitConverter]::ToString($sha256.ComputeHash($pathBytes)).Replace('-', '').Substring(0, 16).ToLowerInvariant()
    }
    finally {
        $sha256.Dispose()
    }

    return "ningdu-mind-$fingerprint"
}

Export-ModuleMember -Function Get-DistinctUpstreamAddresses, Get-ComposeProjectName
