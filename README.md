# Brodgar launcher

The launcher for the [Brodgar client](https://github.com/irongete/brodgar-io-client) of Haven & Hearth.
Windows, macOS (Apple Silicon: M1 and later) and Linux (x64); it brings its own Java.

- **Installs the client** and keeps it at the newest release, on the **Release** or the **Beta** channel.
- **Updates itself** the same way.
- **Fetches the game's resources from brodgar.io** (two checkboxes in **Options...**): the **resource
  pack**, `client/brodgar-res.jar`, holds every resource from the first start (one download of about
  250 MB; renewed at most monthly, and only when there is something new), and the **resource cache**
  answers what the pack has not got in a fraction of the official server's time.
  `launcher.properties`: `resource.pack`, `resource.pack.url`, `resource.pack.renew.days`, `resource.proxy`.
- **Starts the game** with the memory and Java options you set in **Options...**.

## How to use

1. Download it for your system and start it:
   - **Windows**: **[brodgar.io-launcher-windows-x64.zip](https://github.com/irongete/brodgar-io-client-launcher/releases/latest/download/brodgar.io-launcher-windows-x64.zip)**.
     Unzip it anywhere (the client and your game data will live in that folder) and run `run.bat`.
   - **macOS**: **[brodgar.io-launcher-macos-arm64.zip](https://github.com/irongete/brodgar-io-client-launcher/releases/latest/download/brodgar.io-launcher-macos-arm64.zip)**.
     Open it and double-click **Brodgar launcher**; you can move it to Applications or the Dock first. The app is
     not signed by Apple, so the first time macOS refuses it: open **System Settings**, **Privacy &
     Security**, and at the bottom press **Open Anyway** by *"Brodgar launcher" was blocked*. Once only; from then
     on it opens with a double-click. The client and your game data live in
     `~/Library/Application Support/Brodgar.io`.
   - **Linux**: **[brodgar.io-launcher-linux-x64.zip](https://github.com/irongete/brodgar-io-client-launcher/releases/latest/download/brodgar.io-launcher-linux-x64.zip)**.
     Unzip it anywhere (the `brodgar.io-launcher` folder is where the client and your game data will live)
     and run `run.sh` in it: in a terminal, or in the file manager with a right click, **Run as a Program**.
     From then on **Brodgar launcher** is in your applications menu.
2. The first start asks four things, a page each: the resource pack, the resource cache, the SQLite store and
   the game's memory (all in **Options...** later; `firstrun=true` in `launcher.properties` asks again). Then
   it downloads the client.
3. Press **Play**. The launcher stays open; press it again for another client.

On Steam (Windows), subscribe to the *Brodgar* item in the Workshop of Haven & Hearth and pick it in the
game's launcher; the files then live in `%LOCALAPPDATA%\Brodgar.io`.

**Start the client with a console window** (Windows) shows what the game prints, for when it will not start;
`client.log` keeps the same for the last run, on every system. **Open client folder** opens `client/`:
`savedata` is your game data, `addons` your addons.

**Downloads**: [launcher releases](https://github.com/irongete/brodgar-io-client-launcher/releases) ·
[client releases](https://github.com/irongete/brodgar-io-client/releases) (the pre-releases are the Beta
channel)
