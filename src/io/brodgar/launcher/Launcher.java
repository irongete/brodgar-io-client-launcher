package io.brodgar.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The Brodgar.io client launcher: keeps <code>client/</code> at the latest GitHub release of the client and
 * starts it, on the Play button, on the runtime that ships beside this launcher.
 *
 * <p>Everything lives in one folder, the one the launcher executable is in:
 * <pre>
 *   Brodgar.exe            the launcher (jpackage)
 *   runtime/               the Java runtime (jlink), the launcher's and the client's
 *   app/launcher.jar
 *   client/                the client release: hafen.jar, lib/, the resource jars, addons/
 *   client/savedata/       the player's own; never written by the launcher
 *   client/installed-version
 *   launcher.properties    settings, written with defaults on the first run
 *   client.log             what the client printed on its last run
 * </pre>
 *
 * <p>The window opens at once; the release check and the download run behind it, and the button becomes
 * <b>Play</b> when the client is ready — or <b>Retry</b> when nothing is installed and the download failed.
 * Run with <code>--check</code> it resolves the latest release and prints what it would download and how it
 * would start the client, and exits without touching anything or opening a window.
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
        Ui ui = Ui.open(title(client.installedVersion()));
        Launcher l = new Launcher(home, settings, client, java, ui);
        new Thread(l::prepare, "launcher-update").start();
    }

    /** Bring the client up to date, then offer Play — or Retry, when there is nothing to play. */
    private void prepare() {
        ui.busy();
        try {
            update();
        } catch(Exception e) {
            ui.status("Unexpected: " + e);
        }
        if(client.isInstalled())
            ui.ready("Play", this::play);
        else
            ui.ready("Retry", this::prepare);
    }

    /** Fetch the latest release when it differs from the installed one; GitHub being out of reach is said in
     *  the status line and the installed client, if any, is still offered. */
    private void update() {
        String installed = client.installedVersion();
        if(!settings.checkUpdates()) {
            ui.status((installed == null) ? "Update check is off (launcher.properties) and no client is installed."
                                          : "Client " + installed + " — update check is off (launcher.properties).");
            return;
        }
        String latest;
        try {
            ui.status("Looking for the latest release...");
            latest = GitHubRelease.latestTag(settings.repo());
        } catch(Exception e) {
            ui.status((installed == null) ? "GitHub is unreachable: " + e.getMessage()
                                          : "GitHub is unreachable — client " + installed + " is installed.");
            return;
        }
        if(latest.equals(installed)) {
            ui.status("Client " + installed + " is up to date.");
            return;
        }
        try {
            String url = GitHubRelease.assetUrl(settings.repo(), latest, settings.assetPrefix());
            ui.status((installed == null) ? "Downloading client " + latest + "..." : "Updating " + installed + " → " + latest + "...");
            Files.createDirectories(client.dir());
            Path zip = client.dir().resolve("download.tmp");
            GitHubRelease.download(url, zip, ui::progress);
            ui.status("Installing client " + latest + "...");
            client.install(zip, latest);
            Files.deleteIfExists(zip);
            ui.title(title(latest));
            ui.status("Client " + latest + " is ready.");
        } catch(Exception e) {
            ui.status((installed == null) ? "The download failed: " + e.getMessage()
                                          : "The update failed (" + e.getMessage() + ") — client " + installed + " is installed.");
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

    /** The client's command line: the flags <code>ant run</code> and <code>run.bat</code> pass, plus the ones a
     *  runtime past 23 wants, then whatever <code>java.opts</code> adds. */
    static List<String> command(Path java, Settings settings) {
        int feature = Runtime.version().feature();   // the same image runs the launcher and the client
        List<String> cmd = new ArrayList<>();
        cmd.add(java.toString());
        cmd.add("-Xms" + settings.heap());
        cmd.add("-Xmx" + settings.heap());
        cmd.add("-XX:+AlwaysPreTouch");
        cmd.add("-XX:+UseZGC");
        if(feature < 24)
            cmd.add("-XX:+ZGenerational");                   // the default from 23, an obsolete flag from 24
        if(feature >= 23)
            cmd.add("--sun-misc-unsafe-memory-access=allow");  // JOGL and LWJGL still use it; 24+ warns without this
        cmd.add("--add-exports=java.base/java.lang=ALL-UNNAMED");
        cmd.add("--add-exports=java.desktop/sun.awt=ALL-UNNAMED");
        cmd.add("--add-exports=java.desktop/sun.java2d=ALL-UNNAMED");
        cmd.add("--enable-native-access=ALL-UNNAMED");
        cmd.add("-Dsun.java2d.uiScale.enabled=false");
        cmd.add("-Djava.net.preferIPv6Addresses=system");
        cmd.addAll(settings.javaOpts());
        cmd.add("-jar");
        cmd.add("hafen.jar");
        cmd.add("-U");
        cmd.add(settings.resUrl());
        return cmd;
    }

    private static void check(Path home, Settings settings, ClientInstall client, Path java) {
        System.out.println("home:      " + home);
        System.out.println("runtime:   " + java + (Files.exists(java) ? "" : "  (MISSING)"));
        System.out.println("installed: " + client.installedVersion());
        try {
            String latest = GitHubRelease.latestTag(settings.repo());
            System.out.println("latest:    " + latest);
            System.out.println("asset:     " + GitHubRelease.assetUrl(settings.repo(), latest, settings.assetPrefix()));
        } catch(Exception e) {
            System.out.println("latest:    unreachable: " + e);
        }
        System.out.println("command:   " + String.join(" ", command(java, settings)));
        System.out.println("cwd:       " + client.dir());
    }

    /** The folder the launcher lives in: the executable's, under jpackage; the working directory when run as a
     *  bare jar during development. */
    static Path home() {
        String exe = System.getProperty("jpackage.app-path");
        if(exe != null)
            return Paths.get(exe).toAbsolutePath().getParent();
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

    private static String title(String installed) {
        String v = System.getProperty("launcher.version");
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
