# Brodgar.io Launcher

The launcher for the [brodgar.io client](https://github.com/irongete/brodgar-io-client) of Haven & Hearth:
it downloads the client, keeps it and itself up to date, and starts the game. Windows only. It brings its own
Java, so there is nothing to install.

## Getting it

1. Download `brodgar.io-launcher.zip` from the
   [latest release](https://github.com/irongete/brodgar-io-client-launcher/releases/latest).
2. Unzip it anywhere — a folder of its own, since the client and your game data will live in it.
3. Run `run.bat`.

The first start downloads the client (about 85 MB); from then on the launcher only downloads what is new.
Move the folder and everything moves with it.

On Steam, subscribe to the *Brodgar* item in the Workshop of Haven & Hearth and pick it in the game's own
launcher: it starts this one, on Steam's Java, with your Steam login available in the client. The files
then live in `%LOCALAPPDATA%\Brodgar.io`.

## The window

At the top, the game's trailer: click to play or pause, double-click for full screen (Esc or another
double-click comes back), and a bar over its bottom edge, while the mouse is on it, to seek. Under it, a link
to the trailer on YouTube.

Then the status line, which says what the launcher is doing — looking for the newest client, downloading it,
ready — and a progress bar for the download. Beside them, **Play**.

- **Play** starts the game. The launcher stays open while it runs, with Play disabled, and offers it again
  when the game exits. If the client dies at once, a dialog shows its exit code and the last lines of
  `client.log`.
- **Start the client with a console window**: the game runs in a command window that shows what it prints
  and stays open if it ends in an error. For finding out why it will not start.
- **Use brodgar.io resource cache proxy**: the game fetches its resources through brodgar.io's cache instead
  of the game server. Faster loading, less load on the server.
- **Options...**: how the game is started — see below.
- **Open client folder**: the client's folder in Explorer: `hafen.jar`, `savedata`, `addons`.
- **Channel**: *Release* installs the newest release of the client; *Beta* the newest of everything, betas
  included. Changing it installs that channel's newest right away. A channel with nothing published yet keeps
  Play disabled until you pick the other.

## Updates

Every start looks for the newest client on the channel and installs it if it is newer than the installed
one. Your game data (`savedata`, addons you added yourself) is kept. The newest download of each channel is
kept too, so switching between Release and Beta and back does not download the same client twice.

The launcher also updates itself: when a newer launcher is published, it installs it and restarts before
going on. On Steam the Workshop item is the update, as with any Workshop item.

## Options

- **Memory**: how much the game gets, reserved when it starts. The slider goes up to half your machine's
  memory, 16 GB at most.
- **Reserve it all at start**: the memory is claimed at once rather than page by page as the game first uses
  it. A slower start, smoother afterwards.
- **Garbage collector**: how the game frees memory it no longer uses. *Concurrent (ZGC)* does it while the
  game keeps running; *Standard (G1)* is Java's default, in short stops.
- **Let Windows scale the game window**: on, Windows scales the window on a high-DPI screen; off, the game
  draws at 1:1 and scales its interface itself.
- **IPv6**: which address to try first when a server has both kinds.
- **Extra Java options**: anything else for the Java that runs the game, space-separated.
- **Override addons folder** / **Override savedata folder**: a folder of your choice instead of the client's
  own `addons` and `savedata`.
- **Look for a newer launcher and game when the launcher opens**: off, the launcher offers whatever client is
  installed and asks GitHub for nothing.

The dialog previews the command the game will be started with. OK writes the settings to
`launcher.properties`, beside the launcher; Cancel keeps the old ones.

## Files

```text
run.bat                starts the launcher
launcher.jar           the launcher
runtime/               its Java, which also runs the game
client/                the game client
client/savedata/       your game data: settings, maps, everything the client saves
client/addons/         addons
cache/                 the newest client download of each channel
launcher.properties    the settings, as Options writes them
client.log             what the game printed the last time it ran
```

## If something goes wrong

- **The game does not start**: turn on *Start the client with a console window* and press Play; the console
  shows what the client printed and stays open. `client.log` has the same for the last run without the
  console.
- **"GitHub is unreachable"**: the launcher could not ask GitHub for the newest client. If one is installed,
  Play still works with it.
- **A download failed**: **Retry** tries again; a download is dropped when nothing arrives for a minute.
- **The launcher will not update itself**: a new launcher needs to replace `runtime/`, which it cannot do
  while the game runs on it; it finishes on a later start, when nothing does.
