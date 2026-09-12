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
 * <code>launcher.properties</code>: written with its defaults the first time, read every time. The one
 * setting the window itself changes — the resource cache proxy checkbox — is written back in place, the rest
 * of the file left as the player has it.
 */
public final class Settings {
    private static final String DEFAULTS = """
        # Brodgar.io launcher settings. Delete a line to get its default back.

        # The heap the client runs with; fixed and pre-touched, so it is reserved at start.
        heap=2g

        # Where the client's releases are, as owner/repo on GitHub, and the name its zip starts with.
        repo=irongete/brodgar-io-client
        asset.prefix=brodgar-io-client-

        # Read the game's resources through the brodgar.io cache proxy instead of the game's own server:
        # the launcher's checkbox, remembered here.
        resource.proxy=false
        resource.proxy.url=http://brodgar.io/res/

        # false: never look for a release, offer whatever is installed.
        check.updates=true

        # Extra JVM options for the client, space-separated.
        java.opts=
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
    String repo()             {return get("repo", "irongete/brodgar-io-client");}
    String assetPrefix()      {return get("asset.prefix", "brodgar-io-client-");}
    boolean resourceProxy()   {return "true".equalsIgnoreCase(get("resource.proxy", "false"));}
    String resourceProxyUrl() {return get("resource.proxy.url", "http://brodgar.io/res/");}
    boolean checkUpdates()    {return !"false".equalsIgnoreCase(get("check.updates", "true"));}

    List<String> javaOpts() {
        String v = get("java.opts", "");
        return v.isEmpty() ? List.of() : Arrays.asList(v.split("\\s+"));
    }

    /** The checkbox: remembered at once, so the next run starts where this one left it. */
    void resourceProxy(boolean on) {
        p.setProperty("resource.proxy", String.valueOf(on));
        write("resource.proxy", String.valueOf(on));
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
