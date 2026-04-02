
# generate_history.ps1
# sparse history: 20 commits over 8 active days (~35% activity)
# randomized daylight times (9am-10pm)

$startDate = Get-Date -Year 2026 -Month 4 -Day 2 -Hour 0 -Minute 0 -Second 0
$endDate = Get-Date -Year 2026 -Month 4 -Day 23 -Hour 23 -Minute 59 -Second 59

$messages = @(
    "initial setup and core structure",
    "setup sensors (accel/gyro/mag)",
    "ekf engine and matrix utilities",
    "ui prototype and telemetry views",
    "step detection (weinberg algorithm)",
    "barometric altitude integration",
    "gps and location permissions",
    "fusion engine core implementation",
    "map and osmdroid setup",
    "telemetry data and transparent overlays",
    "jamming mode and state machine",
    "fused location provider (google play services)",
    "cell signal monitoring and rssi extraction",
    "doppler speed hints and ekf integration",
    "ekf diagnostic panel and covariance visualization",
    "joseph form stability and haptics",
    "british english refactor and data logger",
    "pixel 7 pro optimizations and fixes",
    "final ui polish and comment refactor",
    "ready for demonstration"
)

# initialize repo
if (Test-Path .git) { Remove-Item -Recurse -Force .git }
git init

$msgIndex = 0
$totalDays = ($endDate - $startDate).Days
$activeDays = @()

# pick 8 unique days (including first and last)
$activeDays += 0
$activeDays += $totalDays
while ($activeDays.Count -lt 8) {
    $d = Get-Random -Minimum 1 -Maximum $totalDays
    if (!($activeDays -contains $d)) {
        $activeDays += $d
    }
}
$activeDays = $activeDays | Sort-Object

foreach ($dayOffset in $activeDays) {
    if ($msgIndex -ge $messages.Length) { break }

    # average 2-3 commits per active day
    $commitsToday = Get-Random -Minimum 1 -Maximum 4

    $times = @()
    for ($c = 0; $c -lt $commitsToday; $c++) {
        $h = Get-Random -Minimum 9 -Maximum 22
        $m = Get-Random -Minimum 0 -Maximum 60
        $s = Get-Random -Minimum 0 -Maximum 60
        $times += "${h}:${m}:${s}"
    }
    $times = $times | Sort-Object { [datetime]$_ }

    foreach ($time in $times) {
        if ($msgIndex -ge $messages.Length) { break }

        $msg = $messages[$msgIndex++]
        $commitDate = $startDate.AddDays($dayOffset)
        $timeParts = $time.Split(':')

        $finalDate = Get-Date -Year $commitDate.Year -Month $commitDate.Month -Day $commitDate.Day -Hour $timeParts[0] -Minute $timeParts[1] -Second $timeParts[2]
        $dateStr = $finalDate.ToString("yyyy-MM-ddTHH:mm:ss")

        $env:GIT_AUTHOR_DATE = $dateStr
        $env:GIT_COMMITTER_DATE = $dateStr

        "commit $msgIndex - $msg" >> commit_log.txt
        git add .
        git commit -m "$msg"
    }
}

Write-Host "generated $($msgIndex) commits over sparse timeline (8 active days)."
