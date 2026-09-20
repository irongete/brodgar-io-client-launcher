package io.brodgar.launcher;

import java.awt.Dimension;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
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
 * Launcher self-update, a separate process. The shipped layout is jpackage's, with the runtime named after the
 * version it came with:
 * <pre>
 *   Brodgar.exe            starts the launcher, no console: the JVM of the runtime app/Brodgar.cfg names, in-process
 *   app/launcher.jar       the launcher; the trailer's files and Brodgar.cfg beside it
 *   runtime-&lt;version&gt;/     that launcher's runtime, which also runs the client
 *   update/                this update's staging folder; the launcher it installs deletes it
 * </pre>
 * So an update never replaces a folder a process runs on. {@link #launch} copies <code>app/launcher.jar</code>
 * to <code>update/updater.jar</code> (the jar in use is locked) and starts this class from it, on the current
 * runtime; the launcher exits. {@link #main}: wait for the launcher's pid, download the release zip into
 * <code>update/</code>, unpack, {@link #place} the launcher (its runtime folder, <code>app/</code> in the old
 * one's stead, <code>Brodgar.exe</code>, <code>run.bat</code>), start <code>Brodgar.exe</code>, exit. The
 * launcher then deletes <code>update/</code> and the runtimes of other versions ({@link #tidy}).
 *
 * <p>On failure: error dialog, then the current launcher is started with <code>--no-launcher-update</code>.
 * <code>client/</code> is never touched.
 *
 * <p>Launchers before v7 (<code>run.bat</code>, <code>launcher.jar</code> and <code>runtime/</code> at the root)
 * update through their own updater, which takes those three from the zip and starts the jar on the old
 * runtime. The zip still carries them — the jar a copy of <code>app/launcher.jar</code>, <code>run.bat</code>
 * forwarding to <code>Brodgar.exe</code>, <code>runtime/</code> a stub — and that jar, so started, finishes
 * the update itself: {@link #migrate} places what that updater left in <code>update/</code> and starts
 * <code>Brodgar.exe</code>, touching no JavaFX, which the old runtime may lack.
 */
public final class Updater {
    private static final String EXE = "Brodgar.exe", APP = "app", JAR = "launcher.jar", BAT = "run.bat", STAGING = "update";
    /** The runtime folders: this prefix, the version. Also what launchers before v7 named theirs (with
     *  <code>.new</code> and <code>.old</code>). */
    static final String RUNTIME = "runtime";
    /** The release asset, as build.xml names it: the same for every release, since the folder a player unzips
     *  keeps its name while the launcher inside updates itself. */
    static final String ASSET = "brodgar.io-launcher.zip";

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
        frame = new JFrame(Launcher.TITLE + " v" + version);
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
            u.status("Launcher " + version + " could not be installed.");
            JOptionPane.showMessageDialog(u.frame, "Launcher " + version + " could not be installed: " + reason(e)
                                          + "\n\nThe launcher goes on as it is, and tries again the next time it starts.",
                                          u.frame.getTitle(), JOptionPane.ERROR_MESSAGE);
            u.start(true);
        }
        System.exit(0);
    }

    /** Wait for <code>pid</code>; download and unpack into <code>update/</code>; {@link #place}. */
    private void install(long pid, String version, String url) throws IOException, InterruptedException {
        waitFor(pid);
        Path dir = home.resolve(STAGING);
        Path zip = dir.resolve("launcher.zip");
        status("Downloading launcher " + version + "...");
        GitHubRelease.download(url, zip, this::progress);
        status("Installing launcher " + version + "...");
        Unzip.unpack(zip, dir);
        Files.delete(zip);
        place(home, dir);
    }

    /** Move the launcher unpacked in <code>dir</code> into <code>home</code>: its runtime folder (a leftover of the
     *  same name deleted first), <code>app/</code> (the old one deleted first), <code>Brodgar.exe</code> and
     *  <code>run.bat</code>. <code>IOException</code> if <code>dir</code> holds no launcher. */
    static void place(Path home, Path dir) throws IOException {
        Path runtime = runtimeIn(dir);
        if((runtime == null) || !Files.isRegularFile(dir.resolve(APP).resolve(JAR)) || !Files.isRegularFile(dir.resolve(EXE)))
            throw new IOException("the release zip does not hold a launcher");
        Path target = home.resolve(runtime.getFileName());
        deleteTree(target);
        Files.move(runtime, target, StandardCopyOption.ATOMIC_MOVE);
        deleteTree(home.resolve(APP));
        Files.move(dir.resolve(APP), home.resolve(APP), StandardCopyOption.ATOMIC_MOVE);
        Files.move(dir.resolve(EXE), home.resolve(EXE), StandardCopyOption.REPLACE_EXISTING);
        if(Files.isRegularFile(dir.resolve(BAT)))
            Files.move(dir.resolve(BAT), home.resolve(BAT), StandardCopyOption.REPLACE_EXISTING);
    }

    /** The <code>runtime-*</code> folder in <code>dir</code> with <code>bin/javaw.exe</code>; null if none. */
    private static Path runtimeIn(Path dir) throws IOException {
        try(Stream<Path> entries = Files.list(dir)) {
            return entries.filter(p -> p.getFileName().toString().startsWith(RUNTIME + "-")
                                       && Files.isRegularFile(p.resolve("bin").resolve("javaw.exe")))
                          .findFirst().orElse(null);
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

    /** <code>Brodgar.exe [--no-launcher-update]</code> in <code>home</code>; an <code>IOException</code> is shown
     *  in a dialog. */
    private void start(boolean asIs) {
        List<String> cmd = new ArrayList<>(List.of(home.resolve(EXE).toString()));
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

    /** Copy <code>app/launcher.jar</code> to <code>update/updater.jar</code> and start {@link #main} from it, on
     *  this launcher's runtime, with this process's pid. An updater that exits within 1 s is an
     *  <code>IOException</code>. The caller exits. */
    static void launch(Path home, String version, String url) throws IOException, InterruptedException {
        Path dir = home.resolve(STAGING);
        deleteTree(dir);
        Files.createDirectories(dir);
        Path jar = dir.resolve("updater.jar");
        Files.copy(home.resolve(APP).resolve(JAR), jar);
        Process p = new ProcessBuilder(Launcher.javaw().toString(), "-cp", jar.toString(), Updater.class.getName(),
                                       home.toString(), Long.toString(ProcessHandle.current().pid()), version, url)
            .directory(home.toFile()).inheritIO().start();
        if(p.waitFor(1, TimeUnit.SECONDS))
            throw new IOException("the updater exited at once (code " + p.exitValue() + ")");
    }

    /** A launcher before v7 updated to this one: its updater put <code>launcher.jar</code> at the root and started
     *  it on the old runtime, leaving the rest of the zip in <code>update/</code>. Finish: {@link #place} that and
     *  start <code>Brodgar.exe</code> with <code>args</code>. True if this launcher is that jar and the folder is
     *  there (the caller exits, a failure shown in a dialog); false otherwise. */
    static boolean migrate(Path home, String[] args) {
        Path dir = home.resolve(STAGING);
        Path jar = Launcher.jar();
        try {
            if((jar == null) || !Files.isRegularFile(dir.resolve(EXE)) || !Files.isSameFile(jar, home.resolve(JAR)))
                return false;
            place(home, dir);
            List<String> cmd = new ArrayList<>(List.of(home.resolve(EXE).toString()));
            cmd.addAll(Arrays.asList(args));
            new ProcessBuilder(cmd).directory(home.toFile()).inheritIO().start();
        } catch(IOException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(null, "The launcher could not be updated: " + reason(e) + "\n\nUnzip the launcher over " + home + " to mend it.",
                                          Launcher.TITLE, JOptionPane.ERROR_MESSAGE);
        }
        return true;
    }

    /** What an update leaves for the launcher it installed: <code>update/</code>, the runtime folders of other
     *  versions (those of launchers before v7 included, with their <code>launcher.jar</code> at the root).
     *  Retried for 5 s: the updater that started this launcher may still hold its jar and its runtime. What is
     *  still in use waits for the next start. */
    static void tidy(Path home) {
        for(int i = 0; i < 20; i++) {
            List<Path> left = leftovers(home);
            if(left.isEmpty())
                return;
            for(Path p : left) {
                try {
                    deleteTree(p);
                } catch(IOException e) {
                    // in use: again in a moment, or the next start
                }
            }
            try {
                Thread.sleep(250);
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** {@link #tidy}'s targets still present. */
    private static List<Path> leftovers(Path home) {
        List<Path> out = new ArrayList<>();
        Path mine = Paths.get(System.getProperty("java.home"));
        Path self = Launcher.jar();
        try(Stream<Path> entries = Files.list(home)) {
            for(Path p : entries.toList()) {
                String name = p.getFileName().toString();
                try {
                    if(name.equals(STAGING)
                       || (Files.isDirectory(p) && name.startsWith(RUNTIME) && !Files.isSameFile(p, mine))
                       || (name.equals(JAR) && (self != null) && !Files.isSameFile(p, self)))
                        out.add(p);
                } catch(IOException e) {
                    // gone meanwhile
                }
            }
        } catch(IOException e) {
            // home unreadable: nothing to tidy
        }
        return out;
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
