$ErrorActionPreference = "Stop"

$BaseUrl = "http://localhost:8080"
$TempRoot = Join-Path $env:TEMP "paytm-correctness-final"

if (Test-Path $TempRoot) {
    Remove-Item $TempRoot -Recurse -Force -ErrorAction SilentlyContinue
}

New-Item -ItemType Directory -Path $TempRoot -Force | Out-Null

$script:Failures = 0


# ============================================================
# HELPERS
# ============================================================

function Header {
    param([string]$Text)

    Write-Host ""
    Write-Host "=========================================" -ForegroundColor Cyan
    Write-Host $Text -ForegroundColor Cyan
    Write-Host "=========================================" -ForegroundColor Cyan
}

function Pass {
    param([string]$Text)

    Write-Host "PASS - $Text" -ForegroundColor Green
}

function Fail {
    param([string]$Text)

    Write-Host "FAIL - $Text" -ForegroundColor Red
    $script:Failures++
}


function Get-Token {
    param(
        [string]$UserId,
        [string]$Role
    )

    $token = Invoke-RestMethod `
        -UseBasicParsing `
        -Method Post `
        -Uri "$BaseUrl/auth/token?userId=$UserId&role=$Role"

    return [string]$token
}


function Create-Show {
    param(
        [string[]]$Seats,
        [string]$Name
    )

    $body = @{
        name = $Name
        seats = $Seats
        price_paise = 10000
    } | ConvertTo-Json -Compress

    return Invoke-RestMethod `
        -UseBasicParsing `
        -Method Post `
        -Uri "$BaseUrl/shows" `
        -Headers @{
            Authorization = "Bearer $script:AdminToken"
        } `
        -ContentType "application/json" `
        -Body $body
}


function Get-Show {
    param([long]$ShowId)

    return Invoke-RestMethod `
        -UseBasicParsing `
        -Method Get `
        -Uri "$BaseUrl/shows/$ShowId"
}


function Reserve {
    param(
        [long]$ShowId,
        [string]$Token,
        [string[]]$Seats,
        [string]$Key
    )

    $body = @{
        seats = $Seats
        idempotency_key = $Key
    } | ConvertTo-Json -Compress

    try {

        $response = Invoke-WebRequest `
            -UseBasicParsing `
            -Method Post `
            -Uri "$BaseUrl/shows/$ShowId/reserve" `
            -Headers @{
                Authorization = "Bearer $Token"
                "X-Request-ID" = [guid]::NewGuid().ToString()
            } `
            -ContentType "application/json" `
            -Body $body

        return @{
            Status = [int]$response.StatusCode
            Body = [string]$response.Content
        }
    }
    catch {

        $status = 0
        $body = ""

        if ($null -ne $_.Exception.Response) {

            try {
                $status = [int]$_.Exception.Response.StatusCode.value__
            }
            catch {}

            try {
                $reader = New-Object System.IO.StreamReader(
                    $_.Exception.Response.GetResponseStream()
                )

                $body = $reader.ReadToEnd()
                $reader.Close()
            }
            catch {}
        }

        return @{
            Status = $status
            Body = $body
        }
    }
}


# ============================================================
# CONCURRENT CURL RUNNER
# IMPORTANT:
# %%{http_code} is required because this is a .cmd file
# ============================================================

function Run-Concurrent {

    param(
        [long]$ShowId,
        [string]$Token,
        [string[]]$Seats,
        [string[]]$Keys,
        [string]$Prefix
    )

    $dir = Join-Path $TempRoot ([guid]::NewGuid().ToString())

    New-Item `
        -ItemType Directory `
        -Path $dir `
        -Force | Out-Null

    $processes = @()

    for ($i = 0; $i -lt $Keys.Count; $i++) {

        $bodyFile = Join-Path $dir "body-$i.json"
        $outputFile = Join-Path $dir "response-$i.json"
        $statusFile = Join-Path $dir "status-$i.txt"
        $cmdFile = Join-Path $dir "worker-$i.cmd"

        $body = @{
            seats = @($Seats[$i])
            idempotency_key = $Keys[$i]
        } | ConvertTo-Json -Compress

        Set-Content `
            -Path $bodyFile `
            -Value $body `
            -Encoding ASCII

        $cmdContent = @"
curl.exe -s -o "$outputFile" -w "%%{http_code}" -X POST "$BaseUrl/shows/$ShowId/reserve" -H "Authorization: Bearer $Token" -H "Content-Type: application/json" -H "X-Request-ID: $Prefix-$i" --data-binary "@$bodyFile" > "$statusFile"
"@

        Set-Content `
            -Path $cmdFile `
            -Value $cmdContent `
            -Encoding ASCII

        $process = Start-Process `
            -FilePath "cmd.exe" `
            -ArgumentList "/c `"$cmdFile`"" `
            -WindowStyle Hidden `
            -PassThru

        $processes += $process
    }

    Write-Host "Started $($Keys.Count) concurrent requests..."

    foreach ($process in $processes) {
        $process.WaitForExit()
    }

    Start-Sleep -Milliseconds 300

    $results = @()

    for ($i = 0; $i -lt $Keys.Count; $i++) {

        $statusFile = Join-Path $dir "status-$i.txt"
        $outputFile = Join-Path $dir "response-$i.json"

        $status = 0
        $body = ""

        if (Test-Path $statusFile) {

            $raw = Get-Content `
                $statusFile `
                -Raw `
                -ErrorAction SilentlyContinue

            if ($null -ne $raw) {

                $raw = $raw.Trim()

                if ($raw -match "^\d{3}$") {
                    $status = [int]$raw
                }
            }
        }

        if (Test-Path $outputFile) {

            $rawBody = Get-Content `
                $outputFile `
                -Raw `
                -ErrorAction SilentlyContinue

            if ($null -ne $rawBody) {
                $body = $rawBody
            }
        }

        $results += [PSCustomObject]@{
            Status = $status
            Body = $body
        }
    }

    Remove-Item `
        $dir `
        -Recurse `
        -Force `
        -ErrorAction SilentlyContinue

    return $results
}


function Check-Reconciliation {

    param(
        [object]$Show,
        [string]$Name
    )

    $sum =
        [int]$Show.available +
        [int]$Show.held +
        [int]$Show.confirmed

    if ($sum -eq [int]$Show.total) {

        Pass "$Name reconciliation: $($Show.available) + $($Show.held) + $($Show.confirmed) = $($Show.total)"

    }
    else {

        Fail "$Name reconciliation FAILED"
    }
}


# ============================================================
# SETUP
# ============================================================

Header "PAYTM SEAT RESERVATION - FINAL CORRECTNESS SUITE"

Write-Host "Base URL: $BaseUrl"
Write-Host ""

Write-Host "[SETUP] Creating tokens..."

$script:AdminToken = Get-Token "admin" "ADMIN"
$user1 = Get-Token "test-user-1" "USER"
$user2 = Get-Token "test-user-2" "USER"

if (
    [string]::IsNullOrWhiteSpace($script:AdminToken) -or
    [string]::IsNullOrWhiteSpace($user1) -or
    [string]::IsNullOrWhiteSpace($user2)
) {
    throw "Token generation failed."
}

Pass "Authentication tokens created"


# ============================================================
# TEST 1 - HOT SEAT
# ============================================================

Header "TEST 1 - HOT SEAT CONCURRENCY"

$show = Create-Show `
    @("A1","A2","A3","A4","A5") `
    "Hot Seat Final Test"

$showId = $show.showId

$seats = @()
$keys = @()

for ($i = 1; $i -le 20; $i++) {
    $seats += "A1"
    $keys += "hot-$i"
}

$results = Run-Concurrent `
    $showId `
    $user1 `
    $seats `
    $keys `
    "hot"

$c201 = @($results | Where-Object Status -eq 201).Count
$c409 = @($results | Where-Object Status -eq 409).Count
$c5xx = @($results | Where-Object { $_.Status -ge 500 }).Count
$c0 = @($results | Where-Object Status -eq 0).Count

Write-Host "201: $c201"
Write-Host "409: $c409"
Write-Host "5xx: $c5xx"
Write-Host "Connection: $c0"

if (
    $c201 -eq 1 -and
    $c409 -eq 19 -and
    $c5xx -eq 0 -and
    $c0 -eq 0
) {
    Pass "Hot-seat concurrency"
}
else {
    Fail "Hot-seat concurrency"
}

$state = Get-Show $showId

if ([int]$state.confirmed -eq 1) {
    Pass "Hot-seat confirmed count = 1"
}
else {
    Fail "Hot-seat confirmed count should be 1"
}

Check-Reconciliation $state "Hot-seat"


# ============================================================
# TEST 2 - PER USER LIMIT
# ============================================================

Header "TEST 2 - PER USER LIMIT"

$seats = @(
    "A1","A2","A3","A4","A5",
    "A6","A7","A8","A9","A10"
)

$show = Create-Show `
    $seats `
    "Per User Final Test"

$showId = $show.showId

$keys = @()

for ($i = 1; $i -le 10; $i++) {
    $keys += "limit-$i"
}

$results = Run-Concurrent `
    $showId `
    $user1 `
    $seats `
    $keys `
    "limit"

$c201 = @($results | Where-Object Status -eq 201).Count
$c409 = @($results | Where-Object Status -eq 409).Count
$c5xx = @($results | Where-Object { $_.Status -ge 500 }).Count
$c0 = @($results | Where-Object Status -eq 0).Count

Write-Host "201: $c201"
Write-Host "409: $c409"
Write-Host "5xx: $c5xx"
Write-Host "Connection: $c0"

if (
    $c201 -eq 4 -and
    $c409 -eq 6 -and
    $c5xx -eq 0 -and
    $c0 -eq 0
) {
    Pass "Per-user limit = 4"
}
else {
    Fail "Per-user limit"
}

$state = Get-Show $showId

if ([int]$state.confirmed -eq 4) {
    Pass "Exactly 4 seats confirmed"
}
else {
    Fail "Confirmed count should be 4"
}

Check-Reconciliation $state "Per-user limit"


# ============================================================
# TEST 3 - BASIC IDEMPOTENCY
# ============================================================

Header "TEST 3 - IDEMPOTENCY SAME KEY SAME BODY"

$show = Create-Show `
    @("A1","A2","A3") `
    "Idempotency Basic Test"

$showId = $show.showId

$key = "basic-$([guid]::NewGuid())"

$r1 = Reserve `
    $showId `
    $user1 `
    @("A1") `
    $key

$r2 = Reserve `
    $showId `
    $user1 `
    @("A1") `
    $key

Write-Host "First : $($r1.Status)"
Write-Host "Second: $($r2.Status)"

if (
    $r1.Status -eq 201 -and
    $r2.Status -eq 201
) {

    try {

        $o1 = $r1.Body | ConvertFrom-Json
        $o2 = $r2.Body | ConvertFrom-Json

        # IMPORTANT:
        # ReserveResponse uses reservationId, NOT reservation_id
        $id1 = $o1.reservationId
        $id2 = $o2.reservationId

        Write-Host "Reservation 1: $id1"
        Write-Host "Reservation 2: $id2"

        if (
            -not [string]::IsNullOrWhiteSpace([string]$id1) -and
            -not [string]::IsNullOrWhiteSpace([string]$id2) -and
            ([string]$id1 -eq [string]$id2)
        ) {
            Pass "Same key returned same reservation"
        }
        else {
            Fail "Reservation IDs missing or different"
        }

    }
    catch {

        Fail "Could not parse idempotency response"
    }

}
else {

    Fail "Idempotency requests did not both return 201"
}


# ============================================================
# TEST 4 - SAME KEY DIFFERENT BODY
# ============================================================

Header "TEST 4 - SAME KEY DIFFERENT BODY"

$key = "different-$([guid]::NewGuid())"

$r1 = Reserve `
    $showId `
    $user1 `
    @("A2") `
    $key

$r2 = Reserve `
    $showId `
    $user1 `
    @("A3") `
    $key

Write-Host "First : $($r1.Status)"
Write-Host "Second: $($r2.Status)"

if (
    $r1.Status -eq 201 -and
    $r2.Status -eq 409
) {
    Pass "Same key + different body = 409"
}
else {
    Fail "Same key + different body"
}


# ============================================================
# TEST 5 - CONCURRENT SAME KEY
# ============================================================

Header "TEST 5 - CONCURRENT SAME-KEY RETRIES"

$show = Create-Show `
    @("A1","A2","A3","A4","A5") `
    "Concurrent Idempotency Test"

$showId = $show.showId

$sameKey = "concurrent-$([guid]::NewGuid())"

$seats = @()
$keys = @()

for ($i = 1; $i -le 20; $i++) {
    $seats += "A1"
    $keys += $sameKey
}

$results = Run-Concurrent `
    $showId `
    $user1 `
    $seats `
    $keys `
    "idem"

$c201 = @($results | Where-Object Status -eq 201).Count
$c409 = @($results | Where-Object Status -eq 409).Count
$c5xx = @($results | Where-Object { $_.Status -ge 500 }).Count
$c0 = @($results | Where-Object Status -eq 0).Count

Write-Host "201: $c201"
Write-Host "409: $c409"
Write-Host "5xx: $c5xx"
Write-Host "Connection: $c0"

$ids = @()

foreach ($result in $results) {

    if (
        $result.Status -eq 201 -and
        -not [string]::IsNullOrWhiteSpace($result.Body)
    ) {

        try {

            $obj = $result.Body | ConvertFrom-Json

            # IMPORTANT: camelCase
            if ($null -ne $obj.reservationId) {
                $ids += [string]$obj.reservationId
            }

        }
        catch {}
    }
}

$uniqueIds = @($ids | Sort-Object -Unique)

Write-Host "Successful responses: $($ids.Count)"
Write-Host "Unique reservation IDs: $($uniqueIds.Count)"

if (
    $c5xx -eq 0 -and
    $c0 -eq 0 -and
    $uniqueIds.Count -eq 1
) {
    Pass "Concurrent same-key produced exactly one reservation"
}
else {
    Fail "Concurrent same-key idempotency"
}

$state = Get-Show $showId

if ([int]$state.confirmed -eq 1) {
    Pass "Concurrent idempotency confirmed count = 1"
}
else {
    Fail "Concurrent idempotency confirmed count should be 1"
}

Check-Reconciliation $state "Concurrent idempotency"


# ============================================================
# TEST 6 - INVALID SEAT
# ============================================================

Header "TEST 6 - INVALID SEAT"

$show = Create-Show `
    @("A1","A2") `
    "Invalid Seat Test"

$showId = $show.showId

$r = Reserve `
    $showId `
    $user1 `
    @("INVALID-SEAT") `
    "invalid-$([guid]::NewGuid())"

Write-Host "Status: $($r.Status)"

if ($r.Status -eq 409) {
    Pass "Invalid seat rejected with 409"
}
else {
    Fail "Invalid seat status"
}


# ============================================================
# TEST 7 - JWT IDENTITY
# ============================================================

Header "TEST 7 - JWT IDENTITY"

$show = Create-Show `
    @("A1","A2") `
    "Identity Test"

$showId = $show.showId

$r = Reserve `
    $showId `
    $user2 `
    @("A1") `
    "identity-$([guid]::NewGuid())"

if ($r.Status -eq 201) {

    try {

        $obj = $r.Body | ConvertFrom-Json

        # IMPORTANT: camelCase
        Write-Host "Returned userId: $($obj.userId)"

        if ($obj.userId -eq "test-user-2") {
            Pass "Identity comes from JWT"
        }
        else {
            Fail "Identity mismatch"
        }

    }
    catch {

        Fail "Could not parse identity response"
    }

}
else {

    Fail "Identity reservation failed"
}


# ============================================================
# TEST 8 - CANCEL + REBOOK
# ============================================================

Header "TEST 8 - CANCEL + REBOOK"

$show = Create-Show `
    @("A1","A2","A3") `
    "Cancel Rebook Test"

$showId = $show.showId

$r = Reserve `
    $showId `
    $user1 `
    @("A1") `
    "cancel-$([guid]::NewGuid())"

if ($r.Status -ne 201) {

    Fail "Initial reservation failed"

}
else {

    try {

        $obj = $r.Body | ConvertFrom-Json

        # IMPORTANT: camelCase
        $reservationId = $obj.reservationId

        Write-Host "Reservation ID: $reservationId"

        if ([string]::IsNullOrWhiteSpace([string]$reservationId)) {

            Fail "Reservation ID missing"

        }
        else {

            try {

                $cancel = Invoke-WebRequest `
                    -UseBasicParsing `
                    -Method Post `
                    -Uri "$BaseUrl/reservations/$reservationId/cancel" `
                    -Headers @{
                        Authorization = "Bearer $user1"
                        "X-Request-ID" = [guid]::NewGuid().ToString()
                    }

                Write-Host "Cancel status: $($cancel.StatusCode)"

                if ([int]$cancel.StatusCode -eq 200) {
                    Pass "Cancellation succeeded"
                }
                else {
                    Fail "Cancellation returned unexpected status"
                }

            }
            catch {

                Fail "Cancellation request failed"
            }


            $rebook = Reserve `
                $showId `
                $user2 `
                @("A1") `
                "rebook-$([guid]::NewGuid())"

            Write-Host "Rebook status: $($rebook.Status)"

            if ($rebook.Status -eq 201) {
                Pass "Cancelled seat successfully re-booked"
            }
            else {
                Fail "Cancelled seat could not be re-booked"
            }
        }

    }
    catch {

        Fail "Could not parse cancellation reservation"
    }
}

$state = Get-Show $showId

Check-Reconciliation $state "Cancel + rebook"


# ============================================================
# TEST 9 - PROMETHEUS
# ============================================================

Header "TEST 9 - PROMETHEUS METRICS"

try {

    $metricsResponse = Invoke-WebRequest `
        -UseBasicParsing `
        -Method Get `
        -Uri "$BaseUrl/actuator/prometheus"

    $metrics = [string]$metricsResponse.Content

    if ($metrics -match "reservations_confirmed_total") {
        Pass "Confirmed counter exists"
    }
    else {
        Fail "Confirmed counter missing"
    }

    if ($metrics -match "reservations_declined_total") {
        Pass "Declined counter exists"
    }
    else {
        Fail "Declined counter missing"
    }

    if ($metrics -match "reservations_seats_available") {
        Pass "Available seats gauge exists"
    }
    else {
        Fail "Available seats gauge missing"
    }

}
catch {

    Fail "Prometheus endpoint unavailable"
}


# ============================================================
# FINAL
# ============================================================

Header "FINAL RESULT"

Write-Host ""

if ($script:Failures -eq 0) {

    Write-Host "ALL TESTS PASSED." -ForegroundColor Green
    Write-Host ""

    Write-Host "HOT SEAT               : PASS"
    Write-Host "PER USER LIMIT         : PASS"
    Write-Host "IDEMPOTENCY            : PASS"
    Write-Host "DIFFERENT BODY         : PASS"
    Write-Host "CONCURRENT IDEMPOTENCY : PASS"
    Write-Host "INVALID SEAT           : PASS"
    Write-Host "JWT IDENTITY           : PASS"
    Write-Host "CANCEL + REBOOK        : PASS"
    Write-Host "RECONCILIATION         : PASS"
    Write-Host "PROMETHEUS             : PASS"

    Write-Host ""
    Write-Host "=========================================" -ForegroundColor Green
    Write-Host " APPLICATION CORRECTNESS SUITE PASSED"
    Write-Host "=========================================" -ForegroundColor Green

}
else {

    Write-Host "$($script:Failures) TEST(S) FAILED." -ForegroundColor Red
    Write-Host ""
    Write-Host "DO NOT COMMIT YET." -ForegroundColor Red
}


# Cleanup
Remove-Item `
    $TempRoot `
    -Recurse `
    -Force `
    -ErrorAction SilentlyContinue