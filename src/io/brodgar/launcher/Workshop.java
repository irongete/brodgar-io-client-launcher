package io.brodgar.launcher;

import javax.swing.JOptionPane;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Steam Workshop entry point, named by <code>workshop/workshop-client.properties</code>. Haven &amp; Hearth's
 * Steam launcher loads <code>launcher.jar</code> into its own JVM and calls <code>main</code>, which starts
 * {@link Launcher} as a child process — same Java (Steam's runtime; it runs the client too), same environment
 * (<code>SteamAppId</code> etc., needed for the client's Steam login), <code>-Dlauncher.home=%LOCALAPPDATA%\Brodgar.io</code>,
 * stdout/stderr to <code>launcher.log</code> there — and returns. 2 s later it calls <code>System.exit(0)</code>:
 * the host JVM has no AWT auto-shutdown and keeps its Steam API session open, so Steam would otherwise show the
 * game as running indefinitely.
 *
 * <p>Home is outside the item folder because Steam rewrites that folder on every item update. No
 * <code>runtime/</code> under home: <code>Launcher.shipped()</code> is false, so no self-update; the item
 * upload is the update. Arguments are passed through (<code>--check</code> for a manual test).
 */
public final class Workshop {
    public static void main(String[] args) {
        try {
            Path jar = Launcher.jar();
            if(jar == null)
                throw new IOException("not running from launcher.jar");
            Path home = home();
            Files.createDirectories(home);
            List<String> cmd = new ArrayList<>();
            cmd.add(java(home).toString());
            cmd.add("-Dlauncher.home=" + home);
            cmd.add("-jar");
            cmd.add(jar.toString());
            cmd.addAll(Arrays.asList(args));
            new ProcessBuilder(cmd).directory(home.toFile()).redirectErrorStream(true)
                .redirectOutput(home.resolve("launcher.log").toFile()).start();
        } catch(IOException | RuntimeException e) {
            JOptionPane.showMessageDialog(null, "The brodgar.io launcher could not be started: " + e, "brodgar.io", JOptionPane.ERROR_MESSAGE);
        }
        Thread exit = new Thread(() -> {
            try {
                Thread.sleep(2000);
            } catch(InterruptedException e) {
                // exit now
            }
            System.exit(0);
        }, "workshop-exit");
        exit.setDaemon(true);
        exit.start();
    }

    /** <code>%LOCALAPPDATA%\Brodgar.io</code>, or <code>~/.brodgar.io</code> without that variable. */
    static Path home() {
        String local = System.getenv("LOCALAPPDATA");
        return (local != null) ? Paths.get(local, "Brodgar.io") : Paths.get(System.getProperty("user.home"), ".brodgar.io");
    }

    /** <code>javaw.exe</code> beside the current JVM's executable; else that executable; else
     *  {@link Launcher#javaw}. */
    static Path java(Path home) {
        Path exe = ProcessHandle.current().info().command().map(Paths::get).orElse(null);
        if(exe == null)
            return Launcher.javaw(home);
        Path javaw = exe.resolveSibling("javaw.exe");
        return Files.exists(javaw) ? javaw : exe;
    }
}
