param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$RequestCount = 20
)

$ErrorActionPreference = "Stop"

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host " Seat Reservation Hot-Seat Test" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "Base URL : $BaseUrl"
Write-Host "Requests : $RequestCount"
Write-Host ""

# ---------------------------------------------------------
# TEMP DIRECTORY
# ---------------------------------------------------------

$TempDir = Join-Path $PSScriptRoot "burst-temp"

if (Test-Path $TempDir) {
    Remove-Item $TempDir -Recurse -Force
}

New-Item -ItemType Directory -Path $TempDir | Out-Null

# ---------------------------------------------------------
# HELPER
# ---------------------------------------------------------

function Get-StatusCode {
    param(
        [string]$File
    )

    if (-not (Test-Path $File)) {
        return "000"
    }

    $value = (Get-Content $File -Raw).Trim()

    if ($value -match "^\d{3}$") {
        return $value
    }

    return "000"
}

# ---------------------------------------------------------
# 1. ADMIN TOKEN
# ---------------------------------------------------------

Write-Host "[1/5] Creating admin token..."

$AdminToken = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/auth/token?userId=admin&role=ADMIN"

if ([string]::IsNullOrWhiteSpace($AdminToken)) {
    throw "Admin token was empty."
}

Write-Host "Admin token OK." -ForegroundColor Green
Write-Host ""

# ---------------------------------------------------------
# 2. CREATE FRESH SHOW
# ---------------------------------------------------------

Write-Host "[2/5] Creating fresh show..."

$ShowRequest = @{
    name = "Concurrency Test Show"
    seats = @(
        "A1",
        "A2",
        "A3",
        "A4",
        "A5"
    )
    price_paise = 10000
} | ConvertTo-Json -Compress

$Headers = @{
    Authorization = "Bearer $AdminToken"
}

$ShowResponse = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/shows" `
    -Headers $Headers `
    -ContentType "application/json" `
    -Body $ShowRequest

$ShowId = $ShowResponse.showId

if (-not $ShowId) {
    throw "Show creation failed. No showId returned."
}

Write-Host "Created show: $ShowId" -ForegroundColor Green
Write-Host ""

# ---------------------------------------------------------
# 3. PREPARE CONCURRENT REQUESTS
# ---------------------------------------------------------

Write-Host "[3/5] Preparing $RequestCount concurrent requests..."

$Processes = @()

for ($i = 1; $i -le $RequestCount; $i++) {

    # ---------------------------------------------
    # USER TOKEN
    # ---------------------------------------------

    $UserId = "hot-user-$i"

    $UserToken = Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/auth/token?userId=$UserId&role=USER"

    if ([string]::IsNullOrWhiteSpace($UserToken)) {
        throw "Token creation failed for $UserId"
    }

    # ---------------------------------------------
    # JSON BODY FILE
    # ---------------------------------------------

    $BodyFile = Join-Path $TempDir "body-$i.json"

    $ReserveRequest = @{
        seats = @("A1")
        idempotency_key = "hot-seat-key-$i"
    } | ConvertTo-Json -Compress

    # Write actual JSON to disk.
    # This avoids PowerShell -> cmd -> curl quote escaping problems.
    Set-Content `
        -Path $BodyFile `
        -Value $ReserveRequest `
        -Encoding ASCII

    # ---------------------------------------------
    # RESULT FILES
    # ---------------------------------------------

    $StatusFile = Join-Path $TempDir "status-$i.txt"
    $ResponseFile = Join-Path $TempDir "response-$i.json"
    $CmdFile = Join-Path $TempDir "worker-$i.cmd"

    # ---------------------------------------------
    # CURL COMMAND
    # ---------------------------------------------

    $CmdContent = @"
curl.exe -s -o "$ResponseFile" -w "%%{http_code}" -X POST "$BaseUrl/shows/$ShowId/reserve" -H "Authorization: Bearer $UserToken" -H "Content-Type: application/json" -H "X-Request-ID: hot-seat-$i" --data-binary "@$BodyFile" > "$StatusFile"
"@

    Set-Content `
        -Path $CmdFile `
        -Value $CmdContent `
        -Encoding ASCII

    # ---------------------------------------------
    # START PROCESS
    # ---------------------------------------------

    $Process = Start-Process `
        -FilePath "cmd.exe" `
        -ArgumentList "/c `"$CmdFile`"" `
        -WindowStyle Hidden `
        -PassThru

    $Processes += $Process
}

Write-Host "$RequestCount requests started." -ForegroundColor Green
Write-Host ""

# ---------------------------------------------------------
# 4. WAIT FOR ALL REQUESTS
# ---------------------------------------------------------

Write-Host "[4/5] Waiting for all requests..."

foreach ($Process in $Processes) {
    $Process.WaitForExit()
}

Write-Host "All requests completed." -ForegroundColor Green
Write-Host ""

# ---------------------------------------------------------
# READ RESULTS
# ---------------------------------------------------------

$Count201 = 0
$Count409 = 0
$Count401 = 0
$Count403 = 0
$CountOther4xx = 0
$Count5xx = 0
$Count000 = 0

$Results = @()

for ($i = 1; $i -le $RequestCount; $i++) {

    $StatusFile = Join-Path $TempDir "status-$i.txt"
    $ResponseFile = Join-Path $TempDir "response-$i.json"

    $Status = Get-StatusCode $StatusFile

    $Results += [PSCustomObject]@{
        Request = $i
        Status = $Status
    }

    switch -Regex ($Status) {

        "^201$" {
            $Count201++
            break
        }

        "^409$" {
            $Count409++
            break
        }

        "^401$" {
            $Count401++
            break
        }

        "^403$" {
            $Count403++
            break
        }

        "^4\d\d$" {
            $CountOther4xx++
            break
        }

        "^5\d\d$" {
            $Count5xx++
            break
        }

        "^000$" {
            $Count000++
            break
        }

        default {
            $Count000++
            break
        }
    }
}

# ---------------------------------------------------------
# RESULTS
# ---------------------------------------------------------

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host " RESULTS" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

Write-Host "201 Created : $Count201"
Write-Host "409 Conflict: $Count409"
Write-Host "401         : $Count401"
Write-Host "403         : $Count403"
Write-Host "Other 4xx   : $CountOther4xx"
Write-Host "5xx Errors  : $Count5xx"
Write-Host "Connection  : $Count000"

Write-Host ""

# Optional detailed distribution
$Results |
    Group-Object Status |
    Sort-Object Name |
    Format-Table Name, Count -AutoSize

# ---------------------------------------------------------
# 5. FINAL RECONCILIATION
# ---------------------------------------------------------

Write-Host "[5/5] Final reconciliation..."
Write-Host ""

$FinalState = Invoke-RestMethod `
    -Method Get `
    -Uri "$BaseUrl/shows/$ShowId"

$FinalState | ConvertTo-Json -Depth 10

Write-Host ""

$Total = [int]$FinalState.total
$Available = [int]$FinalState.available
$Held = [int]$FinalState.held
$Confirmed = [int]$FinalState.confirmed

$CalculatedTotal = $Available + $Held + $Confirmed

Write-Host "Reconciliation: $Available + $Held + $Confirmed = $CalculatedTotal"

if ($CalculatedTotal -eq $Total) {
    Write-Host "RECONCILIATION: PASS" -ForegroundColor Green
    $ReconciliationPassed = $true
}
else {
    Write-Host "RECONCILIATION: FAIL" -ForegroundColor Red
    $ReconciliationPassed = $false
}

Write-Host ""
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host " HOT-SEAT TEST VERDICT" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

# ---------------------------------------------------------
# EXPECTED RESULT
# ---------------------------------------------------------

$Expected201 = 1
$Expected409 = $RequestCount - 1

$HotSeatPassed =
    ($Count201 -eq $Expected201) -and
    ($Count409 -eq $Expected409) -and
    ($Count401 -eq 0) -and
    ($Count403 -eq 0) -and
    ($CountOther4xx -eq 0) -and
    ($Count5xx -eq 0) -and
    ($Count000 -eq 0) -and
    ($Confirmed -eq 1) -and
    $ReconciliationPassed

if ($HotSeatPassed) {

    Write-Host "PASS - Hot-seat concurrency behaved correctly." -ForegroundColor Green
    Write-Host ""
    Write-Host "Exactly one request confirmed A1." -ForegroundColor Green
    Write-Host "All other requests received 409." -ForegroundColor Green
    Write-Host "No 5xx errors." -ForegroundColor Green
    Write-Host "Reconciliation invariant passed." -ForegroundColor Green

}
else {

    Write-Host "FAIL - inspect the results above." -ForegroundColor Red

}

Write-Host ""

# ---------------------------------------------------------
# CLEANUP
# ---------------------------------------------------------

Remove-Item $TempDir -Recurse -Force