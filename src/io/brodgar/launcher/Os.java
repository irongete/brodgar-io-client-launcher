package io.brodgar.launcher;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * The platform the launcher runs on, and what differs by it: the Java executables of a runtime, the file that
 * starts the launcher, the launcher's release asset and where its files sit in it, how a folder or a page is
 * opened, and on Linux the applications menu entry. Released for Windows x64, macOS on Apple Silicon and Linux
 * x64 ({@link #asset}); elsewhere the launcher runs wherever Java and JavaFX do, and is never updated.
 *
 * <p>On macOS the player starts <code>Brodgar launcher.app</code>, which keeps the launcher in
 * <code>~/Library/Application Support/Brodgar.io</code> and starts <code>run.sh</code> there: that folder is
 * home (<code>etc/macos/launcher</code> says why). On Linux home is the folder the zip was unpacked to, as on
 * Windows.
 */
final class Os {
    private Os() {}

    private static final String NAME = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static final String ARCH = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    static final boolean WINDOWS = NAME.startsWith("windows");
    static final boolean MAC = NAME.startsWith("mac");
    static final boolean LINUX = NAME.startsWith("linux");

    /** The file beside <code>launcher.jar</code> that starts it, and swaps in the runtime a launcher update left:
     *  <code>run.bat</code> on Windows, <code>run.sh</code> elsewhere. */
    static final String STARTER = WINDOWS ? "run.bat" : "run.sh";

    /** The launcher's icon beside <code>launcher.jar</code> on Linux and macOS: the menu entry's
     *  ({@link #menuEntry}) and the Dock's (<code>run.sh</code>). */
    static final String ICON = "icon.png";

    /** Where the launcher's files sit in {@link #asset} once unpacked (its one top folder stripped,
     *  {@link Unzip}): at its root, or on macOS inside the app, <code>Brodgar launcher.app</code>. */
    static final String PAYLOAD = MAC ? "Contents/Resources/launcher" : "";

    /** The platform's name as a player knows it, for the labels that name it. */
    static String label() {
        return WINDOWS ? "Windows" : MAC ? "macOS" : LINUX ? "Linux" : System.getProperty("os.name");
    }

    /** The Java that runs the client with no console: <code>bin/javaw.exe</code> in <code>runtime</code> on
     *  Windows, <code>bin/java</code> elsewhere, where no console window comes with it. */
    static Path javaw(Path runtime) {
        return runtime.resolve("bin").resolve(WINDOWS ? "javaw.exe" : "java");
    }

    /** The launcher's release asset for this platform, as build.xml names it; null where none is released, and
     *  such a launcher is never updated. The Windows zip also rides in every release under the name it had
     *  when Windows was the only platform, brodgar.io-launcher.zip, which the launchers from those days ask
     *  for: they update themselves through it into one that asks for this name. */
    static String asset() {
        boolean x64 = ARCH.equals("amd64") || ARCH.equals("x86_64");
        boolean arm64 = ARCH.equals("aarch64") || ARCH.equals("arm64");
        if(WINDOWS && x64)
            return "brodgar.io-launcher-windows-x64.zip";
        if(MAC && arm64)
            return "brodgar.io-launcher-macos-arm64.zip";
        if(LINUX && x64)
            return "brodgar.io-launcher-linux-x64.zip";
        return null;
    }

    /** Open <code>dir</code> in the file manager: <code>Desktop</code> on Windows, <code>open</code> on macOS,
     *  <code>xdg-open</code> on Linux; neither of the last two touches AWT, which on macOS would share the
     *  main thread with JavaFX. */
    static void open(Path dir) throws IOException {
        if(WINDOWS)
            Desktop.getDesktop().open(dir.toFile());
        else
            run(dir.toString());
    }

    /** Open <code>url</code> in the browser, as {@link #open} does a folder; nothing if there is none. */
    static void browse(String url) {
        try {
            if(WINDOWS)
                Desktop.getDesktop().browse(URI.create(url));
            else
                run(url);
        } catch(IOException | UnsupportedOperationException | IllegalArgumentException e) {
            // no browser to open it in
        }
    }

    /** <code>open</code> or <code>xdg-open</code> on <code>target</code>, not waited for. */
    private static void run(String target) throws IOException {
        new ProcessBuilder(MAC ? "open" : "xdg-open", target).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    /**
     * On Linux, the applications menu entry, <code>io.brodgar.launcher.desktop</code> in
     * <code>$XDG_DATA_HOME/applications</code> (<code>~/.local/share/applications</code>): <code>run.sh</code> in
     * <code>home</code>, with its {@link #ICON}. Written at every start of a launcher as shipped, when it says
     * anything else, so a folder moved elsewhere mends its entry the next time it is started from there. A
     * failure is only printed: the launcher starts from <code>run.sh</code> as well.
     */
    static void menuEntry(Path home) {
        if(!LINUX || !Files.isRegularFile(home.resolve(STARTER)))
            return;
        String data = System.getenv("XDG_DATA_HOME");
        Path dir = ((data != null) && !data.isBlank()) ? Paths.get(data) : Paths.get(System.getProperty("user.home"), ".local", "share");
        Path file = dir.resolve("applications").resolve("io.brodgar.launcher.desktop");
        String entry = String.join("\n",
            "[Desktop Entry]",
            "Type=Application",
            "Name=Brodgar launcher",
            "Comment=The Brodgar launcher for Haven & Hearth",
            "Exec=" + quoted(home.resolve(STARTER).toString()),
            "Path=" + escaped(home.toString()),
            "Icon=" + escaped(home.resolve(ICON).toString()),
            "Terminal=false",
            "Categories=Game;",
            "");
        try {
            if(Files.isRegularFile(file) && Files.readString(file).equals(entry))
                return;
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry);
        } catch(IOException e) {
            System.err.println(file + " could not be written: " + e);
        }
    }

    /** A desktop entry's string value: a backslash doubled, a line break as <code>\n</code>. */
    private static String escaped(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n");
    }

    /** <code>Exec</code>'s one argument: in double quotes, where <code>"</code>, <code>`</code>, <code>$</code>
     *  and <code>\</code> take a backslash, <code>%</code> is doubled, and the string escapes apply on top
     *  ({@link #escaped}): the Desktop Entry Specification's two rules. */
    private static String quoted(String s) {
        StringBuilder b = new StringBuilder("\"");
        for(char c : s.toCharArray()) {
            if((c == '"') || (c == '`') || (c == '$') || (c == '\\'))
                b.append('\\');
            b.append((c == '%') ? "%%" : String.valueOf(c));
        }
        return escaped(b.append('"').toString());
    }
}
