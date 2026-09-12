# Brodgar.io client launcher

The one thing a player installs. It keeps the [Brodgar.io client](https://github.com/irongete/brodgar-io-client)
at its latest GitHub release and starts it, on the Play button, on the Java runtime that ships beside the
launcher — so nothing else has to be installed: no Java, no `run.bat`.

```text
Brodgar/
  Brodgar.exe            the launcher
  runtime/               the Java runtime (jlink) — the launcher's and the client's
  app/launcher.jar
  client/                the client release: hafen.jar, lib/, the resource jars, addons/
  client/savedata/       the player's own; the launcher never writes it
  client/installed-version
  launcher.properties    settings, written with their defaults on the first run
  client.log             what the client printed on its last run
```

## What it does

The window opens at once — a status line, a progress bar, the channel dropdown, a checkbox and one button —
and the work runs behind it:

1. It reads the tag last installed and asks GitHub for the channel's newest release: the list the API
   answers with (one call, no token), the highest version by semver order — `v0.1.0` above `v0.1.0-beta.3`,
   whatever order GitHub lists them in. Should the API be out of reach, the redirect
   `github.com/irongete/brodgar-io-client/releases/latest` answers with stands in.
2. When they differ, it downloads `releases/download/<tag>/brodgar-io-client-<version>.zip` (the version is
   the tag without its `v`), the bar showing how far, and unpacks it **over** `client/`: what the zip
   carries is replaced, everything else stays — `savedata/`, and any addon the player dropped in.
3. The button becomes **Play**. Pressed, it starts `runtime/bin/javaw.exe -jar hafen.jar` in `client/`,
   with the flags the client's own `ant run` and `run.bat` use, and the launcher closes. When GitHub
   cannot be reached, the installed client is still offered; when nothing is installed and the download
   failed, the button reads **Retry**.

**Channel**, the dropdown: **Release** installs the highest plain release; **Beta** the highest of everything,
pre-releases included — so a beta player gets a release too when that is the newest thing. The client's
`release.ps1` publishes a version with a suffix (`0.1.0-beta.1`) as a pre-release and a plain one as a
release. Picking a channel looks again at once, and the choice is remembered in `launcher.properties`;
while the client is in beta the default is **Beta**.

**Use brodgar.io resource cache proxy**, the checkbox: off, the client reads the game's resources from the
game's own server, the one its `haven-config.properties` names; on, it is started with
`-U http://brodgar.io/res/`, the cache proxy. The choice is remembered too.

The launcher speaks English only, and so does its runtime: it carries no locale data beyond the JDK's
built-in English.

## Settings

`launcher.properties`, beside the launcher; delete a line to get its default back.

| Key | Default | Meaning |
|---|---|---|
| `heap` | `2g` | the client's heap, fixed and pre-touched |
| `repo` | `irongete/brodgar-io-client` | where the client's releases are |
| `asset.prefix` | `brodgar-io-client-` | what the release zip's name starts with |
| `channel` | `beta` | the dropdown: `release` installs plain releases only, `beta` the newest of everything |
| `resource.proxy` | `false` | the checkbox: read resources through the brodgar.io cache proxy (`-U`) |
| `resource.proxy.url` | `http://brodgar.io/res/` | the proxy's URL |
| `check.updates` | `true` | `false` never looks for a release and offers what is installed |
| `java.opts` | *(empty)* | extra JVM options for the client, space-separated |

## Build

The runtime the player gets is cut from the JDK the build runs on, so that JDK is the one the client is
verified on: **Eclipse Temurin 25** (LTS), unpacked in this folder as `jdk-25.x/` (ignored by git — it is
never committed) and named by `build.properties`:

```properties
jdk.home=C:/path/to/brodgar-io-client-launcher/jdk-25.0.4.1+1
```

Every step runs on it — `javac`, `jlink`, `jpackage`, the development runs. Without that line, the build
uses the JDK `ant` itself runs on, which then has to be a JDK 21 or later carrying `jlink` and `jpackage`.

| Target | Produces |
|---|---|
| `ant jar` | `build/input/launcher.jar` |
| `ant runtime` | `build/runtime/`, jlink of the modules `hafen.jar` and its libraries need (by `jdeps`) |
| `ant image` | `build/image/Brodgar/`, the folder above, by jpackage |
| `ant release` | `build/Brodgar-launcher-<version>-windows.zip`, the release asset |
| `ant check` | resolves the channel's newest release and prints the command the launcher would run, touching nothing |
| `ant run` | the launcher in this folder (`client/` and `launcher.properties` appear here; ignored by git) |

`-Dversion=0.1.0` names the launcher's version (`0.1.0` by default); jpackage takes its leading digits.
Nothing built is committed: the runtime is a product of the JDK on the build machine, and the release asset
is where it travels.

## Release

```powershell
.\release.ps1 0.1.0 -Notes notes.md
```

Refuses a dirty tree or an existing tag, builds from scratch, runs `ant -Dversion=0.1.0 release`, tags HEAD
as `v0.1.0`, pushes the branch and the tag, and creates the GitHub release with the zip as its asset. The
notes come from the file, from `-Message "..."`, or from the commit subjects since the previous `v*` tag.
A version with a suffix is published as a pre-release, a plain one as a release (`-Channel` overrides);
`-NoPublish` stops after the tag; `-Draft` is passed on to GitHub.
