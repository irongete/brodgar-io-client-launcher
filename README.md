# Brodgar.io client launcher

The one thing a player installs. It keeps itself and the [Brodgar.io client](https://github.com/irongete/brodgar-io-client)
at the newest GitHub release of the chosen channel and starts the client, on the Play button, on the Java
runtime that ships beside the launcher — so nothing else has to be installed.

```text
brodgar.io-client-launcher-<version>-windows/   the zip, unzipped: its files sit at its root, so Extract All makes this one folder
  run.bat                starts the launcher on the runtime beside it
  launcher.jar
  runtime/               the Java runtime (jlink) — the launcher's and the client's
  client/                the client release: hafen.jar, lib/, the resource jars, addons/
  client/savedata/       the player's own; the launcher never writes it
  client/installed-version
  launcher.properties    settings, written with their defaults on the first run
  client.log             what the client printed on its last run
```

Started from Steam, the same launcher keeps all of that in `%LOCALAPPDATA%\Brodgar.io` instead, on the Java
Steam ships with the game — see [Steam Workshop](#steam-workshop).

## What it does

The window opens at once — a status line, a progress bar, the big button, and under them the console
checkbox, the resource-proxy checkbox, the channel dropdown, **Options...** and **Open client folder** — and
the work runs behind it:

0. It asks GitHub for the newest release of the launcher itself on the channel and, when that is newer than
   the version in its own manifest, starts the updater and exits — see [Updating itself](#updating-itself).
1. It reads the tag last installed and asks GitHub for the channel's newest release: the list the API
   answers with (one call, no token), the highest version by semver order — `v0.1.0` above `v0.1.0-beta.3`,
   whatever order GitHub lists them in. Should the API be out of reach, the redirect
   `github.com/irongete/brodgar-io-client/releases/latest` answers with — GitHub's latest plain release —
   stands in; when there is none, the Release channel hears that nothing is published, and the Beta channel,
   which cannot tell a beta from nothing that way, that GitHub is unreachable.
2. When they differ, it downloads `releases/download/<tag>/brodgar-io-client-<version>.zip` (the version is
   the tag without its `v`), the bar showing how far, and unpacks it **over** `client/`: what the zip
   carries is replaced, everything else stays — `savedata/`, and any addon the player dropped in. A download
   that goes a minute without a byte is given up as failed, and what a failed or abandoned one left in
   `client/` is removed the next time the launcher looks.
3. The big button becomes **Play**. Pressed, it starts `runtime/bin/javaw.exe -jar hafen.jar` in `client/`
   with the command the Options dialog shows, and the launcher closes. While the channel has nothing
   published the button stays greyed out; when GitHub cannot be reached, the installed client is still
   offered; when nothing is installed and the download failed, the button reads **Retry**.

**Start the client with a console window**, the checkbox: on, the client runs on `runtime/bin/java.exe` in a
command window of its own, which shows what the client prints and stays open when the client ends in an
error — the place to look when something goes wrong. Off, the client starts without a window and
`client.log` holds what it printed.

**Channel**, the dropdown: **Release** installs the highest plain release; **Beta** the highest of everything,
pre-releases included — so a beta player gets a release too when that is the newest thing. The client's
`release.ps1` publishes a version with a suffix (`0.1.0-beta.1`) as a pre-release and a plain one as a
release. Picking a channel looks again at once; while the client is in beta the default is **Beta**.

**Use brodgar.io resource cache proxy**, the checkbox: off, the client reads the game's resources from the
game's own server, the one its `haven-config.properties` names; on, it is started with `-U` and the
proxy's address.

**Open client folder** opens `client/` in Explorer — `hafen.jar`, `savedata/` (the player's own data),
`addons/`, `haven-errors.log` — the place to drop an addon in.

**Options...** is how the game is started, each setting under a plain name with a line saying what it does
and, at the bottom, the command it all makes: the memory the game is given (a bar from 1 GB to half of
what the PC has, never past 16 GB) and whether it is all reserved at start, how memory is cleaned up
(concurrently, or in short stops), whether Windows may scale the window, which kind of network address to
try first, extra Java options, the resource cache address, and whether to look for a newer version when
the launcher opens. What every client needs to run at all — the module exports, native access, the
Unsafe allowance — is not on offer.

Everything is remembered in `launcher.properties`. The launcher speaks English only, and so does its
runtime: it carries no locale data beyond the JDK's built-in English.

## Updating itself

A launcher that finds a newer release of itself on the channel — the same GitHub listing as the client's,
on `launcher.repo` — starts the updater and closes. The updater is a small program of its own in the same
jar (`io.brodgar.launcher.Updater`), run from a copy of the jar in `update/`, since Java holds
`launcher.jar` open for as long as it runs. It shows a window with a progress bar, waits for the launcher to
be gone, downloads `brodgar.io-client-launcher-<version>-windows.zip` into `update/`, unpacks it there, puts
`launcher.jar` and `run.bat` in place — each in one atomic move, so a failure leaves the old file rather than
none — and starts the launcher again, which removes `update/`. The client is never touched.

The runtime cannot be replaced that way: the updater runs on it, and so does the game, possibly for hours.
The release's runtime is left beside it as `runtime.new/`, and `run.bat` swaps it in the next time it starts
the launcher — two renames, the first of which Windows refuses while anything still runs on `runtime/`, in
which case the swap waits for a later start. Until then the new launcher runs on the old runtime, which it
can. When the update fails, a dialog says why and the launcher as it stands is started with
`--no-launcher-update`, so that run gets to the game; the next start tries again. A development run
(`ant run`, `ant check`) is not as shipped and never updates itself.

## Settings

`launcher.properties`, beside the launcher, in UTF-8; delete a line to get its default back. What the
launcher writes, it writes the way Java's `Properties` reads: a backslash in a value is doubled.

| Key | Default | Meaning |
|---|---|---|
| `heap` | `2g` | memory the game is given, reserved when it starts (`-Xms` = `-Xmx`); the dialog's bar sets whole GB |
| `heap.pretouch` | `true` | touch all of it when the game starts (`-XX:+AlwaysPreTouch`) |
| `gc` | `zgc` | `zgc` frees memory concurrently (`-XX:+UseZGC`); `g1` is Java's default collector |
| `ui.scale` | `false` | `true` lets Windows scale the window; `false` passes `-Dsun.java2d.uiScale.enabled=false` |
| `ipv6` | `system` | `-Djava.net.preferIPv6Addresses=`: `system`, `true` (IPv6 first) or `false` (IPv4 first) |
| `java.opts` | *(empty)* | extra JVM options for the client, space-separated |
| `channel` | `beta` | the dropdown: `release` installs plain releases only, `beta` the newest of everything |
| `console` | `false` | the checkbox: start the client on `java.exe` in a command window, kept open when it ends in an error |
| `resource.proxy` | `false` | the checkbox: read resources through the brodgar.io cache proxy (`-U`) |
| `resource.proxy.url` | `http://brodgar.io/res/` | the proxy's address |
| `check.updates` | `true` | `false` never looks for a release — the launcher's own or the game's — and offers what is installed |
| `repo` | `irongete/brodgar-io-client` | where the client's releases are |
| `asset.prefix` | `brodgar-io-client-` | what the release zip's name starts with |
| `launcher.repo` | `irongete/brodgar-io-client-launcher` | where the launcher's own releases are |

Run with `--check` (`run.bat --check`, or `ant check`) the launcher resolves the newest release of itself
and of the channel's client and prints what it would download and the command it would run, without a
window. `--no-launcher-update` starts it without looking for a newer launcher, for that run.

## Build

The runtime the player gets is cut from the JDK the build runs on, so that JDK is the one the client is
verified on: **Eclipse Temurin 25** (LTS), unpacked in this folder as `jdk-25.x/` (ignored by git — it is
never committed) and named by `build.properties`:

```properties
jdk.home=C:/path/to/brodgar-io-client-launcher/jdk-25.0.4.1+1
```

Every step runs on it — `javac`, `jlink`, the development runs. Without that line, the build uses the JDK
`ant` itself runs on, which then has to be a JDK 21 or later carrying `jlink`.

| Target | Produces |
|---|---|
| `ant jar` | `build/launcher.jar`, its manifest carrying the version |
| `ant runtime` | `build/runtime/`, jlink of the modules `hafen.jar` and its libraries need (by `jdeps`) |
| `ant dist` | `build/dist/Brodgar/`, the folder above: the jar, `run.bat`, the runtime |
| `ant release` | `build/brodgar.io-client-launcher-<version>-windows.zip`, the release asset: that folder's contents at the zip's root |
| `ant check` | the launcher's `--check`, its folder being this one |
| `ant run` | the launcher in this folder (`client/` and `launcher.properties` appear here; ignored by git) |
| `ant workshop` | `build/workshop/`, the Steam Workshop item: `launcher.jar` and the files in `workshop/` |

`-Dversion=1.0.1` names the launcher's version (without it, `build.xml`'s default: the last release's, so a
development build says what it is based on). Nothing built is committed: the
runtime is a product of the JDK on the build machine, and the release asset is where it travels.

## Release

```powershell
.\release.ps1 1.0.0 -Notes etc\notes-1.0.0.md
```

Refuses a dirty tree or an existing tag, builds from scratch, runs `ant -Dversion=1.0.0 release`, tags HEAD
as `v1.0.0`, pushes the branch and the tag, and creates the GitHub release with the zip as its asset. The
notes come from the file, from `-Message "..."`, or from the commit subjects since the previous `v*` tag.
A version with a suffix is published as a pre-release, a plain one as a release (`-Channel` overrides);
`-NoPublish` stops after the tag; `-Draft` is passed on to GitHub.

## Steam Workshop

Haven & Hearth's Steam launcher offers, beside the default client, every Workshop client the player is
subscribed to, each an item whose `workshop-client.properties` says how it starts. Ours is
[`workshop/`](workshop/): the item is `launcher.jar` with those files around it, and its launch is direct —
the Steam launcher loads the jar into its own JVM and calls `io.brodgar.launcher.Workshop.main`, which starts
this launcher as a process of its own on the same Java (the runtime Steam ships with the game, which then runs
the client too) and with the same environment, `SteamAppId` included, which is what lets the client's *Log in
with Steam* button work. The launcher's folder is `%LOCALAPPDATA%\Brodgar.io` — `client/`, `savedata/`,
`launcher.properties`, `client.log` and `launcher.log` (what the launcher itself printed) — never the item's,
which Steam rewrites on every update. There is no `runtime/` there, so the launcher never updates itself:
uploading a new item is how it is updated on Steam. The client is installed and kept up to date from GitHub
as ever.

The item is published by [`publish-steam.ps1`](publish-steam.ps1), with the Steam client running and logged in,
`java` and `ant` on the PATH, and the client checkout built (`ant bin` there: the upload tool,
`haven.SteamWorkshop`, is the client's own, in its `bin\hafen.jar`):

```powershell
.\publish-steam.ps1                                # the launcher as it stands, visibility as the file says
.\publish-steam.ps1 -Version 1.0.1 -Message "..."  # the version the window shows, and a change note
.\publish-steam.ps1 -Visibility public             # flip the item public (private, friends: the same way)
.\publish-steam.ps1 -NoUpload                      # build build\workshop\ and stop
```

It runs `ant workshop` and uploads `build\workshop\`. The first upload created the item — `workshop-id` in
[`workshop/workshop-client.properties`](workshop/workshop-client.properties) — and every later one updates it,
after which Steam hands the new launcher to every subscriber (Haven & Hearth's launcher downloads a stale item
on its next start). `-Visibility` is written into that file before the build, so the file always says what
the item is; the upload applies the file's visibility, title, description and preview image every time, and
overwrites what the item's web page was given by hand. `-Version` stamps the jar's manifest, as `ant
-Dversion=` does for a release; without it, the newest `v*` tag reachable from HEAD. Steam wants the
Workshop Legal Agreement accepted before an item goes public — the tool says so when it applies, and it is
accepted once, on the item's web page.
