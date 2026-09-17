# Brodgar.io client launcher

Installs and updates the [Brodgar.io client](https://github.com/irongete/brodgar-io-client) from GitHub
releases, updates itself the same way, and starts the client on the bundled Java runtime.

```text
brodgar.io-client-launcher-<version>-windows/   the zip's contents, at its root
  run.bat                starts launcher.jar on runtime/
  launcher.jar
  runtime/               jlink runtime; runs the launcher and the client
  client/                the client release: hafen.jar, lib/, resource jars, addons/, haven-config.properties
  client/savedata/       client data; never written by the launcher
  client/installed-version
  launcher.properties    settings; created with defaults on first run
  client.log             stdout/stderr of the last client run
```

Started from Steam, the folder is `%LOCALAPPDATA%\Brodgar.io` and the runtime is Steam's — see
[Steam Workshop](#steam-workshop).

## Behaviour

Window: status line, progress bar, main button; console checkbox; proxy checkbox, channel dropdown,
**Options...**; **Open client folder**. On start:

0. GET the launcher's own releases (`launcher.repo`). A tag newer than the jar manifest's version starts the
   updater and exits — see [Self-update](#self-update).
1. Read `client/installed-version`. GET `https://api.github.com/repos/<repo>/releases` (one call, no token);
   pick the highest semver tag on the channel (`v0.1.0` > `v0.1.0-beta.3`, independent of API order). If the
   API fails, follow the redirect of `github.com/<repo>/releases/latest` (latest non-prerelease). No result:
   Release channel reports "nothing published"; Beta channel reports "unreachable" (a beta cannot be
   detected via the redirect).
2. If the tag differs from the installed one: download `releases/download/<tag>/<asset.prefix><version>.zip`
   (`version` = tag without `v`) to `client/download.tmp`, unpack over `client/` (`REPLACE_EXISTING`; files
   not in the zip are kept — `savedata/`, addons added by hand), write `installed-version`. A download with
   no bytes for 60 s fails. `download.tmp`/`download.tmp.part` left by a failed run are deleted on the next
   start.
3. Main button = **Play**: writes `client/haven-config.properties` (see below), runs the command from
   Options with cwd `client/`, exits. If the process ends within 3 s, an error dialog shows the exit code and
   the tail of `client.log`. Nothing published: button disabled. GitHub unreachable with a client installed:
   Play. Nothing installed and download failed: **Retry**.

**Start the client with a console window** (`console`): on, `runtime/bin/java.exe` via
`cmd /c start /wait ... || pause`, output on the console; off, `runtime/bin/javaw.exe`, output redirected to
`client.log`.

**Channel** (`channel`): `release` = highest non-prerelease tag; `beta` = highest tag of all. The client's
`release.ps1` publishes `x.y.z-suffix` as prerelease, `x.y.z` as release. Changing the channel re-runs step 1
at once. Default `beta`.

**Use brodgar.io resource cache proxy** (`resource.proxy`): selects `haven.resurl` in
`client/haven-config.properties` — on: `resource.proxy.url`; off: `resource.url`. Written on toggle, on
Options close, and before every Play (an unpacked release restores the zip's copy of the file). Only that
line is rewritten; the rest of the file is kept. Not passed on the command line.

**Open client folder**: opens `client/` in Explorer.

**Options...**: `heap`, `heap.pretouch`, `gc`, `ui.scale`, `ipv6`, `java.opts`, `resource.proxy.url`,
`addons.dir`, `check.updates` — see [Settings](#settings) — with a live preview of the resulting command.
Not configurable: `--add-exports`/`--enable-native-access`/`--sun-misc-unsafe-memory-access=allow`.

Locale: English only; the runtime carries no other locale data.

## Self-update

If step 0 finds a newer tag, the launcher copies `launcher.jar` to `update/` (the running jar is locked),
starts `io.brodgar.launcher.Updater` from that copy and exits. The updater waits for the launcher process
to end, downloads `brodgar.io-client-launcher-<version>-windows.zip` into `update/`, unpacks it, moves
`launcher.jar` and `run.bat` into place (atomic move each), restarts the launcher, which deletes `update/`.
`client/` is not touched.

`runtime/` cannot be replaced while in use: the new one is left as `runtime.new/` and `run.bat` swaps it in
(two renames) on a later start when nothing runs on `runtime/`. On failure the updater shows the error and
starts the current launcher with `--no-launcher-update`; the next start retries. Development runs (`ant run`,
`ant check`) never self-update.

## Settings

`launcher.properties`, UTF-8, beside `launcher.jar`. A missing key takes its default. Values are written
`Properties`-escaped (backslashes doubled).

| Key | Default | Effect |
|---|---|---|
| `heap` | `2g` | `-Xms<heap> -Xmx<heap>` |
| `heap.pretouch` | `true` | `-XX:+AlwaysPreTouch` |
| `gc` | `zgc` | `zgc`: `-XX:+UseZGC` (`-XX:+ZGenerational` on JDK < 24); `g1`: nothing (JVM default) |
| `ui.scale` | `false` | `false`: `-Dsun.java2d.uiScale.enabled=false`; `true`: nothing |
| `ipv6` | `system` | `-Djava.net.preferIPv6Addresses=<system\|true\|false>` |
| `java.opts` | *(empty)* | appended to the JVM arguments, space-separated |
| `channel` | `beta` | `release` \| `beta` |
| `console` | `false` | `true`: `java.exe` in a `cmd` window; `false`: `javaw.exe`, output to `client.log` |
| `resource.proxy` | `false` | `haven.resurl` in `client/haven-config.properties` := `true` ? `resource.proxy.url` : `resource.url` |
| `resource.url` | `https://game.havenandhearth.com/res/` | |
| `resource.proxy.url` | `http://brodgar.io/res/` | |
| `addons.dir` | *(empty)* | `haven.addondir` in `client/haven-config.properties`; empty: line removed (client default `client/addons`). The client puts `savedata/` beside that folder |
| `check.updates` | `true` | `false`: skip steps 0 and 1, offer the installed client |
| `repo` | `irongete/brodgar-io-client` | GitHub `owner/repo` of the client releases |
| `asset.prefix` | `brodgar-io-client-` | asset name = `<asset.prefix><version>.zip` |
| `launcher.repo` | `irongete/brodgar-io-client-launcher` | GitHub `owner/repo` of the launcher releases |

Command line: `--check` resolves both newest releases and prints home, runtime, versions, asset URL, the
`haven-config.properties` lines, the client command and cwd, then exits (no window, no writes).
`--no-launcher-update` skips step 0 for that run.

## Build

Requires a JDK with `jlink`, named in `build.properties`; the client is verified on **Eclipse Temurin 25**,
unpacked in this folder as `jdk-25.x/` (gitignored):

```properties
jdk.home=C:/path/to/brodgar-io-client-launcher/jdk-25.0.4.1+1
```

Without it, the JDK running `ant` is used (21+ with `jlink`).

| Target | Output |
|---|---|
| `ant jar` | `build/launcher.jar` (version in the manifest) |
| `ant runtime` | `build/runtime/`: `jlink` of the modules `jdeps` reports for `hafen.jar` and its libraries |
| `ant dist` | `build/dist/Brodgar/`: `launcher.jar`, `run.bat`, `runtime/` |
| `ant release` | `build/brodgar.io-client-launcher-<version>-windows.zip`: the `dist` folder's contents at the zip root |
| `ant check` | `--check` with `launcher.home` = this folder |
| `ant run` | the launcher with `launcher.home` = this folder (`client/`, `launcher.properties` gitignored) |
| `ant workshop` | `build/workshop/`: `launcher.jar` + `workshop/` |

`-Dversion=1.0.1` sets the version; default: `build.xml`'s, the last release's. Build output is not
committed.

## Release

```powershell
.\release.ps1 1.0.0 -Notes etc\notes-1.0.0.md
```

Requires a clean tree and no existing tag. Runs `ant -Dversion=1.0.0 release`, tags HEAD `v1.0.0`, pushes
branch and tag, creates the GitHub release with the zip as asset. Notes: `-Notes <file>`, `-Message "..."`,
or the commit subjects since the previous `v*` tag. Suffix → prerelease, plain → release (`-Channel`
overrides). `-NoPublish` stops after the tag; `-Draft` is passed to GitHub.

## Steam Workshop

Haven & Hearth's Steam launcher lists subscribed Workshop clients; each item's `workshop-client.properties`
names its entry point. Ours is [`workshop/`](workshop/): `launcher.jar` plus those files. Steam's launcher
loads the jar into its JVM and calls `io.brodgar.launcher.Workshop.main`, which starts this launcher as a
child process on the same Java (Steam's runtime, which then also runs the client) with the same environment
(`SteamAppId` included — required by the client's *Log in with Steam*). `launcher.home` =
`%LOCALAPPDATA%\Brodgar.io`: `client/`, `launcher.properties`, `client.log`, `launcher.log` (launcher
stdout/stderr). Nothing is written to the item folder, which Steam rewrites on update. No `runtime/` there,
so no self-update: a new item upload is the update. The client is still installed from GitHub.

[`publish-steam.ps1`](publish-steam.ps1) uploads the item. Requires: Steam client running and logged in,
`java` and `ant` on PATH, the client checkout built (`ant bin`: the upload tool `haven.SteamWorkshop` is in
its `bin\hafen.jar`).

```powershell
.\publish-steam.ps1                                # upload; visibility as workshop-client.properties says
.\publish-steam.ps1 -Version 1.0.1 -Message "..."  # manifest version, change note
.\publish-steam.ps1 -Visibility public             # public | private | friends
.\publish-steam.ps1 -NoUpload                      # ant workshop only
```

Runs `ant workshop`, uploads `build\workshop\`. `workshop-id` in
[`workshop/workshop-client.properties`](workshop/workshop-client.properties) was set by the first upload;
later uploads update that item (Steam's launcher re-downloads it on its next start). `-Visibility` is written
to the file before the build; every upload applies the file's visibility, title, description and preview
image, overwriting edits made on the item's web page. `-Version` sets the manifest version (like
`-Dversion=`); default: newest `v*` tag reachable from HEAD. A public item needs the Workshop Legal Agreement
accepted once on the item's web page; the tool reports when that applies.
