package io.brodgar.launcher;

import java.awt.Dimension;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

/**
 * Launcher self-update, a separate process. {@link #launch} copies <code>launcher.jar</code> to
 * <code>update/updater.jar</code> (the running jar is locked) and starts this class from it; the launcher then
 * exits. {@link #main}: wait for the launcher pid, download the platform's release zip ({@link Os#asset}) into
 * <code>update/</code>, unpack, and from the launcher's files in it ({@link Os#PAYLOAD}: inside the app on
 * macOS) <code>ATOMIC_MOVE</code> <code>runtime/</code> → <code>runtime.new/</code>, the starter
 * (<code>run.bat</code>, or <code>run.sh</code> off Windows: {@link Os#STARTER}), <code>launcher.jar</code>,
 * <code>media/</code> and <code>icon.png</code> into <code>home</code>, start the starter, exit. The starter swaps
 * <code>runtime.new/</code> in and starts the new launcher on it: it runs two seconds after this process is gone,
 * since the runtime cannot be replaced while a process runs on it ({@link #start}). The launcher deletes
 * <code>update/</code> at its next start ({@link #tidy}).
 *
 * <p>On failure: error dialog, then the current launcher is started with <code>--no-launcher-update</code>.
 * <code>client/</code> is never touched.
 */
public final class Updater {
    private static final String JAR = "launcher.jar", RUNTIME = "runtime", STAGING = "update";
    /** What moves into <code>home</code> besides the runtime, each replaced when the zip has it: the starter,
     *  the jar, <code>media/</code>, the trailer's folder, and the icon of Linux and macOS. */
    private static final List<String> FILES = List.of(Os.STARTER, JAR, Trailer.DIR, Os.ICON);

    private final Path home;
    private final JFrame frame;
    private final JLabel status;
    private final JProgressBar bar;

    private Updater(Path home, String version) {
        this.home = home;
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch(Exception e) {
            // cross-platform look and feel then
        }
        frame = new JFrame("brodgar.io launcher v" + version);
        frame.setIconImage(Launcher.icon());
        status = new JLabel("Waiting for the launcher to close...");
        bar = new JProgressBar(0, 1000);
        bar.setIndeterminate(true);
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        panel.add(status);
        panel.add(Box.createVerticalStrut(8));
        panel.add(bar);
        status.setAlignmentX(0);
        bar.setAlignmentX(0);
        frame.setContentPane(panel);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setResizable(false);
        frame.setMinimumSize(new Dimension(420, 0));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /** <code>Updater &lt;home&gt; &lt;pid&gt; &lt;version&gt; &lt;url&gt;</code>. */
    public static void main(String[] args) {
        if(args.length != 4) {
            System.err.println("usage: Updater <home> <pid> <version> <url>");
            System.exit(2);
        }
        Path home = Paths.get(args[0]);
        long pid = Long.parseLong(args[1]);
        String version = args[2], url = args[3];
        Updater u = new Updater(home, version);
        try {
            u.install(pid, version, url);
            u.start(false);
        } catch(Exception e) {
            e.printStackTrace();
            u.status("Launcher v" + version + " could not be installed.");
            JOptionPane.showMessageDialog(u.frame, "Launcher v" + version + " could not be installed: " + reason(e)
                                          + "\n\nThe launcher goes on as it is, and tries again the next time it starts.",
                                          u.frame.getTitle(), JOptionPane.ERROR_MESSAGE);
            u.start(true);
        }
        System.exit(0);
    }

    /** Wait for <code>pid</code>; download and unpack into <code>update/</code>; verify <code>launcher.jar</code>
     *  and the runtime's {@link Os#javaw} are in the launcher's files there ({@link Os#PAYLOAD}); move runtime
     *  and {@link #FILES} into place. */
    private void install(long pid, String version, String url) throws IOException, InterruptedException {
        waitFor(pid);
        Path dir = home.resolve(STAGING);
        Path zip = dir.resolve("launcher.zip");
        status("Downloading the launcher...");
        GitHubRelease.download(url, zip, this::progress);
        status("Installing the launcher...");
        Unzip.unpack(zip, dir);
        Files.delete(zip);
        Path payload = dir.resolve(Os.PAYLOAD);
        if(!Files.isRegularFile(payload.resolve(JAR)) || !Files.isRegularFile(Os.javaw(payload.resolve(RUNTIME))))
            throw new IOException("the release zip does not hold a launcher");
        Path staged = home.resolve(RUNTIME + ".new");
        deleteTree(staged);
        Files.move(payload.resolve(RUNTIME), staged, StandardCopyOption.ATOMIC_MOVE);
        for(String f : FILES) {
            if(!Files.exists(payload.resolve(f)))
                continue;
            if(Files.isDirectory(home.resolve(f)))
                deleteTree(home.resolve(f));
            Files.move(payload.resolve(f), home.resolve(f), StandardCopyOption.ATOMIC_MOVE);
        }
    }

    /** Wait up to 60 s for <code>pid</code> to exit; <code>IOException</code> after that. */
    private static void waitFor(long pid) throws IOException, InterruptedException {
        try {
            ProcessHandle.of(pid).map(ProcessHandle::onExit).orElse(CompletableFuture.completedFuture(null)).get(60, TimeUnit.SECONDS);
        } catch(TimeoutException | ExecutionException e) {
            throw new IOException("the launcher is still running", e);
        }
    }

    /** The starter, <code>[--no-launcher-update]</code>, in <code>home</code>, two seconds from now: on Windows
     *  <code>run.bat</code> in a minimised console of its own, elsewhere <code>run.sh</code> under
     *  <code>sh</code>. By then this process, which runs on <code>runtime/</code>, has exited, and the starter
     *  can swap <code>runtime.new/</code> in before it starts the launcher. The caller exits at once. An
     *  <code>IOException</code> is shown in a dialog. */
    private void start(boolean asIs) {
        String flag = asIs ? " --no-launcher-update" : "";
        List<String> cmd = Os.WINDOWS ? List.of("cmd", "/c", "start", "\"\"", "/min", "cmd", "/c", "timeout /t 2 /nobreak >nul & call " + Os.STARTER + flag)
                                      : List.of("/bin/sh", "-c", "sleep 2; exec ./" + Os.STARTER + flag);
        try {
            new ProcessBuilder(cmd).directory(home.toFile()).inheritIO().start();
        } catch(IOException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(frame, "The launcher could not be started: " + reason(e) + "\n\nUnzip the launcher over " + home + " to mend it.",
                                          frame.getTitle(), JOptionPane.ERROR_MESSAGE);
        }
    }

    /** <code>getMessage()</code>, or <code>toString()</code> when null. */
    private static String reason(Exception e) {
        return (e.getMessage() != null) ? e.getMessage() : e.toString();
    }

    private void status(String s) {
        SwingUtilities.invokeLater(() -> status.setText(s));
    }

    /** Fraction in 0..1; negative = indeterminate. */
    private void progress(double fraction) {
        SwingUtilities.invokeLater(() -> {
            bar.setIndeterminate(fraction < 0);
            if(fraction >= 0)
                bar.setValue((int)Math.round(fraction * 1000));
        });
    }

    // ---- called by the launcher --------------------------------------------------------------------------------

    /** Copy <code>launcher.jar</code> to <code>update/updater.jar</code> and start {@link #main} from it with
     *  this process's pid. An updater that exits within 1 s is an <code>IOException</code>. The caller exits. */
    static void launch(Path home, String version, String url) throws IOException, InterruptedException {
        Path dir = home.resolve(STAGING);
        deleteTree(dir);
        Files.createDirectories(dir);
        Path jar = dir.resolve("updater.jar");
        Files.copy(home.resolve(JAR), jar);
        Process p = new ProcessBuilder(Os.javaw(home.resolve(RUNTIME)).toString(), "-cp", jar.toString(), Updater.class.getName(),
                                       home.toString(), Long.toString(ProcessHandle.current().pid()), version, url)
            .directory(home.toFile()).inheritIO().start();
        if(p.waitFor(1, TimeUnit.SECONDS))
            throw new IOException("the updater exited at once (code " + p.exitValue() + ")");
    }

    /** Delete <code>update/</code>, retrying for 5 s (the updater that started this launcher may still hold its
     *  jar); what remains waits for the next start. */
    static void tidy(Path home) {
        Path dir = home.resolve(STAGING);
        for(int i = 0; (i < 20) && Files.exists(dir); i++) {
            try {
                deleteTree(dir);
            } catch(IOException e) {
                try {
                    Thread.sleep(250);
                } catch(InterruptedException x) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Recursive delete; no-op if absent. */
    private static void deleteTree(Path dir) throws IOException {
        if(!Files.exists(dir))
            return;
        List<Path> all;
        try(Stream<Path> tree = Files.walk(dir)) {
            all = tree.toList();
        }
        for(int i = all.size() - 1; i >= 0; i--)
            Files.delete(all.get(i));
    }
}
