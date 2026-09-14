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
 * The Steam Workshop entry point. Haven &amp; Hearth's own Steam launcher starts a subscribed client the way its
 * <code>workshop-client.properties</code> says; ours names this class, so that launcher loads
 * <code>launcher.jar</code> into <em>its</em> JVM and calls <code>main</code> here. This <code>main</code> starts
 * the real launcher as a process of its own — on the same Java (Steam's own runtime, which then runs the client
 * too) and with the same environment (<code>SteamAppId</code> and the rest of what Steam sets on a game it
 * starts, which is what lets the client log in with Steam) — and returns, so the Steam launcher saves the
 * player's choice and disposes its chooser. Then it ends that JVM: the Steam launcher's does not end by itself
 * once <code>main</code> returns — AWT's auto-shutdown never fires in it — and while it lives, with its Steam
 * API session open, Steam shows the game running and its Stop button waits on it forever. The exit lands a
 * moment after <code>main</code> returns, so the launcher has had its turn first.
 *
 * <p>The launcher's folder is not the Workshop item's. Steam rewrites that folder on every update of the item,
 * and <code>client/</code>, with the player's <code>savedata/</code> in it, has to outlive that: it is
 * <code>%LOCALAPPDATA%\Brodgar.io</code>, named with <code>launcher.home</code>, where the launcher keeps
 * <code>client/</code>, <code>launcher.properties</code> and the logs as it would beside <code>run.bat</code>.
 * There is no <code>runtime/</code> there, so the launcher is not "as shipped": it never replaces itself — the
 * Workshop item is how it is updated — and starts the client on the Java that runs it, Steam's.
 *
 * <p>Whatever arguments this <code>main</code> gets go through to the launcher's — none from Steam;
 * <code>--check</code> from a hand test, whose output then lands in <code>launcher.log</code>.
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
            JOptionPane.showMessageDialog(null, "The Brodgar.io launcher could not be started: " + e, "Brodgar.io", JOptionPane.ERROR_MESSAGE);
        }
        Thread exit = new Thread(() -> {
            try {
                Thread.sleep(2000);
            } catch(InterruptedException e) {
                // then now
            }
            System.exit(0);
        }, "workshop-exit");
        exit.setDaemon(true);
        exit.start();
    }

    /** The launcher's folder for a Steam start: <code>%LOCALAPPDATA%\Brodgar.io</code>, or
     *  <code>~/.brodgar.io</code> where there is no such variable. */
    static Path home() {
        String local = System.getenv("LOCALAPPDATA");
        return (local != null) ? Paths.get(local, "Brodgar.io") : Paths.get(System.getProperty("user.home"), ".brodgar.io");
    }

    /** The Java to start the launcher on: the windowless one beside the executable running this JVM — Steam's
     *  own runtime — else that executable, else what the launcher itself would pick. */
    static Path java(Path home) {
        Path exe = ProcessHandle.current().info().command().map(Paths::get).orElse(null);
        if(exe == null)
            return Launcher.javaw(home);
        Path javaw = exe.resolveSibling("javaw.exe");
        return Files.exists(javaw) ? javaw : exe;
    }
}
