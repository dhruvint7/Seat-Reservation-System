param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$RequestCount = 10
)

$ErrorActionPreference = "Stop"

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host " Per-User Limit Concurrency Test" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "Base URL : $BaseUrl"
Write-Host "Requests : $RequestCount"
Write-Host "Limit    : 4"
Write-Host ""

$TempDir = Join-Path $PSScriptRoot "user-limit-temp"

if (Test-Path $TempDir) {
    Remove-Item $TempDir -Recurse -Force
}

New-Item -ItemType Directory -Path $TempDir | Out-Null

# ---------------------------------------------------------
# 1. CREATE ADMIN TOKEN
# ---------------------------------------------------------

Write-Host "[1/5] Creating admin token..."

$AdminToken = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/auth/token?userId=admin&role=ADMIN"

Write-Host "Admin token OK." -ForegroundColor Green
Write-Host ""

# ---------------------------------------------------------
# 2. CREATE SHOW WITH 10 SEATS
# ---------------------------------------------------------

Write-Host "[2/5] Creating fresh show..."

$Seats = @(
    "A1",
    "A2",
    "A3",
    "A4",
    "A5",
    "A6",
    "A7",
    "A8",
    "A9",
    "A10"
)

$ShowRequest = @{
    name = "Per User Limit Test Show"
    seats = $Seats
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

Write-Host "Created show: $ShowId" -ForegroundColor Green
Write-Host ""

# ---------------------------------------------------------
# 3. CREATE ONE USER TOKEN
# ---------------------------------------------------------

Write-Host "[3/5] Creating user token..."

$UserId = "limit-test-user"

$UserToken = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/auth/token?userId=$UserId&role=USER"

Write-Host "User token OK." -ForegroundColor Green
Write-Host ""

# ---------------------------------------------------------
# 4. PREPARE CONCURRENT REQUESTS
# ---------------------------------------------------------

Write-Host "[4/5] Preparing $RequestCount concurrent requests..."

$Processes = @()

for ($i = 1; $i -le $RequestCount; $i++) {

    $Seat = "A$i"

    $BodyFile = Join-Path $TempDir "body-$i.json"
    $StatusFile = Join-Path $TempDir "status-$i.txt"
    $ResponseFile = Join-Path $TempDir "response-$i.json"
    $CmdFile = Join-Path $TempDir "worker-$i.cmd"

    $ReserveRequest = @{
        seats = @($Seat)
        idempotency_key = "user-limit-key-$i"
    } | ConvertTo-Json -Compress

    Set-Content `
        -Path $BodyFile `
        -Value $ReserveRequest `
        -Encoding ASCII

    $CmdContent = @"
curl.exe -s -o "$ResponseFile" -w "%%{http_code}" -X POST "$BaseUrl/shows/$ShowId/reserve" -H "Authorization: Bearer $UserToken" -H "Content-Type: application/json" -H "X-Request-ID: user-limit-$i" --data-binary "@$BodyFile" > "$StatusFile"
"@

    Set-Content `
        -Path $CmdFile `
        -Value $CmdContent `
        -Encoding ASCII

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
# WAIT
# ---------------------------------------------------------

Write-Host "Waiting for all requests..."

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

    if (Test-Path $StatusFile) {
        $Status = (Get-Content $StatusFile -Raw).Trim()
    }
    else {
        $Status = "000"
    }

    if ($Status -notmatch "^\d{3}$") {
        $Status = "000"
    }

    $Results += [PSCustomObject]@{
        Request = $i
        Seat = "A$i"
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

$Results |
    Group-Object Status |
    Sort-Object Name |
    Format-Table Name, Count -AutoSize

# ---------------------------------------------------------
# FINAL SHOW STATE
# ---------------------------------------------------------

Write-Host "[5/5] Checking final show state..."
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

$ReconciliationPassed = ($CalculatedTotal -eq $Total)

if ($ReconciliationPassed) {
    Write-Host "RECONCILIATION: PASS" -ForegroundColor Green
}
else {
    Write-Host "RECONCILIATION: FAIL" -ForegroundColor Red
}

Write-Host ""

# ---------------------------------------------------------
# VERDICT
# ---------------------------------------------------------

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host " PER-USER LIMIT TEST VERDICT" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

$TestPassed =
    ($Count201 -eq 4) -and
    ($Count409 -eq 6) -and
    ($Count401 -eq 0) -and
    ($Count403 -eq 0) -and
    ($CountOther4xx -eq 0) -and
    ($Count5xx -eq 0) -and
    ($Count000 -eq 0) -and
    ($Confirmed -eq 4) -and
    ($ReconciliationPassed)

if ($TestPassed) {

    Write-Host "PASS - Per-user limit enforced under concurrency." -ForegroundColor Green
    Write-Host ""
    Write-Host "Exactly 4 reservations were confirmed." -ForegroundColor Green
    Write-Host "6 requests were rejected with 409." -ForegroundColor Green
    Write-Host "User limit was not exceeded." -ForegroundColor Green
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