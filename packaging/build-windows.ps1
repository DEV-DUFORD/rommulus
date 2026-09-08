param(
    [Parameter(Mandatory = $true)][string]$NativeDir,
    [string]$Version = "0.3.0",
    [string]$OutputDir = "dist/windows",
    [switch]$SkipInstaller
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function New-WindowsIcon {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    Add-Type -AssemblyName System.Drawing
    $sizes = @(16, 24, 32, 48, 64, 128, 256)
    $images = [System.Collections.Generic.List[byte[]]]::new()
    $sourceImage = [System.Drawing.Image]::FromFile($Source)
    try {
        foreach ($size in $sizes) {
            $bitmap = [System.Drawing.Bitmap]::new(
                $size,
                $size,
                [System.Drawing.Imaging.PixelFormat]::Format32bppArgb
            )
            try {
                $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
                try {
                    $graphics.CompositingQuality =
                        [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
                    $graphics.InterpolationMode =
                        [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                    $graphics.PixelOffsetMode =
                        [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
                    $graphics.SmoothingMode =
                        [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
                    $graphics.Clear([System.Drawing.Color]::Transparent)
                    $graphics.DrawImage($sourceImage, 0, 0, $size, $size)
                } finally {
                    $graphics.Dispose()
                }
                $stream = [System.IO.MemoryStream]::new()
                try {
                    $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
                    $images.Add($stream.ToArray())
                } finally {
                    $stream.Dispose()
                }
            } finally {
                $bitmap.Dispose()
            }
        }
    } finally {
        $sourceImage.Dispose()
    }

    $file = [System.IO.File]::Create($Destination)
    $writer = [System.IO.BinaryWriter]::new($file)
    try {
        $writer.Write([uint16]0)
        $writer.Write([uint16]1)
        $writer.Write([uint16]$images.Count)
        $offset = 6 + (16 * $images.Count)
        for ($index = 0; $index -lt $images.Count; $index++) {
            $size = $sizes[$index]
            $writer.Write([byte]$(if ($size -eq 256) { 0 } else { $size }))
            $writer.Write([byte]$(if ($size -eq 256) { 0 } else { $size }))
            $writer.Write([byte]0)
            $writer.Write([byte]0)
            $writer.Write([uint16]1)
            $writer.Write([uint16]32)
            $writer.Write([uint32]$images[$index].Length)
            $writer.Write([uint32]$offset)
            $offset += $images[$index].Length
        }
        foreach ($image in $images) {
            $writer.Write($image)
        }
    } finally {
        $writer.Dispose()
    }
}

$repo = Split-Path $PSScriptRoot -Parent
$native = (Resolve-Path $NativeDir).Path
$nativeAudit = Get-Content -Raw (Join-Path $native "native-audit.json") | ConvertFrom-Json
foreach ($entry in $nativeAudit.PSObject.Properties) {
    $file = [System.IO.Path]::GetFullPath((Join-Path $native $entry.Name))
    if (-not $file.StartsWith("$native\", [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Invalid native artifact path: $($entry.Name)"
    }
    if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.Value.sha256) {
        throw "Native artifact checksum mismatch: $($entry.Name)"
    }
}
$output = [System.IO.Path]::GetFullPath($OutputDir)
$jpackage = Join-Path $env:JAVA_HOME "bin/jpackage.exe"
if (-not (Test-Path $jpackage)) { throw "JDK 17 jpackage.exe is required." }
$icon = Join-Path $repo "desktop/build/packaging/rommulus.ico"
New-Item -ItemType Directory -Force -Path (Split-Path $icon -Parent) | Out-Null
New-WindowsIcon `
    -Source (Join-Path $repo "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png") `
    -Destination $icon
$appJar = Join-Path $repo "desktop/build/libs/desktop.jar"
$libs = Join-Path $repo "desktop/build/runtime-libs"
if (-not (Test-Path $appJar)) { throw "Run :desktop:jar :desktop:copyRuntimeClasspath first." }
$image = Join-Path $output "RomMulus"
if (Test-Path $image) { throw "Output already exists: $image. Use a fresh output directory." }
New-Item -ItemType Directory -Force -Path $output | Out-Null
$inputDir = Join-Path $output "jpackage-input"
if (Test-Path $inputDir) { throw "Staging directory already exists: $inputDir" }
New-Item -ItemType Directory -Path $inputDir | Out-Null
try {
    Copy-Item (Join-Path $libs "*.jar") $inputDir
    Copy-Item $appJar (Join-Path $inputDir "desktop.jar")
    & $jpackage --type app-image --name RomMulus --app-version $Version `
        --vendor DEV-DUFORD --description "RomMulus game library and emulator" `
        --input $inputDir --main-jar desktop.jar --main-class com.romm.desktop.MainKt `
        --add-modules ALL-MODULE-PATH --icon $icon --dest $output
    if ($LASTEXITCODE -ne 0) { throw "jpackage app-image failed ($LASTEXITCODE)" }
} finally {
    Remove-Item -LiteralPath $inputDir -Recurse -Force
}

$nativeOutput = Join-Path $image "native"
New-Item -ItemType Directory -Path $nativeOutput | Out-Null
Copy-Item (Join-Path $native "bin/*") $nativeOutput -Recurse
Copy-Item (Join-Path $native "cores") $nativeOutput -Recurse
Copy-Item (Join-Path $native "native-audit.json") $nativeOutput
Copy-Item (Join-Path $repo "packaging/share/rommulus/windows-core-manifest.json") `
    (Join-Path $image "core-manifest.json")
$manifest = Get-Content -Raw (Join-Path $image "core-manifest.json") | ConvertFrom-Json
$expectedCores = @($manifest.cores | ForEach-Object { "$($_.coreId)_core.dll" } | Sort-Object)
$actualCores = @(Get-ChildItem (Join-Path $nativeOutput "cores") -File | ForEach-Object Name | Sort-Object)
if (Compare-Object $expectedCores $actualCores) { throw "Packaged DLLs do not match Windows core manifest." }
if ($expectedCores.Count -ne 13) { throw "Expected 13 Windows game cores." }
foreach ($core in $manifest.cores) {
    $file = Join-Path $nativeOutput "cores/$($core.coreId)_core.dll"
    $core.binaryChecksums.'windows-x86_64' = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
}
[System.IO.File]::WriteAllText(
    (Join-Path $image "core-manifest.json"),
    ($manifest | ConvertTo-Json -Depth 20),
    [System.Text.UTF8Encoding]::new($false)
)

$licenses = Join-Path $image "licenses"
Copy-Item (Join-Path $repo "packaging/share/licenses/rommulus") $licenses -Recurse
Copy-Item (Join-Path $native "licenses/*") $licenses -Recurse -Force
$assets = Join-Path $image "share/rommulus"
New-Item -ItemType Directory -Force -Path (Join-Path $assets "controllers") | Out-Null
Copy-Item (Join-Path $repo "assets/controllers/*.png") (Join-Path $assets "controllers")
Copy-Item (Join-Path $repo "third_party/cores/mupen64plus_next/mupen64plus-core/data/font.ttf") $assets
Copy-Item (Join-Path $repo "packaging/docs/windows-support.md") (Join-Path $image "README.md")
$revision = (& git -C $repo rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw "Cannot determine source revision." }
@"
RomMulus $Version Windows x86_64
Source revision: $revision
Complete corresponding source and build scripts:
https://github.com/DEV-DUFORD/rommulus/tree/$revision
Clone this revision with git submodule update --init --recursive.
Windows build recipe: .github/workflows/windows-x64.yml
Vendored core modifications are included in this revision.
SDL/ANGLE identities and licenses are included in licenses/.
This development build is unsigned; no ROMs or BIOS firmware are bundled.
"@ | Set-Content -Encoding UTF8 (Join-Path $licenses "SOURCE.txt")

# jpackage consumes this app-image marker and does not install it with an EXE package.
$checksums = Get-ChildItem $image -File -Recurse |
    Where-Object { $_.FullName -ne (Join-Path $image "app/.jpackage.xml") } |
    Sort-Object FullName |
    ForEach-Object {
        $relative = $_.FullName.Substring($image.Length + 1).Replace("\", "/")
        "$((Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant())  $relative"
    }
$checksums | Set-Content -Encoding ASCII (Join-Path $image "PACKAGE.sha256")
if (Select-String -LiteralPath (Join-Path $image "PACKAGE.sha256") -SimpleMatch "app/.jpackage.xml") {
    throw "PACKAGE.sha256 must not include jpackage installer metadata."
}
$zip = Join-Path $output "rommulus-$Version-windows-x86_64.zip"
Compress-Archive -Path $image -DestinationPath $zip -CompressionLevel Optimal
if (-not $SkipInstaller) {
    & $jpackage --type exe --name RomMulus --app-version $Version --vendor DEV-DUFORD `
        --app-image $image --icon $icon --dest $output --win-per-user-install --win-dir-chooser `
        --win-shortcut --win-menu --win-upgrade-uuid "a89fbccd-8263-4716-bbbc-fb64e49ed30c"
    if ($LASTEXITCODE -ne 0) { throw "jpackage EXE installer failed ($LASTEXITCODE)" }
}
Get-ChildItem $output -File | Where-Object { $_.Extension -in ".zip", ".exe" } | ForEach-Object {
    "$((Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant())  $($_.Name)"
} | Set-Content -Encoding ASCII (Join-Path $output "SHA256SUMS.txt")
Write-Output "Windows package: $zip"
