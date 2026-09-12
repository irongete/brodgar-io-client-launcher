package io.brodgar.launcher;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The Brodgar.io client launcher: keeps <code>client/</code> at the newest GitHub release of the client on the
 * chosen channel and starts it, on the Play button, on the runtime that ships beside this launcher.
 *
 * <p>Everything lives in one folder, the one <code>launcher.jar</code> is in:
 * <pre>
 *   run.bat                starts the launcher on the runtime below
 *   launcher.jar
 *   runtime/               the Java runtime (jlink), the launcher's and the client's
 *   client/                the client release: hafen.jar, lib/, the resource jars, addons/
 *   client/savedata/       the player's own; never written by the launcher
 *   client/installed-version
 *   launcher.properties    settings, written with defaults on the first run
 *   client.log             what the client printed on its last run
 * </pre>
 *
 * <p>The window opens at once; the release check and the download run behind it, and the big button becomes
 * <b>Play</b> when the channel's newest release is installed — greyed out while the channel has nothing,
 * <b>Retry</b> when nothing is installed and the download failed. The dropdown is the channel — <b>Release</b>
 * installs plain releases, <b>Beta</b> the newest of everything, pre-releases included — and picking one looks
 * again at once. The checkbox is the brodgar.io resource cache proxy: off, the client reads the game's own
 * resource server (what its shipped haven-config.properties names); on, it is started with <code>-U</code>
 * and the proxy's URL. Options opens the {@link OptionsDialog}. Everything is remembered in
 * <code>launcher.properties</code>. Run with <code>--check</code> it resolves the channel's newest release and
 * prints what it would download and how it would start the client, and exits without touching anything or
 * opening a window.
 */
public final class Launcher {
    private final Path home;
    private final Settings settings;
    private final ClientInstall client;
    private final Path java;
    private final Ui ui;

    private Launcher(Path home, Settings settings, ClientInstall client, Path java, Ui ui) {
        this.home = home;
        this.settings = settings;
        this.client = client;
        this.java = java;
        this.ui = ui;
    }

    public static void main(String[] args) {
        boolean check = Arrays.asList(args).contains("--check");
        Path home = home();
        Settings settings = Settings.load(home.resolve("launcher.properties"));
        ClientInstall client = new ClientInstall(home.resolve("client"));
        Path java = javaw(home);
        if(check) {
            check(home, settings, client, java);
            return;
        }
        Launcher[] l = new Launcher[1];
        Ui ui = Ui.open(title(client.installedVersion()), settings.channel(), c -> l[0].channel(c),
                        settings.resourceProxy(), settings::resourceProxy, () -> l[0].options());
        l[0] = new Launcher(home, settings, client, java, ui);
        new Thread(l[0]::prepare, "launcher-update").start();
    }

    /** What the release check left the launcher with. */
    private enum Outcome {
        /** The channel's newest release is installed. */
        READY,
        /** GitHub answered and the channel has nothing: nothing to play, nothing to retry. */
        NO_RELEASE,
        /** GitHub could not be reached or the download failed, and a client is installed: play that. */
        INSTALLED_ANYWAY,
        /** GitHub could not be reached or the download failed, and nothing is installed: retry. */
        NOTHING,
    }

    /** The dropdown: remember the channel and look for its newest release at once. Only reachable while no
     *  work is going on, since the window holds the dropdown until the check is over. */
    private void channel(Channel c) {
        settings.channel(c);
        new Thread(this::prepare, "launcher-update").start();
    }

    /** The Options button, on the event thread: modal, so nothing else happens while it is open. */
    private void options() {
        OptionsDialog.show(ui.frame(), settings, java);
    }

    /** Bring the client up to date, then offer what there is. */
    private void prepare() {
        ui.busy();
        Outcome o;
        try {
            o = update();
        } catch(Exception e) {
            ui.status("Unexpected: " + e);
            o = client.isInstalled() ? Outcome.INSTALLED_ANYWAY : Outcome.NOTHING;
        }
        switch(o) {
            case READY, INSTALLED_ANYWAY -> ui.ready("Play", this::play);
            case NO_RELEASE -> ui.idle();
            case NOTHING -> ui.ready("Retry", this::prepare);
        }
    }

    /** Fetch the channel's newest release when it differs from the installed one, saying in the status line
     *  what happened. */
    private Outcome update() {
        String installed = client.installedVersion();
        if(!settings.checkUpdates()) {
            ui.status((installed == null) ? "Update check is off (Options) and no client is installed."
                                          : "Client " + installed + " — update check is off (Options).");
            return (installed == null) ? Outcome.NO_RELEASE : Outcome.READY;
        }
        Channel channel = settings.channel();
        String kind = channel.label.toLowerCase();
        String latest;
        try {
            ui.status("Looking for the newest " + kind + "...");
            latest = GitHubRelease.newestTag(settings.repo(), channel);
        } catch(GitHubRelease.NoReleaseException e) {
            ui.status("No " + kind + " has been published yet" + ((installed == null) ? "." : " — pick another channel to play " + installed + "."));
            return Outcome.NO_RELEASE;
        } catch(Exception e) {
            ui.status((installed == null) ? "GitHub is unreachable: " + e.getMessage()
                                          : "GitHub is unreachable — client " + installed + " is installed.");
            return (installed == null) ? Outcome.NOTHING : Outcome.INSTALLED_ANYWAY;
        }
        if(latest.equals(installed)) {
            ui.status("Client " + installed + " is the newest " + kind + ".");
            return Outcome.READY;
        }
        try {
            String url = GitHubRelease.assetUrl(settings.repo(), latest, settings.assetPrefix());
            ui.status((installed == null) ? "Downloading client " + latest + " (" + kind + ")..." : "Installing " + latest + " (" + kind + ") over " + installed + "...");
            Files.createDirectories(client.dir());
            Path zip = client.dir().resolve("download.tmp");
            GitHubRelease.download(url, zip, ui::progress);
            ui.status("Installing client " + latest + "...");
            client.install(zip, latest);
            Files.deleteIfExists(zip);
            ui.title(title(latest));
            ui.status("Client " + latest + " is ready.");
            return Outcome.READY;
        } catch(Exception e) {
            ui.status((installed == null) ? "The download failed: " + e.getMessage()
                                          : "The update failed (" + e.getMessage() + ") — client " + installed + " is installed.");
            return (installed == null) ? Outcome.NOTHING : Outcome.INSTALLED_ANYWAY;
        }
    }

    /** The Play button: start the client and leave, unless it dies at once, in which case say so and stay. */
    private void play() {
        ui.busy();
        ui.status("Starting the client...");
        try {
            Process p = start();
            if(p.waitFor(3, TimeUnit.SECONDS)) {
                ui.error("The client exited at once (code " + p.exitValue() + ").\n\n" + tail(home.resolve("client.log"), 12));
                ui.status("The client did not start.");
                ui.ready("Play", this::play);
                return;
            }
            ui.close();
            System.exit(0);
        } catch(Exception e) {
            ui.error(e.toString());
            ui.status("The client did not start.");
            ui.ready("Play", this::play);
        }
    }

    private Process start() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command(java, settings))
            .directory(client.dir().toFile())
            .redirectErrorStream(true)
            .redirectOutput(home.resolve("client.log").toFile());
        return pb.start();
    }

    /** The client's command line as the settings stand. */
    static List<String> command(Path java, Settings s) {
        return command(java, s.launch());
    }

    /**
     * The client's command line from its parts — what the Options dialog previews and what Play runs. The
     * settings shape the memory, the collector, the window scaling and the address preference; the rest is
     * what every client needs: the module exports and native access <code>run.bat</code> passes, plus what a
     * runtime past 23 wants; then whatever extra options were given, and <code>-U</code> with the cache proxy's
     * URL only while the checkbox is on.
     */
    static List<String> command(Path java, Settings.Launch l) {
        int feature = Runtime.version().feature();   // the same runtime runs the launcher and the client
        List<String> cmd = new ArrayList<>();
        cmd.add(java.toString());
        cmd.add("-Xms" + l.heap());
        cmd.add("-Xmx" + l.heap());
        if(l.pretouch())
            cmd.add("-XX:+AlwaysPreTouch");
        if(l.gc().equals("zgc")) {
            cmd.add("-XX:+UseZGC");
            if(feature < 24)
                cmd.add("-XX:+ZGenerational");               // the default from 23, an obsolete flag from 24
        }
        if(feature >= 23)
            cmd.add("--sun-misc-unsafe-memory-access=allow");  // JOGL and LWJGL still use it; 24+ warns without this
        cmd.add("--add-exports=java.base/java.lang=ALL-UNNAMED");
        cmd.add("--add-exports=java.desktop/sun.awt=ALL-UNNAMED");
        cmd.add("--add-exports=java.desktop/sun.java2d=ALL-UNNAMED");
        cmd.add("--enable-native-access=ALL-UNNAMED");
        if(!l.uiScale())
            cmd.add("-Dsun.java2d.uiScale.enabled=false");
        cmd.add("-Djava.net.preferIPv6Addresses=" + l.ipv6());
        cmd.addAll(l.opts());
        cmd.add("-jar");
        cmd.add("hafen.jar");
        if(l.proxy()) {
            cmd.add("-U");
            cmd.add(l.proxyUrl());
        }
        return cmd;
    }

    private static void check(Path home, Settings settings, ClientInstall client, Path java) {
        System.out.println("home:      " + home);
        System.out.println("runtime:   " + java + (Files.exists(java) ? "" : "  (MISSING)"));
        System.out.println("installed: " + client.installedVersion());
        System.out.println("channel:   " + settings.channel().key);
        try {
            String latest = GitHubRelease.newestTag(settings.repo(), settings.channel());
            System.out.println("newest:    " + latest);
            System.out.println("asset:     " + GitHubRelease.assetUrl(settings.repo(), latest, settings.assetPrefix()));
        } catch(GitHubRelease.NoReleaseException e) {
            System.out.println("newest:    none on this channel (" + e.getMessage() + ")");
        } catch(Exception e) {
            System.out.println("newest:    unreachable: " + e);
        }
        System.out.println("proxy:     " + (settings.resourceProxy() ? "on, " + settings.resourceProxyUrl() : "off (the game's own resource server)"));
        System.out.println("command:   " + String.join(" ", command(java, settings)));
        System.out.println("cwd:       " + client.dir());
    }

    /** The folder the launcher lives in: <code>launcher.home</code> when set (the development run names this
     *  folder), else the folder <code>launcher.jar</code> is in, else the working directory. */
    static Path home() {
        String set = System.getProperty("launcher.home");
        if(set != null)
            return Paths.get(set).toAbsolutePath();
        try {
            CodeSource src = Launcher.class.getProtectionDomain().getCodeSource();
            if(src != null) {
                Path self = Paths.get(src.getLocation().toURI());
                if(Files.isRegularFile(self))               // the jar; a classes/ folder is a development run
                    return self.toAbsolutePath().getParent();
            }
        } catch(URISyntaxException | RuntimeException e) {
            // fall through to the working directory
        }
        return Paths.get("").toAbsolutePath();
    }

    /** The runtime's windowless Java: <code>runtime/bin/javaw.exe</code> beside the launcher, or the JVM running
     *  this launcher when there is no such image (development). */
    static Path javaw(Path home) {
        Path shipped = home.resolve("runtime").resolve("bin").resolve("javaw.exe");
        if(Files.exists(shipped))
            return shipped;
        Path own = Paths.get(System.getProperty("java.home"), "bin", "javaw.exe");
        return Files.exists(own) ? own : Paths.get(System.getProperty("java.home"), "bin", "java");
    }

    /** The window title: the launcher's version from the jar's manifest, and the installed client's. */
    private static String title(String installed) {
        String v = Launcher.class.getPackage().getImplementationVersion();
        return "Brodgar.io" + ((v == null) ? "" : " launcher " + v) + ((installed == null) ? "" : " · client " + installed);
    }

    private static String tail(Path log, int lines) {
        try {
            List<String> all = Files.readAllLines(log);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch(IOException e) {
            return "(no log)";
        }
    }
}
