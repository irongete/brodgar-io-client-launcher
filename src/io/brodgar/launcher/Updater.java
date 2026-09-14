package io.brodgar.launcher;

import java.awt.Dimension;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
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
 * The launcher's updater: a small program of its own, started by a launcher that found a newer release of
 * itself, which then exits. It shows a window with a bar, waits for the launcher to be gone, downloads the
 * release zip into <code>update/</code>, unpacks it there, puts <code>launcher.jar</code> and
 * <code>run.bat</code> in place — each in one atomic move, so a failure leaves the old file rather than none —
 * moves the release's runtime to <code>runtime.new/</code>, starts the launcher and exits.
 *
 * <p>Two things it cannot do. It cannot run from <code>launcher.jar</code>, which Java holds open for as long as
 * it runs, so the launcher starts it from a copy, <code>update/updater.jar</code>; the launcher removes
 * <code>update/</code> when it next starts. And it cannot replace <code>runtime/</code>, which it runs on — as
 * the game does, possibly for hours — so <code>run.bat</code> swaps <code>runtime.new/</code> in at the next
 * start, when nothing runs on the runtime. Until then the new launcher runs on the old runtime, which it can.
 *
 * <p>When anything fails, a dialog says so, and the launcher as it stands is started with
 * <code>--no-launcher-update</code>, so that one run gets to the game; the next start tries again. The client is
 * never touched.
 */
public final class Updater {
    private static final String JAR = "launcher.jar", BAT = "run.bat", RUNTIME = "runtime", STAGING = "update";
    /** The release asset, as build.xml names it: <code>Brodgar-launcher-&lt;version&gt;-windows.zip</code>. */
    private static final String ASSET_PREFIX = "Brodgar-launcher-", ASSET_SUFFIX = "-windows.zip";

    private final Path home;
    private final JFrame frame;
    private final JLabel status;
    private final JProgressBar bar;

    private Updater(Path home, String version) {
        this.home = home;
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch(Exception e) {
            // the cross-platform look is fine too
        }
        frame = new JFrame("Brodgar.io launcher " + version);
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

    /** <code>Updater &lt;home&gt; &lt;pid&gt; &lt;version&gt; &lt;url&gt;</code>: replace the launcher in
     *  <code>home</code>, once the process <code>pid</code> is gone, with the <code>version</code> at
     *  <code>url</code>. */
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
            u.status("Launcher " + version + " could not be installed.");
            JOptionPane.showMessageDialog(u.frame, "Launcher " + version + " could not be installed: " + reason(e)
                                          + "\n\nThe launcher goes on as it is, and tries again the next time it starts.",
                                          u.frame.getTitle(), JOptionPane.ERROR_MESSAGE);
            u.start(true);
        }
        System.exit(0);
    }

    /** The update itself: the release downloaded and unpacked into <code>update/</code>, then the jar and the
     *  bat put in place and the runtime staged as <code>runtime.new/</code>. */
    private void install(long pid, String version, String url) throws IOException, InterruptedException {
        waitFor(pid);
        Path dir = home.resolve(STAGING);
        Path zip = dir.resolve("launcher.zip");
        status("Downloading launcher " + version + "...");
        GitHubRelease.download(url, zip, this::progress);
        status("Installing launcher " + version + "...");
        Unzip.unpack(zip, dir);
        Files.delete(zip);
        if(!Files.isRegularFile(dir.resolve(JAR)) || !Files.isRegularFile(dir.resolve(RUNTIME).resolve("bin").resolve("javaw.exe")))
            throw new IOException("the release zip does not hold a launcher");
        Path staged = home.resolve(RUNTIME + ".new");
        deleteTree(staged);
        Files.move(dir.resolve(RUNTIME), staged, StandardCopyOption.ATOMIC_MOVE);
        Files.move(dir.resolve(BAT), home.resolve(BAT), StandardCopyOption.ATOMIC_MOVE);
        Files.move(dir.resolve(JAR), home.resolve(JAR), StandardCopyOption.ATOMIC_MOVE);
    }

    /** Wait for the launcher <code>pid</code> to be gone: it exits right after starting this updater, so a minute
     *  is a launcher that is stuck. */
    private static void waitFor(long pid) throws IOException, InterruptedException {
        try {
            ProcessHandle.of(pid).map(ProcessHandle::onExit).orElse(CompletableFuture.completedFuture(null)).get(60, TimeUnit.SECONDS);
        } catch(TimeoutException | ExecutionException e) {
            throw new IOException("the launcher is still running", e);
        }
    }

    /** Start the launcher in <code>home</code> on the runtime there; <code>asIs</code> tells it not to look for a
     *  newer launcher this once. A launcher that cannot be started is said so in a dialog: the window is the last
     *  thing there is. */
    private void start(boolean asIs) {
        List<String> cmd = new ArrayList<>(List.of(home.resolve(RUNTIME).resolve("bin").resolve("javaw.exe").toString(), "-jar", home.resolve(JAR).toString()));
        if(asIs)
            cmd.add("--no-launcher-update");
        try {
            new ProcessBuilder(cmd).directory(home.toFile()).inheritIO().start();
        } catch(IOException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(frame, "The launcher could not be started: " + reason(e) + "\n\nUnzip the launcher over " + home + " to mend it.",
                                          frame.getTitle(), JOptionPane.ERROR_MESSAGE);
        }
    }

    /** What went wrong, in a line: the message, or the exception when it has none. */
    private static String reason(Exception e) {
        return (e.getMessage() != null) ? e.getMessage() : e.toString();
    }

    private void status(String s) {
        SwingUtilities.invokeLater(() -> status.setText(s));
    }

    /** A fraction in 0..1, or a negative number while the size is unknown. */
    private void progress(double fraction) {
        SwingUtilities.invokeLater(() -> {
            bar.setIndeterminate(fraction < 0);
            if(fraction >= 0)
                bar.setValue((int)Math.round(fraction * 1000));
        });
    }

    // ---- the launcher's side ---------------------------------------------------------------------------------

    /** The zip a release carries: <code>Brodgar-launcher-1.1.0-windows.zip</code> under <code>v1.1.0</code>. */
    static String asset(String tag) {
        return ASSET_PREFIX + GitHubRelease.version(tag) + ASSET_SUFFIX;
    }

    /** Start an updater for the launcher in <code>home</code>, on a copy of its jar in <code>update/</code>, to
     *  replace this process with the <code>version</code> at <code>url</code> — which this process then leaves
     *  to it by exiting. An updater gone within a second did not start, and that is a failure here. */
    static void launch(Path home, String version, String url) throws IOException, InterruptedException {
        Path dir = home.resolve(STAGING);
        deleteTree(dir);
        Files.createDirectories(dir);
        Path jar = dir.resolve("updater.jar");
        Files.copy(home.resolve(JAR), jar);
        Process p = new ProcessBuilder(home.resolve(RUNTIME).resolve("bin").resolve("javaw.exe").toString(), "-cp", jar.toString(), Updater.class.getName(),
                                       home.toString(), Long.toString(ProcessHandle.current().pid()), version, url)
            .directory(home.toFile()).inheritIO().start();
        if(p.waitFor(1, TimeUnit.SECONDS))
            throw new IOException("the updater exited at once (code " + p.exitValue() + ")");
    }

    /** Remove <code>update/</code>, where an updater ran: the one that started this launcher may be letting go
     *  of its jar still, so a few seconds are given; what is left then waits for the next start. */
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

    /** Remove the folder <code>dir</code> and everything in it; nothing to do when it is not there. */
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
