<#
.SYNOPSIS
  Publish the Brodgar.io launcher on the Steam Workshop: build the item, upload it, set its visibility.

.DESCRIPTION
  One command puts the launcher as it stands on Steam, as the Workshop item workshop\ describes:

    .\publish-steam.ps1                                the item as it stands, visibility as the file says
    .\publish-steam.ps1 -Message "what changed"        with a change note, shown in the item's history
    .\publish-steam.ps1 -Version 1.0.1                 the version the launcher's window shows
    .\publish-steam.ps1 -Visibility public             flip the item public (private, friends: the same way)
    .\publish-steam.ps1 -NoUpload                      build build\workshop\ and stop, for a look

  It runs `ant -Dversion=<version> workshop` -- launcher.jar with workshop\'s files around it, in
  build\workshop\ -- and uploads that folder with the client's own tool, haven.SteamWorkshop, out of the
  client checkout's bin\hafen.jar. The version is the one HEAD is tagged with when the tree is clean --
  run this right after publish.ps1, and the Workshop carries the launcher GitHub does -- and `dev` for
  anything else, as with every build that is not a release. The first upload creates the item and the
  script writes its workshop-id into workshop\workshop-client.properties; every later one updates that
  item, and Steam hands the new launcher to every subscriber. The item carries the launcher only: the
  client is installed and kept up to date by the launcher from GitHub, as ever, so a client release never
  touches the Workshop.

  -Visibility is written into workshop\workshop-client.properties before the build, so the file always says
  what the item is; the tool sets the item's visibility (and its title, description and preview image) from
  that file on every upload, and what the item's web page was given by hand is overwritten by the next one.
  Steam wants the Workshop Legal Agreement accepted before an item is public: the tool says so when it
  applies, and the agreement is accepted once on the item's web page.

  Needs the Steam client running and logged in, java on the PATH, ant, and the client checkout built
  (`ant bin` there).

.PARAMETER Version
  The launcher's version in the jar's manifest (its window title): a number. By default the v* tag HEAD
  carries, when the tree is clean; dev otherwise.
.PARAMETER Message
  The change note Steam shows in the item's change history.
.PARAMETER Visibility
  private, friends or public: written into workshop\workshop-client.properties and applied by the upload.
  Without it, the file's current line stands.
.PARAMETER Client
  The client checkout, whose bin\hafen.jar holds the upload tool: ..\brodgar-io-client by default.
.PARAMETER NoUpload
  Build the item into build\workshop\ and stop: nothing is uploaded and the properties file is left as it is.
#>
param(
    [string]$Version,
    [string]$Message,
    [ValidateSet('private', 'friends', 'public')][string]$Visibility,
    [string]$Client = '..\brodgar-io-client',
    [switch]$NoUpload
)

$ErrorActionPreference = 'Stop'
$appId = '3051280'                                        # Haven & Hearth on Steam
$properties = 'workshop\workshop-client.properties'
$item = 'build\workshop'
Set-Location $PSScriptRoot

function Run {
    param([string]$Exe, [string[]]$Arguments)
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Exe $($Arguments -join ' ') failed with exit code $LASTEXITCODE" }
}

# The file is read and written as bytes: UTF-8 without a BOM and LF, as git holds it.
function Read-Properties { [System.IO.File]::ReadAllText((Resolve-Path $properties).Path) }
function Write-Properties {
    param([string]$Text)
    [System.IO.File]::WriteAllText((Resolve-Path $properties).Path, $Text, (New-Object System.Text.UTF8Encoding $false))
}

# --- checks -------------------------------------------------------------------------------------------------
if ($Version -and $Version -notmatch '^\d+(\.\d+)*$') { throw "the version must be a number, not '$Version'" }
if (-not (Test-Path $properties)) { throw "$properties not found: this is not the launcher checkout" }
$tool = Join-Path $Client 'bin\hafen.jar'
if (-not $NoUpload) {
    if (-not (Test-Path $tool)) { throw "$tool not found: the upload tool is the client's -- run `ant bin` in $Client, or pass -Client" }
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'java is not on the PATH' }
    if (-not (Get-Process -Name steam -ErrorAction SilentlyContinue)) { throw 'the Steam client is not running: start it and log in first' }
}
# the version belongs to its tag: HEAD's, with nothing changed since; anything else is a dev build
$dirty = [bool](git status --porcelain)
if (-not $Version) {
    $tag = if ($dirty) { $null } else { git tag -l 'v*' --points-at HEAD --sort=-v:refname | Select-Object -First 1 }
    $Version = if ($tag) { $tag.Trim() -replace '^v', '' } else { 'dev' }
}

# --- the visibility, into the file that is the item's truth -------------------------------------------------
$text = Read-Properties
if ($Visibility -and -not $NoUpload) {
    if ($text -notmatch '(?m)^visibility=') { throw "$properties has no visibility line to set" }
    $text = $text -replace '(?m)^visibility=.*$', "visibility=$Visibility"
    Write-Properties $text
}
$current = [regex]::Match($text, '(?m)^visibility=(\S+)').Groups[1].Value
$id = [regex]::Match($text, '(?m)^workshop-id=(\d+)').Groups[1].Value

# --- build --------------------------------------------------------------------------------------------------
$head = (git rev-parse --short HEAD).Trim()
Write-Host "Building the Workshop item from $((git rev-parse --abbrev-ref HEAD).Trim()) ($head)$(if ($dirty) { ' (with uncommitted changes)' }), launcher $Version..."
Run ant @("-Dversion=$Version", 'workshop')
if (-not (Test-Path (Join-Path $item 'launcher.jar'))) { throw "the build produced no $item\launcher.jar" }
if ($NoUpload) {
    Write-Host "Item built in $item\ (visibility $current$(if ($id) { ", workshop-id $id" } else { ', not yet uploaded' })). Nothing uploaded (-NoUpload)."
    exit 0
}

# --- upload -------------------------------------------------------------------------------------------------
# The tool talks to the running Steam client and needs the process identified as the game; it prints
# everything to stderr, which is shown as it comes and kept for the id of a new item.
Write-Host "$(if ($id) { "Updating item $id" } else { 'Creating the item' }) as $current..."
$env:SteamAppId = $appId
$upload = @('--enable-native-access=ALL-UNNAMED', '-cp', (Resolve-Path $tool).Path, 'haven.SteamWorkshop', 'upload', $item)
if ($Message) { $upload += $Message }
$eap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'                         # a native command's stderr is output here, not an error
$out = & java @upload 2>&1 | ForEach-Object { $line = "$_"; Write-Host "  $line"; $line }
$ErrorActionPreference = $eap
if ($LASTEXITCODE -ne 0) {
    if ($id -and (($out -join "`n") -match 'submission failure: FileNotFound')) {
        throw "item $id does not exist any more (deleted on Steam?): remove the workshop-id line from $properties and publish again, which creates a new item"
    }
    throw "the upload failed with exit code $LASTEXITCODE"
}

# --- the id of a new item, into the file -------------------------------------------------------------------
$created = [regex]::Match(($out -join "`n"), '(?m)^workshop-id=(\d+)').Groups[1].Value
if ($created -and -not $id) {
    $id = $created
    $text = Read-Properties
    if (-not $text.EndsWith("`n")) { $text += "`n" }
    Write-Properties ($text + "workshop-id=$id`n")
    Write-Host "New item ${id}: workshop-id written into $properties -- commit it, or the next upload creates another item."
}
if (($out -join "`n") -match 'Legal Agreement') {
    Write-Host 'Steam wants the Workshop Legal Agreement accepted before the item is public: accept it once on the item page, then publish again.'
}
Write-Host "Published launcher $Version as item $id, ${current}: https://steamcommunity.com/sharedfiles/filedetails/?id=$id"
