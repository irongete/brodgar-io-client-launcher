package io.brodgar.launcher;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * <code>launcher.properties</code>: written with its defaults the first time, read every time. What the window
 * changes — the channel dropdown, the two checkboxes, the Options dialog — is written back in place, one
 * <code>key=value</code> line each, the rest of the file left as the player has it.
 */
public final class Settings {
    private static final String DEFAULTS = """
        # Brodgar.io launcher settings. Delete a line to get its default back. The Options button edits
        # the ones that shape how the game is started.

        # Memory the game is given, e.g. 512m, 2g, 4g. It is reserved when the game starts (-Xms = -Xmx).
        heap=2g

        # true: touch all of that memory when the game starts, rather than page by page as it is first used.
        heap.pretouch=true

        # How the game frees memory it no longer uses -- zgc: concurrently, while the game keeps running;
        # g1: Java's default, in short stops.
        gc=zgc

        # true: let Windows scale the game window on a high-DPI screen; false: the game draws at 1:1 and
        # scales its interface itself.
        ui.scale=false

        # When a server has both kinds of address -- system: as Windows prefers; false: IPv4 first;
        # true: IPv6 first.
        ipv6=system

        # Extra options for Java when the game starts, space-separated.
        java.opts=

        # Which releases to install -- release: plain releases only; beta: pre-releases too, the newest of
        # everything. The launcher's dropdown, remembered here.
        channel=beta

        # true: start the game in a command window (java.exe) that shows what it prints and stays open when
        # the game ends in an error -- the way to see what went wrong; false: no window, and what the game
        # printed is in client.log. The launcher's checkbox, remembered here.
        console=false

        # Read the game's resources through the brodgar.io cache proxy instead of the game's own server:
        # the launcher's checkbox, remembered here, and the proxy's address.
        resource.proxy=false
        resource.proxy.url=http://brodgar.io/res/

        # false: never look for a release -- the launcher's own or the game's -- and offer whatever is installed.
        check.updates=true

        # Where the client's releases are, as owner/repo on GitHub, and the name its zip starts with.
        repo=irongete/brodgar-io-client
        asset.prefix=brodgar-io-client-

        # Where the launcher's own releases are: it keeps itself at the newest one on the channel, as it keeps
        # the client.
        launcher.repo=irongete/brodgar-io-client-launcher
        """;

    private final Path file;
    private final Properties p = new Properties();

    private Settings(Path file) {
        this.file = file;
    }

    /** The settings in <code>file</code>, which is first written with the defaults when there is none — unless
     *  <code>create</code> is off, as it is for a check run, which touches nothing: the defaults then simply
     *  stand. The file is UTF-8, as it is written. */
    static Settings load(Path file, boolean create) {
        Settings s = new Settings(file);
        try {
            if(create && !Files.exists(file))
                Files.writeString(file, DEFAULTS);
            if(Files.exists(file)) {
                try(Reader in = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
                    s.p.load(in);
                }
            }
        } catch(IOException e) {
            // unreadable or unwritable: the defaults below stand
        }
        return s;
    }

    private String get(String key, String def) {
        String v = p.getProperty(key);
        return ((v == null) || v.isBlank()) ? def : v.trim();
    }

    String heap()             {return get("heap", "2g");}
    boolean pretouch()        {return !"false".equalsIgnoreCase(get("heap.pretouch", "true"));}
    String gc()               {return "g1".equalsIgnoreCase(get("gc", "zgc")) ? "g1" : "zgc";}
    boolean uiScale()         {return "true".equalsIgnoreCase(get("ui.scale", "false"));}
    String ipv6()             {String v = get("ipv6", "system").toLowerCase(); return (v.equals("true") || v.equals("false")) ? v : "system";}
    String javaOptsText()     {return get("java.opts", "");}
    String repo()             {return get("repo", "irongete/brodgar-io-client");}
    String assetPrefix()      {return get("asset.prefix", "brodgar-io-client-");}
    String launcherRepo()     {return get("launcher.repo", "irongete/brodgar-io-client-launcher");}
    Channel channel()         {return Channel.of(get("channel", "beta"), Channel.BETA);}
    boolean console()         {return "true".equalsIgnoreCase(get("console", "false"));}
    boolean resourceProxy()   {return "true".equalsIgnoreCase(get("resource.proxy", "false"));}
    String resourceProxyUrl() {return get("resource.proxy.url", "http://brodgar.io/res/");}
    boolean checkUpdates()    {return !"false".equalsIgnoreCase(get("check.updates", "true"));}

    /** Everything that shapes the game's command line, as it stands. */
    Launch launch() {
        return new Launch(heap(), pretouch(), gc(), uiScale(), ipv6(), split(javaOptsText()), resourceProxy(), resourceProxyUrl());
    }

    /** The parts of the game's command line — what the settings hold, or what the Options dialog is showing. */
    record Launch(String heap, boolean pretouch, String gc, boolean uiScale, String ipv6, List<String> opts, boolean proxy, String proxyUrl) {}

    /** Space-separated options as a list; nothing from an empty or blank string. */
    static List<String> split(String opts) {
        String v = (opts == null) ? "" : opts.trim();
        return v.isEmpty() ? List.of() : Arrays.asList(v.split("\\s+"));
    }

    /** The proxy checkbox: remembered at once, so the next run starts where this one left it. */
    void resourceProxy(boolean on) {
        set("resource.proxy", String.valueOf(on));
    }

    /** The console checkbox, remembered the same way. */
    void console(boolean on) {
        set("console", String.valueOf(on));
    }

    /** The dropdown, remembered the same way. */
    void channel(Channel c) {
        set("channel", c.key);
    }

    /** One setting, kept in memory and in the file — what the Options dialog writes on OK. */
    void set(String key, String value) {
        p.setProperty(key, value);
        write(key, value);
    }

    /** Set <code>key=value</code> on its own line — replacing the line that holds it, or appended — and leave
     *  every other line, comments included, as it is. The value is written the way {@link Properties#load} reads
     *  it back: a backslash is an escape to it, so each one is doubled (a Windows path in the Java options would
     *  otherwise lose its separators on the next run); nothing else in a one-line value is special. A file that
     *  cannot be written keeps the value for this run alone. */
    private void write(String key, String value) {
        try {
            List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file)) : new ArrayList<>();
            String line = key + "=" + value.replace("\\", "\\\\");
            boolean done = false;
            for(int i = 0; i < lines.size(); i++) {
                String t = lines.get(i).stripLeading();
                if(t.startsWith(key + "=") || t.startsWith(key + " ") || t.startsWith(key + ":")) {
                    lines.set(i, line);
                    done = true;
                    break;
                }
            }
            if(!done)
                lines.add(line);
            Files.write(file, lines);
        } catch(IOException e) {
            // the setting stands for this run
        }
    }
}
