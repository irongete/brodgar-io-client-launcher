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
 * Main class. Keeps <code>client/</code> at the channel's newest GitHub release and starts it on
 * <code>runtime/</code>. Home is the folder of <code>launcher.jar</code>, or <code>-Dlauncher.home</code>:
 * <pre>
 *   run.bat                starts launcher.jar on runtime/
 *   launcher.jar
 *   media/                 the trailer and its poster, beside the jar ({@link Trailer})
 *   runtime/               jlink runtime; runs the launcher and the client
 *   client/                the client release: hafen.jar, lib/, resource jars, addons/, haven-config.properties
 *   client/savedata/       client data; never written by the launcher
 *   client/installed-version
 *   cache/release/         the newest client zip downloaded on each channel: switching back needs no download
 *   cache/beta/
 *   launcher.properties    {@link Settings}; created with defaults on first run
 *   client.log             stdout/stderr of the last client run
 * </pre>
 *
 * <p>Start: window ({@link Ui}), then on a thread: {@link #updateSelf} (shipped, released launchers only), {@link #update}
 * (release check, download, unpack), {@link Ui#ready} with Play or Retry, or {@link Ui#idle}. Play:
 * {@link ClientInstall#configure} writes the client's <code>haven-config.properties</code> from the settings
 * (<code>haven.resurl</code>, <code>haven.addondir</code>; also written on the proxy checkbox and when Options
 * closes — an unpacked release restores the zip's copy), then {@link #command} runs with cwd <code>client/</code>
 * and the launcher stays, Play disabled until the client exits. <code>--check</code>: resolve and print, no
 * window, no writes.
 * <code>--no-launcher-update</code>: skip {@link #updateSelf}.
 */
public final class Launcher {
    private final Path home;
    private final Settings settings;
    private final ClientInstall client;
    private final Path javaw;
    private final Ui ui;
    /** {@link #shipped(Path)}: self-update allowed. */
    private final boolean shipped;
    /** <code>--no-launcher-update</code> was given. */
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
        ClientInstall client = new ClientInstall(home.resolve("client"), home.resolve("cache"));
        Path javaw = javaw(home);
        boolean shipped = shipped(home);
        if(check) {
            check(home, settings, client, javaw, shipped);
            return;
        }
        Launcher[] l = new Launcher[1];
        Path jar = jar();
        Ui ui = Ui.open(TITLE, settings, ((jar != null) ? jar.getParent() : home).resolve(Trailer.DIR), c -> l[0].channel(c), on -> l[0].proxy(on), () -> l[0].options(), () -> l[0].clientFolder());
        l[0] = new Launcher(home, settings, client, javaw, ui, shipped, a.contains("--no-launcher-update"));
        new Thread(l[0]::prepare, "launcher-update").start();
    }

    /** Result of {@link #update}. */
    private enum Outcome {
        /** Newest release installed: Play. */
        READY,
        /** GitHub answered, channel empty: button disabled, whatever is installed (it is another channel's). */
        NO_RELEASE,
        /** GitHub or download failed, a client is installed: Play. */
        INSTALLED_ANYWAY,
        /** GitHub or download failed, nothing installed: Retry. */
        NOTHING,
    }

    /** Dropdown callback: save, re-run {@link #prepare}. Disabled while work runs. */
    private void channel(Channel c) {
        settings.channel(c);
        new Thread(this::prepare, "launcher-update").start();
    }

    /** Proxy checkbox callback: save, write the client's file. */
    private void proxy(boolean on) {
        settings.resourceProxy(on);
        configure();
    }

    /** Options button callback: the modal Swing dialog beside the window, then write the client's file. */
    private void options() {
        ui.swingDialog(() -> OptionsDialog.show(null, settings, exe(javaw, settings)), this::configure);
    }

    /** {@link ClientInstall#configure} from the settings; an <code>IOException</code> goes to the status line. */
    private void configure() {
        try {
            client.configure(settings.clientConfig());
        } catch(IOException e) {
            ui.status(client.config().getFileName() + " could not be written: " + e.getMessage());
        }
    }

    /** Open client folder callback: <code>Desktop.open(client/)</code>; error dialog if absent. */
    private void clientFolder() {
        Path dir = client.dir();
        try {
            if(!Files.isDirectory(dir))
                throw new IOException("no client is installed yet, so " + dir + " does not exist");
            java.awt.Desktop.getDesktop().open(dir.toFile());
        } catch(IOException | RuntimeException e) {
            ui.error("The client folder could not be opened: " + e.getMessage());
        }
    }

    /** {@link #updateSelf}, {@link #update}, then the button state per {@link Outcome}. */
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
        switch(o) {
            case READY, INSTALLED_ANYWAY -> ui.ready("Play", this::play);
            case NO_RELEASE -> ui.idle();
            case NOTHING -> ui.ready("Retry", this::prepare);
        }
    }

    /** Shipped, {@link #released} launchers with <code>check.updates</code> and no <code>--no-launcher-update</code>:
     *  if {@link #newerLauncher} finds a tag, {@link Updater#launch} and <code>System.exit(0)</code>. A failed
     *  launch is reported in the status line; a failed lookup is left to {@link #update} to report. */
    private void updateSelf() {
        if(!shipped)
            return;
        Updater.tidy(home);
        if(!released() || noLauncherUpdate || !settings.checkUpdates())
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
            ui.status("Launcher v" + v + " is out: updating...");
            Updater.launch(home, v, GitHubRelease.assetUrl(settings.launcherRepo(), tag, Updater.ASSET));
        } catch(Exception e) {
            ui.status("Launcher v" + v + " could not be installed: " + e.getMessage());
            Updater.tidy(home);
            return;
        }
        ui.close();
        System.exit(0);
    }

    /** Newest launcher tag if {@link GitHubRelease#compare} puts it above {@link #version()}. The launcher has one
     *  line of releases, so its channel setting (the client's) plays no part;
     *  else null. */
    private static String newerLauncher(Settings settings) throws IOException, InterruptedException {
        String tag;
        try {
            tag = GitHubRelease.newestTag(settings.launcherRepo(), Channel.RELEASE);
        } catch(GitHubRelease.NoReleaseException e) {
            return null;
        }
        return (GitHubRelease.compare(tag, version()) > 0) ? tag : null;
    }

    /** Release check and install; every branch sets the status line. */
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
            ui.status("No " + kind + " has been published yet.");
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
        String asset = settings.assetPrefix() + latest + ".zip";
        Path zip = client.cached(asset);
        try {
            if(zip == null) {
                String url = GitHubRelease.assetUrl(settings.repo(), latest, asset);
                ui.status("Downloading client " + latest + " (" + kind + ")...");
                zip = client.cache(channel, asset);
                GitHubRelease.download(url, zip, ui::progress);
                client.prune(zip);
            }
            ui.status("Installing client " + latest + "...");
            Files.createDirectories(client.dir());
            client.install(zip, latest);
            ui.status("Client " + latest + " is ready.");
            return Outcome.READY;
        } catch(Exception e) {
            ui.status((installed == null) ? "The download failed: " + e.getMessage()
                                          : "The update failed (" + e.getMessage() + ") — client " + installed + " is installed.");
            if(zip != null) {
                try {
                    Files.deleteIfExists(zip);           // a zip that would not install is not kept for next time
                } catch(IOException io) {
                    // locked: the next attempt tries it again
                }
            }
            return (installed == null) ? Outcome.NOTHING : Outcome.INSTALLED_ANYWAY;
        }
    }

    /** Play: configure the client's file, pause the trailer, {@link #start}, and stay: the button and the
     *  dropdown disabled while the client runs (its files are in use), offered again when it exits — unless the
     *  process ends within 3 s: error dialog with the exit code and the log tail. With the console on, the
     *  <code>pause</code> keeps the process alive, so the error is shown there instead. */
    private void play() {
        ui.busy();
        ui.status("Starting the client...");
        Process p;
        try {
            client.configure(settings.clientConfig());
            ui.pauseTrailer();
            p = start();
            if(p.waitFor(3, TimeUnit.SECONDS)) {
                ui.error("The client exited at once (code " + p.exitValue() + ").\n\n" + tail(home.resolve("client.log"), 12));
                ui.status("The client did not start.");
                ui.ready("Play", this::play);
                return;
            }
            ui.status("The client is running.");
            p.waitFor();
        } catch(Exception e) {
            ui.error(e.toString());
            ui.status("The client did not start.");
            ui.ready("Play", this::play);
            return;
        }
        ui.status((p.exitValue() == 0) ? "The client has exited." : "The client exited with code " + p.exitValue() + ".");
        ui.ready("Play", this::play);
    }

    /** Start the client in <code>client/</code>: {@link #command} with stdout/stderr to <code>client.log</code>,
     *  or with the console on {@link #consoleCommand}, the log then holding a note and cmd's own output. */
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

    /** {@link #command(Path, Settings.Launch)} on {@link #exe}. */
    static List<String> command(Path javaw, Settings s) {
        return command(exe(javaw, s), s.launch());
    }

    /**
     * <code>cmd /c start "brodgar.io client" /wait /D dir cmd /c java.exe ...args ^|^| pause</code>: the client
     * on <code>java.exe</code> in its own console window; the outer cmd waits, so the returned process ends
     * with the client. <code>^|^|</code> keeps the outer cmd from evaluating <code>||</code>. java.exe is
     * given relative to <code>dir</code> (<code>..\runtime\bin\java.exe</code>) so no quoting is needed after
     * <code>cmd /c</code> — cmd's quote-stripping rule breaks a quoted path with a space and a parenthesis;
     * only a Java on another drive is absolute, quoted if it has a space.
     */
    static List<String> consoleCommand(Path javaw, Settings s, Path dir) {
        Path java = consoleJava(javaw);
        String exe;
        try {
            exe = dir.toAbsolutePath().relativize(java.toAbsolutePath()).toString();
        } catch(IllegalArgumentException e) {
            exe = java.toString();                                  // another drive: no relative path
        }
        if(exe.indexOf(' ') >= 0)
            exe = "\"" + exe + "\"";
        List<String> client = command(java, s.launch());
        List<String> cmd = new ArrayList<>(List.of("cmd.exe", "/c", "start", "\"brodgar.io client\"", "/wait", "/D", "\"" + dir + "\"", "cmd.exe", "/c", exe));
        cmd.addAll(client.subList(1, client.size()));           // the arguments of command(), java.exe as above
        cmd.addAll(List.of("^|^|", "pause"));
        return cmd;
    }

    /** <code>java.exe</code> with the console on, else <code>javaw</code>. */
    static Path exe(Path javaw, Settings s) {
        return s.console() ? consoleJava(javaw) : javaw;
    }

    /** <code>java.exe</code> beside <code>javaw</code>, or <code>javaw</code> itself if absent. */
    static Path consoleJava(Path javaw) {
        Path java = javaw.resolveSibling("java.exe");
        return Files.exists(java) ? java : javaw;
    }

    /**
     * The client command line: <code>java -Xms -Xmx [-XX:+AlwaysPreTouch] [-XX:+UseZGC [-XX:+ZGenerational]]
     * [--sun-misc-unsafe-memory-access=allow] --add-exports ×3 --enable-native-access
     * [-Dsun.java2d.uiScale.enabled=false] -Djava.net.preferIPv6Addresses= java.opts... -jar hafen.jar</code>.
     * Client configuration is not here: see {@link ClientInstall#configure}.
     */
    static List<String> command(Path java, Settings.Launch l) {
        int feature = Runtime.version().feature();   // the launcher and the client run on the same runtime
        List<String> cmd = new ArrayList<>();
        cmd.add(java.toString());
        cmd.add("-Xms" + l.heap());
        cmd.add("-Xmx" + l.heap());
        if(l.pretouch())
            cmd.add("-XX:+AlwaysPreTouch");
        if(l.gc().equals("zgc")) {
            cmd.add("-XX:+UseZGC");
            if(feature < 24)
                cmd.add("-XX:+ZGenerational");               // default since 23, obsolete (warns) from 24
        }
        if(feature >= 23)
            cmd.add("--sun-misc-unsafe-memory-access=allow");  // JOGL and LWJGL use Unsafe; 24+ warns otherwise
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
        return cmd;
    }

    private static void check(Path home, Settings settings, ClientInstall client, Path javaw, boolean shipped) {
        System.out.println("home:      " + home);
        System.out.println("runtime:   " + javaw + (Files.exists(javaw) ? "" : "  (MISSING)"));
        System.out.println("launcher:  " + (released() ? "v" : "") + version() + (!shipped ? " (a development run: not updated)"
                                                       : !released() ? " (a development build: never updated)"
                                                       : " (as shipped: kept at the channel's newest release)"));
        if(shipped && released()) {
            try {
                String tag = newerLauncher(settings);
                System.out.println("newer:     " + ((tag == null) ? "none" : tag + "  " + GitHubRelease.assetUrl(settings.launcherRepo(), tag, Updater.ASSET)));
            } catch(Exception e) {
                System.out.println("newer:     unreachable: " + e);
            }
        }
        System.out.println("installed: " + client.installedVersion());
        System.out.println("channel:   " + settings.channel().key);
        try {
            String latest = GitHubRelease.newestTag(settings.repo(), settings.channel());
            System.out.println("newest:    " + latest);
            System.out.println("asset:     " + GitHubRelease.assetUrl(settings.repo(), latest, settings.assetPrefix() + latest + ".zip"));
        } catch(GitHubRelease.NoReleaseException e) {
            System.out.println("newest:    none on this channel (" + e.getMessage() + ")");
        } catch(Exception e) {
            System.out.println("newest:    unreachable: " + e);
        }
        System.out.println("proxy:     " + (settings.resourceProxy() ? "on, " + settings.resourceProxyUrl() : "off, " + settings.resourceUrl() + " (the game's own resource server)"));
        System.out.println("config:    " + client.config() + " is made to say: " + Settings.lines(settings.clientConfig()).replace(System.lineSeparator(), "  "));
        System.out.println("console:   " + (settings.console() ? "on (a command window, kept open when the client fails)" : "off (what the client prints goes to client.log)"));
        System.out.println("command:   " + String.join(" ", command(javaw, settings)));
        if(settings.console())
            System.out.println("window:    " + String.join(" ", consoleCommand(javaw, settings, client.dir())));
        System.out.println("cwd:       " + client.dir());
    }

    /** <code>-Dlauncher.home</code>; else the folder of {@link #jar()}; else the working directory. */
    static Path home() {
        String set = System.getProperty("launcher.home");
        if(set != null)
            return Paths.get(set).toAbsolutePath();
        Path jar = jar();
        return (jar != null) ? jar.getParent() : Paths.get("").toAbsolutePath();
    }

    /** The window title: no versions there (the launcher's is in the update messages, the client's in the
     *  status line). */
    static final String TITLE = "Brodgar.io Launcher";

    /** The window icon, in the jar beside the classes: the client's dolmen on a parchment tile (the client's own
     *  is the blue one), <code>etc/icon.png</code> out of the client's <code>tools/icon.py --style parchment</code>. */
    static final String ICON = "icon.png";

    /** {@link #ICON} for a Swing window; null if the jar has none. */
    static java.awt.Image icon() {
        try(java.io.InputStream icon = Launcher.class.getResourceAsStream(ICON)) {
            return (icon == null) ? null : javax.imageio.ImageIO.read(icon);
        } catch(IOException e) {
            return null;
        }
    }

    /** The jar this class was loaded from; null from a classes folder. */
    static Path jar() {
        try {
            CodeSource src = Launcher.class.getProtectionDomain().getCodeSource();
            if(src != null) {
                Path self = Paths.get(src.getLocation().toURI());
                if(Files.isRegularFile(self))
                    return self.toAbsolutePath();
            }
        } catch(URISyntaxException | RuntimeException e) {
            // no code source
        }
        return null;
    }

    /** <code>Implementation-Version</code> of the jar manifest; null from a classes folder. */
    static String version() {
        return Launcher.class.getPackage().getImplementationVersion();
    }

    /** True if {@link #version} is a version a release carries (<code>6</code>, <code>5.1-beta</code>): what
     *  <code>publish.ps1</code> stamps. False for <code>dev</code>, every other build's, which
     *  {@link GitHubRelease#compare} would rank below any release: a development build never updates itself,
     *  whatever folder it runs from. */
    static boolean released() {
        return GitHubRelease.isVersion(version());
    }

    /** True if running from <code>home/launcher.jar</code> with a manifest version and <code>home/runtime/</code>
     *  present: the shipped layout, which self-update needs. False for a development run off the build folder. */
    static boolean shipped(Path home) {
        try {
            Path jar = jar();
            return (jar != null) && (version() != null) && Files.isSameFile(jar, home.resolve("launcher.jar"))
                && Files.isDirectory(home.resolve("runtime"));
        } catch(IOException e) {
            return false;                           // no home/launcher.jar
        }
    }

    /** <code>home/runtime/bin/javaw.exe</code> if present; else <code>java.home/bin/javaw.exe</code>, else
     *  <code>java.home/bin/java</code>. */
    static Path javaw(Path home) {
        Path shipped = home.resolve("runtime").resolve("bin").resolve("javaw.exe");
        if(Files.exists(shipped))
            return shipped;
        Path own = Paths.get(System.getProperty("java.home"), "bin", "javaw.exe");
        return Files.exists(own) ? own : Paths.get(System.getProperty("java.home"), "bin", "java");
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
