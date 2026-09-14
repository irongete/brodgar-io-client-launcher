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
 * again at once. One checkbox is the console: off, the client starts without a window of its own and what it
 * prints goes to <code>client.log</code>; on, it runs in a command window that shows what it prints and stays
 * open when it ends in an error. The other is the brodgar.io resource cache proxy: off, the client reads the
 * game's own resource server (what its shipped haven-config.properties names); on, it is started with
 * <code>-U</code> and the proxy's URL. Options opens the {@link OptionsDialog}. Everything is remembered in
 * <code>launcher.properties</code>. Run with <code>--check</code> it resolves the channel's newest release and
 * prints what it would download and how it would start the client, and exits without touching anything or
 * opening a window.
 *
 * <p>Before the client, the launcher looks at its own releases and, when a newer one is out on the channel,
 * starts the {@link Updater} and exits: the updater downloads the release, puts it in place and starts the
 * launcher again — with <code>--no-launcher-update</code> when it could not, so that run does not try again.
 * Only a launcher as shipped does this, <code>launcher.jar</code> on the runtime beside it; a development run
 * has nothing to replace.
 */
public final class Launcher {
    private final Path home;
    private final Settings settings;
    private final ClientInstall client;
    private final Path javaw;
    private final Ui ui;
    /** Whether the launcher runs as shipped, and so may replace itself; false in a development run. */
    private final boolean shipped;
    /** <code>--no-launcher-update</code>: the updater that started this run failed, and once is enough. */
    private final boolean noLauncherUpdate;

    private Launcher(Path home, Settings settings, ClientInstall client, Path javaw, Ui ui, boolean shipped, boolean noLauncherUpdate) {
        this.home = home;
        this.settings = settings;
        this.client = client;
        this.javaw = javaw;
        this.ui = ui;
        this.shipped = shipped;
        this.noLauncherUpdate = noLauncherUpdate;
    }

    public static void main(String[] args) {
        List<String> a = Arrays.asList(args);
        boolean check = a.contains("--check");
        Path home = home();
        Settings settings = Settings.load(home.resolve("launcher.properties"), !check);
        ClientInstall client = new ClientInstall(home.resolve("client"));
        Path javaw = javaw(home);
        boolean shipped = shipped(home);
        if(check) {
            check(home, settings, client, javaw, shipped);
            return;
        }
        Launcher[] l = new Launcher[1];
        Ui ui = Ui.open(title(null), settings, c -> l[0].channel(c), () -> l[0].options());
        l[0] = new Launcher(home, settings, client, javaw, ui, shipped, a.contains("--no-launcher-update"));
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

    /** The Options button, on the event thread: modal, so nothing else happens while it is open. The preview
     *  starts with the executable Play would use, java.exe while the console checkbox is on. */
    private void options() {
        OptionsDialog.show(ui.frame(), settings, exe(javaw, settings));
    }

    /** Bring the launcher, then the client, up to date, and offer what there is. The title names the client
     *  only while Play offers it: what sits in <code>client/</code> without a channel behind it is not the
     *  launcher's to announce. */
    private void prepare() {
        ui.busy();
        Outcome o;
        try {
            updateSelf();
            o = update();
        } catch(Exception e) {
            ui.status("Unexpected: " + e);
            o = client.isInstalled() ? Outcome.INSTALLED_ANYWAY : Outcome.NOTHING;
        }
        ui.title(title((o == Outcome.READY || o == Outcome.INSTALLED_ANYWAY) ? client.installedVersion() : null));
        switch(o) {
            case READY, INSTALLED_ANYWAY -> ui.ready("Play", this::play);
            case NO_RELEASE -> ui.idle();
            case NOTHING -> ui.ready("Retry", this::prepare);
        }
    }

    /** Bring this launcher up to date with its own releases on the channel: when a newer one is out, start the
     *  updater and exit — it does the rest and starts the launcher again. Back when there is nothing to do, or
     *  the updater could not be started, which the status line says; GitHub out of reach is left to the client's
     *  check, which follows and says so. */
    private void updateSelf() {
        if(!shipped)
            return;
        Updater.tidy(home);
        if(noLauncherUpdate || !settings.checkUpdates())
            return;
        String tag;
        try {
            ui.status("Looking for a newer launcher...");
            tag = newerLauncher(settings);
        } catch(Exception e) {
            return;
        }
        if(tag == null)
            return;
        String v = GitHubRelease.version(tag);
        try {
            ui.status("Launcher " + v + " is out: updating...");
            Updater.launch(home, v, GitHubRelease.assetUrl(settings.launcherRepo(), tag, Updater.asset(tag)));
        } catch(Exception e) {
            ui.status("Launcher " + v + " could not be installed: " + e.getMessage());
            Updater.tidy(home);
            return;
        }
        ui.close();
        System.exit(0);
    }

    /** The tag of the newest launcher release on the channel when it is newer than this launcher, else null:
     *  this launcher is the newest, or newer (a beta on the Release channel), or the channel has nothing. */
    private static String newerLauncher(Settings settings) throws IOException, InterruptedException {
        String tag;
        try {
            tag = GitHubRelease.newestTag(settings.launcherRepo(), settings.channel());
        } catch(GitHubRelease.NoReleaseException e) {
            return null;
        }
        return (GitHubRelease.compare(tag, version()) > 0) ? tag : null;
    }

    /** Fetch the channel's newest release when it differs from the installed one, saying in the status line
     *  what happened. */
    private Outcome update() {
        client.tidy();
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
            // Only what GitHub answered: the Beta channel is everything, so nothing there is nothing at all; the
            // Release channel may have skipped a beta, named when the API showed it. Nothing about what is
            // installed, which no channel is known to have.
            ui.status((channel == Channel.BETA) ? "Nothing has been published yet."
                      : (e.beta == null) ? "No release has been published yet."
                      : "No release has been published yet — " + e.beta + " is on the Beta channel.");
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
            String url = GitHubRelease.assetUrl(settings.repo(), latest, settings.assetPrefix() + GitHubRelease.version(latest) + ".zip");
            ui.status((installed == null) ? "Downloading client " + latest + " (" + kind + ")..." : "Installing " + latest + " (" + kind + ") over " + installed + "...");
            Files.createDirectories(client.dir());
            Path zip = client.download();
            GitHubRelease.download(url, zip, ui::progress);
            ui.status("Installing client " + latest + "...");
            client.install(zip, latest);
            Files.deleteIfExists(zip);
            ui.status("Client " + latest + " is ready.");
            return Outcome.READY;
        } catch(Exception e) {
            ui.status((installed == null) ? "The download failed: " + e.getMessage()
                                          : "The update failed (" + e.getMessage() + ") — client " + installed + " is installed.");
            return (installed == null) ? Outcome.NOTHING : Outcome.INSTALLED_ANYWAY;
        }
    }

    /** The Play button: start the client and leave, unless it dies at once, in which case say so and stay. With
     *  the console on, a client that dies at once has its window kept open by the pause, so the process here
     *  lives on and the launcher leaves as usual: the error is on that screen. */
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

    /** The client as a process: on javaw.exe with its output in <code>client.log</code>, or, with the console
     *  checkbox on, in a command window of its own — the log then says so, and takes what cmd itself may have
     *  to say (nothing, unless the window could not be opened). */
    private Process start() throws IOException {
        Path log = home.resolve("client.log");
        ProcessBuilder pb = new ProcessBuilder().directory(client.dir().toFile()).redirectErrorStream(true);
        if(settings.console()) {
            Files.writeString(log, "The client was started with a console window: what it printed is there." + System.lineSeparator());
            pb.command(consoleCommand(javaw, settings, client.dir())).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        } else {
            pb.command(command(javaw, settings)).redirectOutput(log.toFile());
        }
        return pb.start();
    }

    /** The client's command line as the settings stand, on the executable Play would use. */
    static List<String> command(Path javaw, Settings s) {
        return command(exe(javaw, s), s.launch());
    }

    /**
     * The client's command line in a command window of its own, for the console checkbox. cmd's
     * <code>start</code> opens the window (titled, waited for, in <code>dir</code>) and runs a second cmd in it,
     * which runs the client on <code>java.exe</code> — the launcher that writes to a console, where javaw.exe
     * has none — and pauses when it ends in an error, so what went wrong stays on screen. The outer cmd waits
     * for the window, so the process returned stands for the client's just as the silent one does.
     *
     * <p>Two things keep cmd from misreading the line. The client's <code>||</code> is written <code>^|^|</code>
     * so the outer cmd passes it on rather than acting on it. And java.exe is named by a path relative to
     * <code>dir</code> when it can be (<code>..\runtime\bin\java.exe</code>, always in the shipped folder), so
     * that nothing after <code>cmd /c</code> needs quoting: a quoted path with a space and a parenthesis, as in
     * <code>Brodgar (2)</code>, would lose its quotes to cmd's quote-stripping rule. Only a Java elsewhere
     * (development, a JDK on another drive) is written whole, quoted when it has a space.
     */
    static List<String> consoleCommand(Path javaw, Settings s, Path dir) {
        Path java = consoleJava(javaw);
        String exe;
        try {
            exe = dir.toAbsolutePath().relativize(java.toAbsolutePath()).toString();
        } catch(IllegalArgumentException e) {
            exe = java.toString();                                  // another drive: no relative path to it
        }
        if(exe.indexOf(' ') >= 0)
            exe = "\"" + exe + "\"";
        List<String> client = command(java, s.launch());
        List<String> cmd = new ArrayList<>(List.of("cmd.exe", "/c", "start", "\"Brodgar.io client\"", "/wait", "/D", "\"" + dir + "\"", "cmd.exe", "/c", exe));
        cmd.addAll(client.subList(1, client.size()));           // the same command, java.exe named as above
        cmd.addAll(List.of("^|^|", "pause"));
        return cmd;
    }

    /** The executable the settings start the client with: java.exe while the console checkbox is on, else javaw. */
    static Path exe(Path javaw, Settings s) {
        return s.console() ? consoleJava(javaw) : javaw;
    }

    /** The console launcher beside a javaw: <code>java.exe</code> in the same <code>bin</code>, or javaw itself
     *  when there is none. */
    static Path consoleJava(Path javaw) {
        Path java = javaw.resolveSibling("java.exe");
        return Files.exists(java) ? java : javaw;
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

    private static void check(Path home, Settings settings, ClientInstall client, Path javaw, boolean shipped) {
        System.out.println("home:      " + home);
        System.out.println("runtime:   " + javaw + (Files.exists(javaw) ? "" : "  (MISSING)"));
        System.out.println("launcher:  " + version() + (shipped ? " (as shipped: kept at the channel's newest release)" : " (a development run: not updated)"));
        if(shipped) {
            try {
                String tag = newerLauncher(settings);
                System.out.println("newer:     " + ((tag == null) ? "none" : tag + "  " + GitHubRelease.assetUrl(settings.launcherRepo(), tag, Updater.asset(tag))));
            } catch(Exception e) {
                System.out.println("newer:     unreachable: " + e);
            }
        }
        System.out.println("installed: " + client.installedVersion());
        System.out.println("channel:   " + settings.channel().key);
        try {
            String latest = GitHubRelease.newestTag(settings.repo(), settings.channel());
            System.out.println("newest:    " + latest);
            System.out.println("asset:     " + GitHubRelease.assetUrl(settings.repo(), latest, settings.assetPrefix() + GitHubRelease.version(latest) + ".zip"));
        } catch(GitHubRelease.NoReleaseException e) {
            System.out.println("newest:    none on this channel (" + e.getMessage() + ")");
        } catch(Exception e) {
            System.out.println("newest:    unreachable: " + e);
        }
        System.out.println("proxy:     " + (settings.resourceProxy() ? "on, " + settings.resourceProxyUrl() : "off (the game's own resource server)"));
        System.out.println("console:   " + (settings.console() ? "on (a command window, kept open when the client fails)" : "off (what the client prints goes to client.log)"));
        System.out.println("command:   " + String.join(" ", command(javaw, settings)));
        if(settings.console())
            System.out.println("window:    " + String.join(" ", consoleCommand(javaw, settings, client.dir())));
        System.out.println("cwd:       " + client.dir());
    }

    /** The folder the launcher lives in: <code>launcher.home</code> when set (the development run names this
     *  folder), else the folder <code>launcher.jar</code> is in, else the working directory. */
    static Path home() {
        String set = System.getProperty("launcher.home");
        if(set != null)
            return Paths.get(set).toAbsolutePath();
        Path jar = jar();
        return (jar != null) ? jar.getParent() : Paths.get("").toAbsolutePath();
    }

    /** The jar this launcher runs from, or null: a classes/ folder, as a development run has it. */
    static Path jar() {
        try {
            CodeSource src = Launcher.class.getProtectionDomain().getCodeSource();
            if(src != null) {
                Path self = Paths.get(src.getLocation().toURI());
                if(Files.isRegularFile(self))
                    return self.toAbsolutePath();
            }
        } catch(URISyntaxException | RuntimeException e) {
            // no jar to speak of
        }
        return null;
    }

    /** The launcher's version, from the jar's manifest — <code>1.0.1</code> — or null off a classes/ folder. */
    static String version() {
        return Launcher.class.getPackage().getImplementationVersion();
    }

    /** Whether the launcher runs as shipped: <code>launcher.jar</code> in <code>home</code>, with a version in its
     *  manifest, on the runtime beside it. A development run — a classes/ folder, or <code>build/launcher.jar</code>
     *  with <code>launcher.home</code> naming the source folder — is not, and is not replaced by a release. */
    static boolean shipped(Path home) {
        try {
            Path jar = jar();
            return (jar != null) && (version() != null) && Files.isSameFile(jar, home.resolve("launcher.jar"))
                && Files.isDirectory(home.resolve("runtime"));
        } catch(IOException e) {
            return false;                           // no launcher.jar in home: not the shipped folder
        }
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

    /** The window title: the launcher's version from the jar's manifest, and the client's when one is offered
     *  (<code>null</code> while none is). */
    private static String title(String offered) {
        String v = version();
        return "Brodgar.io" + ((v == null) ? "" : " launcher " + v) + ((offered == null) ? "" : " · client " + offered);
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
