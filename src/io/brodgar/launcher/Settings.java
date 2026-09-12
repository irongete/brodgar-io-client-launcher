package io.brodgar.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * <code>launcher.properties</code>: written with its defaults the first time, read every time. What the window
 * changes — the channel dropdown, the proxy checkbox, the Options dialog — is written back in place, one
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

        # Read the game's resources through the brodgar.io cache proxy instead of the game's own server:
        # the launcher's checkbox, remembered here, and the proxy's address.
        resource.proxy=false
        resource.proxy.url=http://brodgar.io/res/

        # false: never look for a release, offer whatever is installed.
        check.updates=true

        # Where the client's releases are, as owner/repo on GitHub, and the name its zip starts with.
        repo=irongete/brodgar-io-client
        asset.prefix=brodgar-io-client-
        """;

    private final Path file;
    private final Properties p = new Properties();

    private Settings(Path file) {
        this.file = file;
    }

    static Settings load(Path file) {
        Settings s = new Settings(file);
        try {
            if(!Files.exists(file))
                Files.writeString(file, DEFAULTS);
            try(InputStream in = Files.newInputStream(file)) {
                s.p.load(in);
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
    Channel channel()         {return Channel.of(get("channel", "beta"), Channel.BETA);}
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

    /** The checkbox: remembered at once, so the next run starts where this one left it. */
    void resourceProxy(boolean on) {
        set("resource.proxy", String.valueOf(on));
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
     *  every other line, comments included, as it is. A file that cannot be written keeps the value for this
     *  run alone. */
    private void write(String key, String value) {
        try {
            List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file)) : new ArrayList<>();
            boolean done = false;
            for(int i = 0; i < lines.size(); i++) {
                String t = lines.get(i).stripLeading();
                if(t.startsWith(key + "=") || t.startsWith(key + " ") || t.startsWith(key + ":")) {
                    lines.set(i, key + "=" + value);
                    done = true;
                    break;
                }
            }
            if(!done)
                lines.add(key + "=" + value);
            Files.write(file, lines);
        } catch(IOException e) {
            // the setting stands for this run
        }
    }
}
