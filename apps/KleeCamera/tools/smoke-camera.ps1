[CmdletBinding()]
param(
    [string]$AdbPath = 'adb',
    [string]$ReceiptPath = (Join-Path $PSScriptRoot ('../build/camera-smoke-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json')),
    [ValidateSet('JPEG', 'TimedBurst', 'Front', 'UltraWide', 'Macro', 'Pro', 'Portrait', 'RAW', 'HDR', 'HDRFront', 'Night', 'NightFront', 'LightTrails', 'Document', 'Video', 'Video1080p60', 'Video4K', 'Video4K60', 'Video4KUltraWide', 'SlowMotion', 'SlowMotionFront', 'Timelapse')]
    [string[]]$Modes = @('JPEG', 'Front', 'UltraWide', 'Macro', 'Pro', 'Portrait', 'RAW', 'HDR', 'Night', 'Document', 'Video', 'Timelapse'),
    [switch]$GrantAudio,
    [switch]$CaptureVideoPreview
)

$ErrorActionPreference = 'Stop'
$package = 'org.kleeui.camera'
$rows = [System.Collections.Generic.List[object]]::new()
$uiFile = '/data/local/tmp/klee-camera-smoke-' + [guid]::NewGuid().ToString('N') + '.xml'
$oldEncoding = [Console]::OutputEncoding
$launched = $false

function Invoke-Adb([string[]]$Arguments) {
    $output = @(& $AdbPath @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw ('adb failed for {0}: {1}' -f ($Arguments -join ' '), ($output -join ' '))
    }
    $output -join "`n"
}

function Invoke-Shell([string]$Command) {
    Invoke-Adb @('-s', $serial, 'shell', $Command)
}

function Get-Ui {
    $result = Invoke-Shell "uiautomator dump $uiFile"
    if ($result -match '(?i)error|exception') { throw $result }
    [xml](Invoke-Shell "cat $uiFile")
}

function Get-Node([xml]$Ui, [string]$Attribute, [string]$Value, [string]$Owner = $package) {
    $Ui.SelectNodes('//node') | Where-Object {
        $_.GetAttribute('package') -eq $Owner -and $_.GetAttribute($Attribute) -eq $Value -and
        $_.GetAttribute('enabled') -eq 'true'
    } | Select-Object -First 1
}

function Get-Bounds([System.Xml.XmlElement]$Node) {
    if ($Node.GetAttribute('bounds') -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
        throw 'The UI node has no screen bounds.'
    }
    [int[]]@($Matches[1], $Matches[2], $Matches[3], $Matches[4])
}

function Tap-Node([System.Xml.XmlElement]$Node) {
    $bound = Get-Bounds $Node
    $x = [int](($bound[0] + $bound[2]) / 2)
    $y = [int](($bound[1] + $bound[3]) / 2)
    Invoke-Shell "input tap $x $y" | Out-Null
}

function Wait-Ui([scriptblock]$Find, [string]$Description, [int]$Seconds = 20) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        $result = & $Find (Get-Ui)
        if ($null -ne $result) { return $result }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for $Description. Keep the phone awake and unlocked."
}

function Find-Mode([string]$Label) {
    $ui = Get-Ui
    $mode = Get-Node $ui 'text' $Label
    if ($mode) { return $mode }

    $pane = Get-Node $ui 'class' 'android.widget.HorizontalScrollView'
    if (-not $pane) { throw 'No camera mode carousel found.' }
    $bounds = Get-Bounds $pane
    $width = $bounds[2] - $bounds[0]
    $left = $bounds[0] + [int]($width * .10)
    $right = $bounds[2] - [int]($width * .10)
    $y = [int](($bounds[1] + $bounds[3]) / 2)
    foreach ($direction in @(1, -1)) {
        $from = if ($direction -eq 1) { $left } else { $right }
        $to = if ($direction -eq 1) { $right } else { $left }
        $previousLabels = ''
        for ($i = 0; $i -lt 6; $i++) {
            Invoke-Shell "input swipe $from $y $to $y 320" | Out-Null
            Start-Sleep -Milliseconds 350
            $ui = Get-Ui
            $mode = Get-Node $ui 'text' $Label
            if ($mode) { return $mode }
            $labels = ($ui.SelectNodes('//node[@class="android.widget.HorizontalScrollView"]//node[@text]') |
                ForEach-Object { $_.GetAttribute('text') }) -join ','
            if ($labels -eq $previousLabels) { break }
            $previousLabels = $labels
        }
    }
}

function Get-Media([bool]$Video) {
    $collection = if ($Video) { 'video/media' } else { 'images/media' }
    $projection = '_id:_display_name:_size:mime_type:width:height'
    if ($Video) { $projection += ':duration' }
    $where = '"relative_path=' + "'DCIM/KleeCamera/'" + ' AND is_pending=0"'
    $result = Invoke-Shell "content query --uri content://media/external_primary/$collection --projection $projection --where $where"
    if ($result -match '(?i)exception|error|permission denial') { throw $result }
    foreach ($line in $result -split "`n") {
        if ($line -notmatch '^Row:\s+\d+\s+(.*)$') { continue }
        $values = @{}
        foreach ($entry in $Matches[1] -split ', ') {
            $name, $value = $entry -split '=', 2
            $values[$name] = $value
        }
        [pscustomobject]@{
            id = [long]$values._id
            file = $values._display_name
            bytes = if ($values._size -match '^\d+$') { [long]$values._size } else { 0 }
            mime = $values.mime_type
            width = if ($values.width -match '^\d+$') { [int]$values.width }
            height = if ($values.height -match '^\d+$') { [int]$values.height }
            durationMs = if ($values.duration -match '^\d+$') { [long]$values.duration }
        }
    }
}

function Wait-Media([bool]$Video, [long[]]$Previous, [string]$Extension, [switch]$RequireDimensions) {
    $deadline = [DateTime]::UtcNow.AddSeconds(25)
    do {
        $found = @(Get-Media $Video | Where-Object {
            $_.id -notin $Previous -and $_.file -like "*.$Extension" -and $_.bytes -gt 0 -and
            (-not $RequireDimensions -or ($_.width -gt 0 -and $_.height -gt 0))
        })
        if ($found.Count -eq 1) { return $found[0] }
        if ($found.Count -gt 1) { throw 'More than one media item was created during this capture.' }
        Start-Sleep -Milliseconds 400
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "No new nonempty $Extension capture was published."
}

function Wait-Shutter([string]$Label) {
    Wait-Ui { param($ui) Get-Node $ui 'content-desc' $Label } $Label
}

function Switch-Facing([string]$Lens) {
    Tap-Node (Wait-Ui { param($ui) Get-Node $ui 'content-desc' '切换前后摄像头' } 'camera switch')
    Wait-Ui { param($ui) Get-Node $ui 'content-desc' "相机预览 · $Lens" } "$Lens preview" | Out-Null
}

function Set-CameraOption([string]$MenuItem, [string]$Choice) {
    Tap-Node (Wait-Ui { param($ui) Get-Node $ui 'content-desc' '网格、定时与连拍设置' } 'camera settings')
    Tap-Node (Wait-Ui { param($ui) Get-Node $ui 'text' $MenuItem } $MenuItem)
    Tap-Node (Wait-Ui { param($ui) Get-Node $ui 'text' $Choice } $Choice)
}

function Set-VideoProfile([string]$Choice) {
    if ($Choice -notmatch '^(1080p|4K) \| (30|60) fps$') {
        throw "Invalid video profile $Choice."
    }
    Tap-Node (Wait-Ui { param($ui) Get-Node $ui 'content-desc' '录像画质与帧率' } 'video quality and frame rate')
    Tap-Node (Wait-Ui { param($ui) Get-Node $ui 'text' $Choice } $Choice)
    Wait-Ui {
        param($ui)
        $profile = Get-Node $ui 'content-desc' '录像画质与帧率'
        if ($profile -and $profile.GetAttribute('text') -eq $Choice) { $profile }
    } "selected video profile $Choice" | Out-Null
}

function Select-Lens([string]$Lens) {
    $lensButton = Get-Node (Get-Ui) 'content-desc' "镜头 $Lens"
    if (-not $lensButton) { return $false }
    Tap-Node $lensButton
    Wait-Ui { param($ui) Get-Node $ui 'content-desc' "相机预览 · $Lens" } "$Lens preview" | Out-Null
    $true
}

function Start-PreviewLogCheck {
    $pidResult = (Invoke-Shell "pidof $package").Trim()
    $pidMatch = [regex]::Match($pidResult, '^(\d+)')
    if (-not $pidMatch.Success) { throw 'Unable to find the camera application process for preview log validation.' }
    $marker = 'recording-' + [guid]::NewGuid().ToString('N')
    $receipt = [IO.Path]::GetFullPath($ReceiptPath)
    $directory = Join-Path ([IO.Path]::GetDirectoryName($receipt)) "$([IO.Path]::GetFileNameWithoutExtension($receipt))-$marker"
    [IO.Directory]::CreateDirectory($directory) | Out-Null
    $outputPath = Join-Path $directory 'logcat.log'
    $errorPath = Join-Path $directory 'logcat.stderr.log'
    $info = [System.Diagnostics.ProcessStartInfo]::new()
    $info.FileName = (Get-Command -CommandType Application -Name $AdbPath -ErrorAction Stop | Select-Object -First 1).Source
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($argument in @('-s', $serial, 'logcat', '-b', 'main', '-T', '1', '-v', 'brief',
            'KleeCamera:I', 'KleeCameraSmoke:I', 'KleeYuvVideo:I', 'KleeRecordingPreview:W', '*:S')) {
        $info.ArgumentList.Add($argument)
    }
    $process = [System.Diagnostics.Process]::Start($info)
    $check = [pscustomobject]@{
        Pid = $pidMatch.Groups[1].Value
        Marker = $marker
        Directory = $directory
        OutputPath = $outputPath
        ErrorPath = $errorPath
        Process = $process
        Output = $null
        ErrorOutput = $null
        CopyOutput = $null
        CopyError = $null
    }
    try {
        $check.Output = [IO.FileStream]::new($outputPath, 'CreateNew', 'Write', 'ReadWrite', 1, $true)
        $check.ErrorOutput = [IO.FileStream]::new($errorPath, 'CreateNew', 'Write', 'ReadWrite', 1, $true)
        $check.CopyOutput = $process.StandardOutput.BaseStream.CopyToAsync($check.Output)
        $check.CopyError = $process.StandardError.BaseStream.CopyToAsync($check.ErrorOutput)
        $check
    } catch {
        Stop-PreviewLogCheck $check
        throw
    }
}

function Stop-PreviewLogCheck($Check) {
    if (-not $Check) { return }
    try {
        if (-not $Check.Process.HasExited) { $Check.Process.Kill() }
        if (-not $Check.Process.WaitForExit(2000)) { throw 'The preview logcat process did not exit.' }
        $copies = @($Check.CopyOutput, $Check.CopyError | Where-Object { $null -ne $_ })
        if ($copies.Count -and -not [System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]$copies, 2000)) {
            throw 'The preview logcat streams did not close.'
        }
    } finally {
        if ($Check.Output) { $Check.Output.Dispose() }
        if ($Check.ErrorOutput) { $Check.ErrorOutput.Dispose() }
        $Check.Process.Dispose()
    }
}

function Wait-PreviewLog($Check, [switch]$MarkerOnly) {
    $markerPattern = '^I/KleeCameraSmoke\s*\(\s*\d+\):\s*' + [regex]::Escape($Check.Marker) + '\s*$'
    $framePattern = '^I/KleeCamera\s*\(\s*' + $Check.Pid + '\):\s*Preview displayed (\d+) frames during recording\s*$'
    $deadline = [DateTime]::UtcNow.AddSeconds(6)
    do {
        if ($MarkerOnly) { Invoke-Shell "log -p i -t KleeCameraSmoke $($Check.Marker)" | Out-Null }
        $afterMarker = $false
        foreach ($line in Get-Content -LiteralPath $Check.OutputPath) {
            if ($line -cmatch $markerPattern) {
                if ($MarkerOnly) { return }
                $afterMarker = $true
            } elseif ($afterMarker -and $line -cmatch $framePattern) {
                return [int]$Matches[1]
            }
        }
        if ($Check.Process.HasExited) { throw "Preview logcat exited early. See $($Check.ErrorPath)." }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    $expected = if ($MarkerOnly) { 'readiness marker' } else { 'new camera preview frame count' }
    throw "No $expected arrived in $($Check.OutputPath)."
}

function Wait-RecordingTime([System.Diagnostics.Stopwatch]$Watch, [int]$TargetMs) {
    $remainingMs = $TargetMs - $Watch.ElapsedMilliseconds
    if ($remainingMs -gt 0) { Start-Sleep -Milliseconds $remainingMs }
}

function Save-PreviewScreenshots([string[]]$RemotePaths, [string]$Directory) {
    for ($i = 0; $i -lt $RemotePaths.Count; $i++) {
        $target = Join-Path $directory ('preview-{0}.png' -f ($i + 1))
        Invoke-Adb @('-s', $serial, 'pull', $RemotePaths[$i], $target) | Out-Null
        $bytes = [IO.File]::ReadAllBytes($target)
        if ($bytes.Length -le 24 -or [BitConverter]::ToString($bytes, 0, 8) -cne '89-50-4E-47-0D-0A-1A-0A') {
            throw "Invalid PNG screenshot at $target."
        }
        $target
    }
}

function Invoke-TimedBurst {
    Set-CameraOption '定时拍照' '3 秒'
    Set-CameraOption '连拍 · 单张' '连拍 3 张'
    $prior = @(Get-Media $false | ForEach-Object id)
    $shutter = Wait-Shutter '拍照'
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    Tap-Node $shutter
    $firstMs = $null
    do {
        $new = @(Get-Media $false | Where-Object { $_.id -notin $prior -and $_.file -like '*.jpg' -and $_.bytes -gt 0 })
        if ($new.Count -gt 0 -and $null -eq $firstMs) { $firstMs = $watch.ElapsedMilliseconds }
        if ($new.Count -ge 3) { break }
        Start-Sleep -Milliseconds 150
    } while ($watch.ElapsedMilliseconds -lt 30000)
    if ($null -eq $firstMs -or $firstMs -lt 2800 -or $new.Count -ne 3) {
        throw "Expected 3 JPEGs after the countdown. Received $($new.Count), first visible after $firstMs ms."
    }
    Wait-Shutter '拍照' | Out-Null
    $new = @(Get-Media $false | Where-Object { $_.id -notin $prior -and $_.file -like '*.jpg' -and $_.bytes -gt 0 })
    if ($new.Count -ne 3) { throw "Expected exactly 3 JPEGs. Received $($new.Count)." }
    Set-CameraOption '连拍 · 3 张' '单张拍照'
    Set-CameraOption '定时拍照 · 3 秒' '关闭'
    [pscustomobject]@{firstVisibleMs = $firstMs; captures = $new}
}

$specs = @{
    JPEG = @{Label = '拍照'; Extension = 'jpg'; Video = $false}
    TimedBurst = @{Label = '拍照'; Extension = 'jpg'; Video = $false}
    Front = @{Label = '拍照'; Extension = 'jpg'; Video = $false; Front = $true}
    UltraWide = @{Label = '拍照'; Extension = 'jpg'; Video = $false; Lens = '超广角'}
    Macro = @{Label = '拍照'; Extension = 'jpg'; Video = $false; Lens = '微距'}
    Pro = @{Label = '专业'; Extension = 'jpg'; Video = $false}
    Portrait = @{Label = '人像场景'; Extension = 'jpg'; Video = $false}
    RAW = @{Label = 'RAW'; Extension = 'dng'; Video = $false}
    HDR = @{Label = 'HDR'; Extension = 'jpg'; Video = $false}
    HDRFront = @{Label = 'HDR'; Extension = 'jpg'; Video = $false; Front = $true}
    Night = @{Label = '夜景'; Extension = 'jpg'; Video = $false}
    NightFront = @{Label = '夜景'; Extension = 'jpg'; Video = $false; Front = $true}
    LightTrails = @{Label = '光轨'; Extension = 'jpg'; Video = $false}
    Document = @{Label = '黑白文档'; Extension = 'jpg'; Video = $false}
    Video = @{Label = '录像'; Extension = 'mp4'; Video = $true; Seconds = 5; Fhd = $true; Profile = '1080p | 30 fps'}
    Video1080p60 = @{Label = '录像'; Extension = 'mp4'; Video = $true; Seconds = 7; Fhd = $true; Profile = '1080p | 60 fps'; RequirePreview = $true}
    Video4K = @{Label = '录像'; Extension = 'mp4'; Video = $true; Seconds = 5; Uhd = $true; Profile = '4K | 30 fps'}
    Video4K60 = @{Label = '录像'; Extension = 'mp4'; Video = $true; Seconds = 7; Uhd = $true; Profile = '4K | 60 fps'; RequirePreview = $true; MinPreviewFps = 12}
    Video4KUltraWide = @{Label = '录像'; Extension = 'mp4'; Video = $true; Seconds = 5; Uhd = $true; Profile = '4K | 30 fps'; Lens = '超广角'}
    SlowMotion = @{Label = '慢动作'; Extension = 'mp4'; Video = $true; Seconds = 5}
    SlowMotionFront = @{Label = '慢动作'; Extension = 'mp4'; Video = $true; Seconds = 5; Front = $true}
    Timelapse = @{Label = '延时'; Extension = 'mp4'; Video = $true; Seconds = 8}
}

$status = 'failed'
$failure = $null
$device = $null
$pendingScreenshots = [System.Collections.Generic.List[string]]::new()
try {
    [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
    $devices = @((Invoke-Adb @('devices')) -split "`n" | Where-Object { $_ -match '^\S+\t\S+' })
    if ($devices.Count -ne 1 -or $devices[0] -notmatch '^(\S+)\s+device\s*$') {
        throw 'Connect exactly one authorized Android device before testing.'
    }
    $serial = $Matches[1]
    $device = (Invoke-Shell 'getprop ro.product.device').Trim()
    if (-not (Invoke-Shell "pm path $package").StartsWith('package:')) { throw 'Install Klee Camera first.' }
    Invoke-Shell "pm grant $package android.permission.CAMERA" | Out-Null
    if ($GrantAudio) { Invoke-Shell "pm grant $package android.permission.RECORD_AUDIO" | Out-Null }
    Invoke-Shell "am force-stop $package" | Out-Null
    $launched = $true
    $launch = Invoke-Shell "am start -W -n $package/.MainActivity"
    if ($launch -match '(?mi)^Error') { throw $launch }
    Wait-Shutter '拍照' | Out-Null

    foreach ($name in $Modes) {
        $spec = $specs[$name]
        $screenshots = @()
        $previewCheck = $null
        $previewFrames = $null
        $previewFps = $null
        try {
            if ($spec.Front) {
                $switch = Get-Node (Get-Ui) 'content-desc' '切换前后摄像头'
                if (-not $switch) {
                    $rows.Add([pscustomobject]@{mode = $name; status = 'unsupported'})
                    continue
                }
                Switch-Facing '前置'
            }
            if ($spec.Lens -and $spec.Video) {
                if (-not (Select-Lens $spec.Lens)) {
                    $rows.Add([pscustomobject]@{mode = $name; status = 'unsupported'})
                    continue
                }
            }
            $mode = Find-Mode $spec.Label
            if (-not $mode) {
                if ($name -in @('JPEG', 'Video', 'Video1080p60', 'Video4K', 'Video4K60')) {
                    throw "Required mode $($spec.Label) is unavailable."
                }
                if ($spec.Front) { Switch-Facing '主摄' }
                $rows.Add([pscustomobject]@{mode = $name; status = 'unsupported'})
                continue
            }
            if ($mode.GetAttribute('selected') -ne 'true') { Tap-Node $mode }
            if ($spec.Profile) { Set-VideoProfile $spec.Profile }
            $ready = if ($spec.Video) { '开始录像' } else { '拍照' }
            Wait-Ui { param($ui)
                $selected = Get-Node $ui 'text' $spec.Label
                if ($selected -and $selected.GetAttribute('selected') -eq 'true') { Get-Node $ui 'content-desc' $ready }
            } "the selected $name session" | Out-Null

            if ($name -eq 'TimedBurst') {
                $rows.Add([pscustomobject]@{mode = $name; status = 'passed'; capture = Invoke-TimedBurst})
                continue
            }

            if ($spec.Lens -and -not $spec.Video) {
                if (-not (Select-Lens $spec.Lens)) {
                    $rows.Add([pscustomobject]@{mode = $name; status = 'unsupported'})
                    continue
                }
            }

            $previous = @(Get-Media $spec.Video | ForEach-Object id)
            if ($spec.RequirePreview) {
                $previewCheck = Start-PreviewLogCheck
                Wait-PreviewLog $previewCheck -MarkerOnly
            }
            Tap-Node (Wait-Shutter $ready)
            if ($spec.Video) {
                $stop = Wait-Ui { param($ui)
                    $stopButton = Get-Node $ui 'content-desc' '停止录像'
                    if ($stopButton) { return $stopButton }
                    $deny = $ui.SelectSingleNode('//node[contains(@resource-id,":id/permission_deny_button")]')
                    if ($deny) { Tap-Node $deny }
                } 'a recording session' 25
                $recordingWatch = [System.Diagnostics.Stopwatch]::StartNew()
                try {
                    if ($CaptureVideoPreview -and $name -eq 'Video4K60') {
                        foreach ($captureMs in @(2000, 4500)) {
                            Wait-RecordingTime $recordingWatch $captureMs
                            $remote = "/data/local/tmp/klee-camera-smoke-$($previewCheck.Marker)-$captureMs.png"
                            $pendingScreenshots.Add($remote)
                            Invoke-Shell "screencap -p $remote" | Out-Null
                        }
                    }
                } finally {
                    Wait-RecordingTime $recordingWatch ($spec.Seconds * 1000)
                    Tap-Node $stop
                }
                if ($CaptureVideoPreview -and $name -eq 'Video4K60') {
                    $screenshots = @(Save-PreviewScreenshots $pendingScreenshots.ToArray() $previewCheck.Directory)
                    Invoke-Shell ('rm -f ' + ($pendingScreenshots -join ' ')) | Out-Null
                    $pendingScreenshots.Clear()
                }
            }
            $media = Wait-Media $spec.Video $previous $spec.Extension -RequireDimensions:([bool]($spec.Uhd -or $spec.Fhd))
            if ($spec.RequirePreview) {
                $previewFrames = Wait-PreviewLog $previewCheck
                if ($previewFrames -le 1) { throw "Expected live preview during recording. Logged $previewFrames frames." }
                if ($media.durationMs -gt 0) {
                    $previewFps = $previewFrames * 1000.0 / $media.durationMs
                }
                if ($spec.MinPreviewFps -and ($null -eq $previewFps -or $previewFps -lt $spec.MinPreviewFps)) {
                    throw "Expected at least $($spec.MinPreviewFps) preview fps. Logged $previewFrames frames over $($media.durationMs) ms."
                }
            }
            $isUhd = ($media.width -eq 3840 -and $media.height -eq 2160) -or
                ($media.width -eq 2160 -and $media.height -eq 3840)
            if ($spec.Uhd -and -not $isUhd) {
                throw "4K mode published $($media.width) × $($media.height) video."
            }
            $isFhd = ($media.width -eq 1920 -and $media.height -eq 1080) -or
                ($media.width -eq 1080 -and $media.height -eq 1920)
            if ($spec.Fhd -and -not $isFhd) {
                throw "1080p mode published $($media.width) × $($media.height) video."
            }
            Wait-Shutter $ready | Out-Null
            if ($spec.Front) { Switch-Facing '主摄' }
            if ($spec.Lens) {
                Tap-Node (Wait-Ui { param($ui) Get-Node $ui 'content-desc' '镜头 主摄' } 'main lens selector')
                Wait-Ui { param($ui) Get-Node $ui 'content-desc' '相机预览 · 主摄' } 'main lens preview' | Out-Null
            }
            $row = [ordered]@{mode = $name; status = 'passed'; capture = $media}
            if ($spec.RequirePreview) {
                $row.previewLog = $previewCheck.OutputPath
                $row.previewFrames = $previewFrames
                if ($null -ne $previewFps) { $row.previewFps = [math]::Round($previewFps, 2) }
            }
            if ($screenshots.Count) { $row.previewScreenshots = $screenshots }
            $rows.Add([pscustomobject]$row)
        } catch {
            $row = [ordered]@{mode = $name; status = 'failed'; error = $_.Exception.Message}
            if ($previewCheck) { $row.previewLog = $previewCheck.OutputPath }
            if ($null -ne $previewFrames) { $row.previewFrames = $previewFrames }
            if ($null -ne $previewFps) { $row.previewFps = [math]::Round($previewFps, 2) }
            if ($screenshots.Count) { $row.previewScreenshots = $screenshots }
            $rows.Add([pscustomobject]$row)
            throw
        } finally {
            Stop-PreviewLogCheck $previewCheck
        }
    }
    $status = 'passed'
} catch {
    $failure = $_.Exception.Message
} finally {
    if ($launched) {
        try {
            Invoke-Shell "am force-stop $package" | Out-Null
            Invoke-Shell ('rm -f ' + ((@($uiFile) + $pendingScreenshots.ToArray()) -join ' ')) | Out-Null
        } catch {
            $failure = if ($failure) { "$failure Cleanup failed: $($_.Exception.Message)" } else { $_.Exception.Message }
            $status = 'failed'
        }
    }
    [Console]::OutputEncoding = $oldEncoding
    $receipt = [IO.Path]::GetFullPath($ReceiptPath)
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($receipt)) | Out-Null
    [pscustomobject]@{
        status = $status
        device = $device
        finishedUtc = [DateTime]::UtcNow.ToString('o')
        results = $rows.ToArray()
        error = $failure
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $receipt -Encoding UTF8
    Write-Output $receipt
}

if ($failure) { throw $failure }
