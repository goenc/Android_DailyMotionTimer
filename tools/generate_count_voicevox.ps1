param(
    [string]$EngineBaseUrl = "http://127.0.0.1:50021",
    [int]$SpeakerId = 119
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectRoot "app\src\main\res\raw"
$tempDir = Join-Path $env:TEMP "voicevox_count_generation"
$pythonCandidates = @(
    "C:\Users\gonec\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe",
    "python"
)
$pythonPath = $pythonCandidates | Where-Object { $_ -eq "python" -or (Test-Path $_) } | Select-Object -First 1

if (-not $pythonPath) {
    throw "Python runtime was not found for wav normalization."
}

$normalizerScript = Join-Path $PSScriptRoot "trim_normalize_wav.py"
$readings = [ordered]@{
    0 = "ゼロ"
    1 = "いち"
    2 = "に"
    3 = "さん"
    4 = "よん"
    5 = "ご"
    6 = "ろく"
    7 = "なな"
    8 = "はち"
    9 = "きゅう"
    10 = "じゅう"
    11 = "じゅういち"
    12 = "じゅうに"
    13 = "じゅうさん"
    14 = "じゅうよん"
    15 = "じゅうご"
    16 = "じゅうろく"
    17 = "じゅうなな"
    18 = "じゅうはち"
    19 = "じゅうきゅう"
    20 = "にじゅう"
    21 = "にじゅういち"
    22 = "にじゅうに"
    23 = "にじゅうさん"
    24 = "にじゅうよん"
    25 = "にじゅうご"
    26 = "にじゅうろく"
    27 = "にじゅうなな"
    28 = "にじゅうはち"
    29 = "にじゅうきゅう"
    30 = "さんじゅう"
    31 = "さんじゅういち"
    32 = "さんじゅうに"
    33 = "さんじゅうさん"
    34 = "さんじゅうよん"
    35 = "さんじゅうご"
    36 = "さんじゅうろく"
    37 = "さんじゅうなな"
    38 = "さんじゅうはち"
    39 = "さんじゅうきゅう"
    40 = "よんじゅう"
    41 = "よんじゅういち"
    42 = "よんじゅうに"
    43 = "よんじゅうさん"
    44 = "よんじゅうよん"
    45 = "よんじゅうご"
    46 = "よんじゅうろく"
    47 = "よんじゅうなな"
    48 = "よんじゅうはち"
    49 = "よんじゅうきゅう"
    50 = "ごじゅう"
}

New-Item -ItemType Directory -Force -Path $tempDir | Out-Null

foreach ($entry in $readings.GetEnumerator()) {
    $count = $entry.Key
    $text = $entry.Value
    $tempWav = Join-Path $tempDir ("count_{0}.wav" -f $count)
    $outputWav = Join-Path $outputDir ("count_{0}.wav" -f $count)

    $encodedText = [System.Uri]::EscapeDataString($text)
    $query = Invoke-RestMethod -Uri "$EngineBaseUrl/audio_query?text=$encodedText&speaker=$SpeakerId" -Method Post
    $query.speedScale = 1.12
    $query.pitchScale = 0.03
    $query.intonationScale = 1.10
    $query.volumeScale = 1.0
    $query.prePhonemeLength = 0.03
    $query.postPhonemeLength = 0.04
    $query.outputSamplingRate = 24000
    $query.outputStereo = $false

    $queryJson = $query | ConvertTo-Json -Depth 32
    $synthesisBody = [System.Text.Encoding]::UTF8.GetBytes($queryJson)
    $response = Invoke-WebRequest -Uri "$EngineBaseUrl/synthesis?speaker=$SpeakerId&enable_interrogative_upspeak=false" `
        -Method Post `
        -ContentType "application/json; charset=utf-8" `
        -Body $synthesisBody
    [System.IO.File]::WriteAllBytes($tempWav, $response.Content)

    & $pythonPath $normalizerScript $tempWav $outputWav
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to normalize wav for count $count."
    }
}

Write-Output "Generated VOICEVOX count wav files in $outputDir"
