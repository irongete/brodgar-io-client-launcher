<#
.SYNOPSIS
  Cut a release of the Brodgar.io launcher: build the app image, zip, tag, push, publish on GitHub.

.DESCRIPTION
  One command does the whole release:

    .\release.ps1 0.1.0 -Notes notes.md              release notes from a markdown file
    .\release.ps1 0.1.0 -Message "First launcher"    release notes inline
    .\release.ps1 0.1.1                              release notes = the commit subjects since the last v* tag

  It refuses to run on a dirty tree or an existing tag, compiles from scratch, runs
  `ant -Dversion=<version> release` -- launcher.jar, the jlink runtime cut from the JDK ant runs on (or
  jdk.home in build.properties), the jpackage app image, and its zip -- tags HEAD as v<version>, pushes the
  branch and the tag, and creates the GitHub release with the zip as its asset.

  The runtime the player gets is the JDK this runs on, so run it on the JDK the client is verified on.
  Needs git, ant and gh (logged in: `gh auth login`) on the PATH.

.PARAMETER Version
  1.2.3 or 1.2.3-beta.1: the tag is v<Version>, the asset Brodgar-launcher-<Version>-windows.zip.
.PARAMETER Notes
  A markdown file with the release notes.
.PARAMETER Message
  The release notes, inline.
.PARAMETER PreRelease
  Mark the release as a pre-release.
.PARAMETER Draft
  Create the release as a draft, to be published by hand on GitHub.
.PARAMETER NoPublish
  Build, zip and tag only: nothing is pushed and no release is created.
#>
param(
    [Parameter(Mandatory = $true, Position = 0)][string]$Version,
    [string]$Notes,
    [string]$Message,
    [switch]$PreRelease,
    [switch]$Draft,
    [switch]$NoPublish
)

$ErrorActionPreference = 'Stop'
$repo = 'irongete/brodgar-io-client-launcher'
$title = "Brodgar.io launcher $Version"
$tag = "v$Version"
$asset = "build\Brodgar-launcher-$Version-windows.zip"
Set-Location $PSScriptRoot

function Run {
    param([string]$Exe, [string[]]$Arguments)
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Exe $($Arguments -join ' ') failed with exit code $LASTEXITCODE" }
}

# --- checks -------------------------------------------------------------------------------------------------
if ($Version -notmatch '^\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?$') { throw "the version must look like 1.2.3 or 1.2.3-beta.1, not '$Version'" }
if ($Notes -and $Message) { throw 'give -Notes or -Message, not both' }
if ($Notes -and -not (Test-Path $Notes)) { throw "notes file not found: $Notes" }
if (git status --porcelain) { throw 'the working tree is not clean: commit or stash first' }
if (git tag -l $tag) { throw "the tag $tag already exists" }
$branch = (git rev-parse --abbrev-ref HEAD).Trim()
if (-not $NoPublish) {
    gh auth status *> $null
    if ($LASTEXITCODE -ne 0) { throw 'gh is not logged in: run `gh auth login` first' }
    if (git ls-remote --tags origin $tag) { throw "the tag $tag already exists on origin" }
}

# --- the notes ----------------------------------------------------------------------------------------------
if ($Notes) {
    $notesFile = (Resolve-Path $Notes).Path
} else {
    $notesFile = Join-Path $env:TEMP "brodgar-io-client-launcher-$Version-notes.md"
    if ($Message) {
        Set-Content -Path $notesFile -Value $Message -Encoding UTF8
    } else {
        $previous = (git describe --tags --abbrev=0 --match 'v*' 2>$null)
        $range = if ($previous) { "$previous..HEAD" } else { 'HEAD' }
        $log = git log --format='- %s' $range
        if (-not $log) { throw "no commits since $previous to write notes from: give -Notes or -Message" }
        Set-Content -Path $notesFile -Value $log -Encoding UTF8
        Write-Host "Release notes (the commits since $(if ($previous) { $previous } else { 'the beginning' })):"
        $log | ForEach-Object { Write-Host "  $_" }
    }
}

# --- build: from scratch, then the app image and its zip -----------------------------------------------------
$jdk = if (Test-Path build.properties) { (Get-Content build.properties | Where-Object { $_ -match '^jdk\.home=' } | Select-Object -First 1) -replace '^jdk\.home=', '' }
if (-not $jdk) { $jdk = "the JDK ant runs on ($(& java -version 2>&1 | Select-Object -First 1))" }
Write-Host "Building $title from $branch ($((git rev-parse --short HEAD).Trim())) with $jdk..."
if (Test-Path build\classes) { Remove-Item -Recurse -Force build\classes }
Run ant @("-Dversion=$Version", 'release')
if (-not (Test-Path $asset)) { throw "the build produced no $asset" }
Write-Host ("Asset: {0} ({1:N1} MB)" -f $asset, ((Get-Item $asset).Length / 1MB))

# --- tag ----------------------------------------------------------------------------------------------------
Run git @('tag', '-a', $tag, '-m', $title)
if ($NoPublish) {
    Write-Host "Tagged $tag. Nothing pushed and no release created (-NoPublish)."
    exit 0
}

# --- push and publish ---------------------------------------------------------------------------------------
Run git @('push', 'origin', $branch)
Run git @('push', 'origin', $tag)
$create = @('release', 'create', $tag, $asset, '--repo', $repo, '--title', $title, '--notes-file', $notesFile)
if ($PreRelease) { $create += '--prerelease' }
if ($Draft) { $create += '--draft' }
Run gh $create
Write-Host "Released $title as $tag."
