<#
.SYNOPSIS
  Publish the brodgar.io launcher on GitHub: build, zip, tag, push, release.

.DESCRIPTION
    .\publish.ps1              the next number:  v1 -> v2
    .\publish.ps1 -Version 5   that number

  The launcher has one line of releases, vN, and every launcher updates itself to the newest; nothing
  published counts as v0. The next number is counted from GitHub and printed with the branch and the
  commit, then confirmed before the build (-Yes skips the question).

  Refuses a dirty tree, a branch other than master (-Branch), a version not above GitHub's newest and a tag
  that exists anywhere. Runs `ant -Dversion=<n> release` -- launcher.jar, run.bat and the jlink runtime cut
  from jdk.home in build.properties, zipped -- tags vN, pushes the tag and then the branch, and creates the
  release with the zip. Then `.\publish-steam.ps1` puts the same launcher on the Steam Workshop.
  Needs git, ant and gh (`gh auth login`).

.PARAMETER Version
  That number instead of the counted one.
.PARAMETER Notes
  A markdown file with the release notes; default: the commit subjects since the previous version.
.PARAMETER Message
  The release notes, inline.
.PARAMETER Branch
  The branch to publish from; master.
.PARAMETER Draft
  Create the release as a draft.
.PARAMETER NoPublish
  Build, zip and tag only.
.PARAMETER Yes
  Do not ask.
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(Position = 0)][string]$Version,
    [string]$Notes,
    [string]$Message,
    [string]$Branch = 'master',
    [switch]$Draft,
    [switch]$NoPublish,
    [switch]$Yes
)

$ErrorActionPreference = 'Stop'
$repo = 'irongete/brodgar-io-client-launcher'
$product = 'brodgar.io launcher'
Set-Location $PSScriptRoot

function Run {
    param([string]$Exe, [string[]]$Arguments)
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Exe $($Arguments -join ' ') failed with exit code $LASTEXITCODE" }
}

# vN (the older vX.Y.Z tags parse too). Key sorts as the launcher does: number by number, a missing one as 0.
function Parse-Version {
    param([string]$Text)
    $m = [regex]::Match($Text, '^v?(\d+(?:\.\d+)*)$')
    if (-not $m.Success) { return $null }
    $numbers = @($m.Groups[1].Value -split '\.' | ForEach-Object { [int]$_ })
    $padded = @(0, 0, 0, 0)
    for ($i = 0; $i -lt $numbers.Count -and $i -lt 4; $i++) { $padded[$i] = $numbers[$i] }
    return [pscustomobject]@{
        Numbers = $numbers; Release = $numbers[0]
        Number = $m.Groups[1].Value
        Key = '{0:D9}.{1:D9}.{2:D9}.{3:D9}' -f $padded[0], $padded[1], $padded[2], $padded[3]
    }
}

# The non-draft releases on GitHub, newest first. (@tsv: no quotes in the argument, PowerShell 5.1 would not escape them.)
function Get-Published {
    $lines = & gh api "repos/$repo/releases?per_page=100" --jq '.[] | select(.draft | not) | [.tag_name, .published_at] | @tsv'
    if ($LASTEXITCODE -ne 0) { throw "could not list the releases of $repo (gh api failed)" }
    $published = @()
    foreach ($line in @($lines)) {
        if (-not $line) { continue }
        $tagName, $date = $line -split "`t"
        $v = Parse-Version $tagName
        if (-not $v) { throw "GitHub serves $tagName, which is not a version (vN): name the version with -Version, and consider retagging that one" }
        if ($date.Length -gt 10) { $date = $date.Substring(0, 10) }
        $published += [pscustomobject]@{ Tag = $tagName; Version = $v; Date = $date }
    }
    return $published | Sort-Object { $_.Version.Key } -Descending
}

# --- checks -------------------------------------------------------------------------------------------------
if ($Version) {
    $named = Parse-Version $Version
    if (-not $named -or $Version.StartsWith('v')) { throw "the version must be a number, not '$Version'" }
}
if ($Notes -and $Message) { throw 'give -Notes or -Message, not both' }
if ($Notes -and -not (Test-Path $Notes)) { throw "notes file not found: $Notes" }
if (git status --porcelain) { throw 'the working tree is not clean: commit or stash first' }
$current = (git rev-parse --abbrev-ref HEAD).Trim()   # not $branch: PowerShell names are case-insensitive
if ($current -ne $Branch) { throw "you are on '$current', and a release is cut from '$Branch': check it out (or pass -Branch $current to mean it)" }
$needsGitHub = -not ($NoPublish -and $Version)        # a named version built and tagged only needs no GitHub
if ($needsGitHub) {
    gh auth status *> $null
    if ($LASTEXITCODE -ne 0) { throw 'gh is not logged in: run `gh auth login` first' }
}

# --- the version --------------------------------------------------------------------------------------------
$published = @()
$newest = $null
if ($needsGitHub) {
    $published = @(Get-Published)
    if ($published.Count -gt 0) { $newest = $published[0] }
}
if ($Version) {
    $number = $named.Number
} else {
    if ($current -ne 'master') { throw "on $current the next number is not counted, since GitHub's newest may be another line's: name it with -Version" }
    $number = [string]($(if ($newest) { $newest.Version.Release } else { 0 }) + 1)   # nothing published: v0
}
$Version = $number
$tag = "v$Version"
$title = "$product $tag"
# the guards: above GitHub's newest (on master), and a tag that is nowhere yet
if ($newest -and $current -eq 'master' -and (Parse-Version $Version).Key -le $newest.Version.Key) {
    throw "$tag is not above $($newest.Tag), the newest on GitHub, and a launcher never installs a lower version: name one above it, or leave -Version out"
}
$asset = Join-Path 'build' 'brodgar.io-launcher.zip'   # no version in the name: the unzipped folder outlives it
if (git tag -l $tag) { throw "the tag $tag already exists in this clone" }
if ($needsGitHub -and ($published | Where-Object { $_.Tag -eq $tag })) { throw "$tag is published on GitHub already" }
if (-not $NoPublish -and (git ls-remote --tags origin $tag)) { throw "the tag $tag already exists on origin" }

# --- the plan -----------------------------------------------------------------------------------------------
$short = (git rev-parse --short HEAD).Trim()
Write-Host ''
Write-Host ("  newest on GitHub:  " + $(if ($newest) { "$($newest.Tag) ($($newest.Date))" } elseif ($needsGitHub) { 'nothing' } else { 'not asked (-NoPublish with -Version)' }))
Write-Host "  this publishes:    $tag -- every launcher updates itself to it"
Write-Host ("  from:              $current @ $short" + $(if ($NoPublish) { '  (-NoPublish: built and tagged here, nothing pushed, no release)' } elseif ($Draft) { '  (-Draft: the release is created as a draft)' } else { '' }))
Write-Host ''
if (-not $Yes) {
    $answer = Read-Host 'Continue? [y/N]'
    if ($answer -notmatch '^[yY]') { Write-Host 'Nothing done.'; exit 1 }
}

# --- the notes ----------------------------------------------------------------------------------------------
if ($Notes) {
    $notesFile = (Resolve-Path $Notes).Path
} else {
    $notesFile = Join-Path ([IO.Path]::GetTempPath()) "brodgar.io-launcher-$Version-notes.md"
    if ($Message) {
        [IO.File]::WriteAllText($notesFile, $Message, (New-Object Text.UTF8Encoding $false))   # no BOM: PowerShell 5.1 would write one
    } else {
        # since the newest published version when its tag is here, else since the highest tag reachable
        $previous = $null
        if ($newest -and (git tag -l $newest.Tag)) { $previous = $newest.Tag }
        if (-not $previous) { $previous = git tag -l 'v*' --merged HEAD --sort=-v:refname | Select-Object -First 1 }
        if ($previous) {
            $log = @(git log --format='- %s' --max-count=200 "$previous..HEAD")
            if (-not $log) { throw "no commits since $previous to write notes from: give -Notes or -Message" }
            $count = [int](git rev-list --count "$previous..HEAD")
            if ($count -gt $log.Count) { $log += "- ... and $($count - $log.Count) more" }
        } else {
            $log = @('The first version.')   # not the whole history
        }
        [IO.File]::WriteAllText($notesFile, ($log -join "`n"), (New-Object Text.UTF8Encoding $false))
        Write-Host "Release notes$(if ($previous) { " (the commits since $previous)" }):"
        $log | ForEach-Object { Write-Host "  $_" }
    }
}

# --- build, from scratch ------------------------------------------------------------------------------------
$jdk = if (Test-Path build.properties) { (Get-Content build.properties | Where-Object { $_ -match '^jdk\.home=' } | Select-Object -First 1) -replace '^jdk\.home=', '' }
if (-not $jdk) { $jdk = "the JDK ant runs on ($(& java -version 2>&1 | Select-Object -First 1))" }
Write-Host "Building $title from $current ($short) with $jdk..."
$classes = Join-Path 'build' 'classes'
if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
Run ant @("-Dversion=$Version", 'release')
if (-not (Test-Path $asset)) { throw "the build produced no $asset" }
Write-Host ("Asset: {0} ({1:N1} MB)" -f $asset, ((Get-Item $asset).Length / 1MB))

# --- tag, push, release -------------------------------------------------------------------------------------
Run git @('tag', '-a', $tag, '-m', $title)
if ($NoPublish) {
    Write-Host "Tagged $tag. Nothing pushed and no release created (-NoPublish)."
    exit 0
}
Run git @('push', 'origin', $tag)
Run git @('push', 'origin', $current)
$create = @('release', 'create', $tag, $asset, '--repo', $repo, '--title', $title, '--notes-file', $notesFile)
if ($Draft) { $create += '--draft' }
Run gh $create
Write-Host "Published $title. The Steam Workshop takes the same one: .\publish-steam.ps1"
