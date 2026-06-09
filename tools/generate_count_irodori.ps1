param(
    [string]$IrodoriRoot = "C:\Users\gonec\Documents\Irodori TTS",
    [string]$ReferenceWav = "C:\Users\gonec\Downloads\vd_20260609_080441_373987_1162485456990938168.wav",
    [string]$Checkpoint = "Aratako/Irodori-TTS-500M-v3",
    [double]$DurationScale = 0.72,
    [int]$NumSteps = 12
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectRoot "app\src\main\res\raw"
$tempDir = Join-Path $env:TEMP "irodori_count_generation"
$pythonPath = Join-Path $IrodoriRoot ".venv\Scripts\python.exe"
$inferScript = Join-Path $IrodoriRoot "infer.py"
$normalizerScript = Join-Path $PSScriptRoot "trim_normalize_wav.py"

if (!(Test-Path $pythonPath)) {
    throw "Irodori-TTS python runtime was not found: $pythonPath"
}
if (!(Test-Path $inferScript)) {
    throw "Irodori-TTS infer.py was not found: $inferScript"
}
if (!(Test-Path $ReferenceWav)) {
    throw "Reference wav was not found: $ReferenceWav"
}

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
    9 = "9"
    10 = "じゅう"
    11 = "じゅういち"
    12 = "じゅう、に"
    13 = "じゅう、さん"
    14 = "じゅうよん"
    15 = "じゅうご"
    16 = "じゅうろく"
    17 = "じゅうなな"
    18 = "じゅうはち"
    19 = "ジュウキュウ"
    20 = "にじゅう"
    21 = "にじゅういち"
    22 = "にじゅうに"
    23 = "にじゅう、さん"
    24 = "にじゅうよん"
    25 = "にじゅう、ご"
    26 = "にじゅうろく"
    27 = "にじゅう、なな"
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
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null

foreach ($entry in $readings.GetEnumerator()) {
    $count = $entry.Key
    $text = $entry.Value
    $tempWav = Join-Path $tempDir ("count_{0}.wav" -f $count)
    $outputWav = Join-Path $outputDir ("count_{0}.wav" -f $count)

    & $pythonPath $inferScript `
        --hf-checkpoint $Checkpoint `
        --text $text `
        --ref-wav $ReferenceWav `
        --duration-scale $DurationScale `
        --num-steps $NumSteps `
        --output-wav $tempWav
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to generate wav for count $count."
    }

    & $pythonPath $normalizerScript $tempWav $outputWav
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to normalize wav for count $count."
    }
}

Write-Output "Generated Irodori-TTS count wav files in $outputDir"
