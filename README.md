# Brodgar.io client launcher

The one thing a player installs. It keeps the [Brodgar.io client](https://github.com/irongete/brodgar-io-client)
at the newest GitHub release of the chosen channel and starts it, on the Play button, on the Java runtime
that ships beside the launcher — so nothing else has to be installed.

```text
Brodgar/
  run.bat                starts the launcher on the runtime beside it
  launcher.jar
  runtime/               the Java runtime (jlink) — the launcher's and the client's
  client/                the client release: hafen.jar, lib/, the resource jars, addons/
  client/savedata/       the player's own; the launcher never writes it
  client/installed-version
  launcher.properties    settings, written with their defaults on the first run
  client.log             what the client printed on its last run
```

## What it does

The window opens at once — a status line, a progress bar, the big button, and under them the console
checkbox, the resource-proxy checkbox, the channel dropdown and **Options...** — and the work runs behind it:

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

**Options...** is how the game is started, each setting under a plain name with a line saying what it does
and, at the bottom, the command it all makes: the memory the game is given (a bar from 1 GB to half of
what the PC has, never past 16 GB) and whether it is all reserved at start, how memory is cleaned up
(concurrently, or in short stops), whether Windows may scale the window, which kind of network address to
try first, extra Java options, the resource cache address, and whether to look for a newer version when
the launcher opens. What every client needs to run at all — the module exports, native access, the
Unsafe allowance — is not on offer.

Everything is remembered in `launcher.properties`. The launcher speaks English only, and so does its
runtime: it carries no locale data beyond the JDK's built-in English.

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
| `check.updates` | `true` | `false` never looks for a release and offers what is installed |
| `repo` | `irongete/brodgar-io-client` | where the client's releases are |
| `asset.prefix` | `brodgar-io-client-` | what the release zip's name starts with |

Run with `--check` (`run.bat --check`, or `ant check`) the launcher resolves the channel's newest release
and prints what it would download and the command it would run, without a window.

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
| `ant release` | `build/Brodgar-launcher-<version>-windows.zip`, the release asset |
| `ant check` | the launcher's `--check`, its folder being this one |
| `ant run` | the launcher in this folder (`client/` and `launcher.properties` appear here; ignored by git) |

`-Dversion=0.1.0` names the launcher's version (`0.1.0` by default). Nothing built is committed: the
runtime is a product of the JDK on the build machine, and the release asset is where it travels.

## Release

```powershell
.\release.ps1 0.1.0 -Notes notes.md
```

Refuses a dirty tree or an existing tag, builds from scratch, runs `ant -Dversion=0.1.0 release`, tags HEAD
as `v0.1.0`, pushes the branch and the tag, and creates the GitHub release with the zip as its asset. The
notes come from the file, from `-Message "..."`, or from the commit subjects since the previous `v*` tag.
A version with a suffix is published as a pre-release, a plain one as a release (`-Channel` overrides);
`-NoPublish` stops after the tag; `-Draft` is passed on to GitHub.
