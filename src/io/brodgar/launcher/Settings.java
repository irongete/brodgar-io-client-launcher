package io.brodgar.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/** <code>launcher.properties</code>: written with its defaults the first time, read every time. */
public final class Settings {
    private static final String DEFAULTS = """
        # Brodgar.io launcher settings. Delete a line to get its default back.

        # The heap the client runs with; fixed and pre-touched, so it is reserved at start.
        heap=2g

        # Where the client's releases are, as owner/repo on GitHub, and the name its zip starts with.
        repo=irongete/brodgar-io-client
        asset.prefix=brodgar-io-client-

        # The resource server the client is told to use (its -U argument).
        resurl=http://brodgar.io/res/

        # false: never look for a release, start whatever is installed.
        check.updates=true

        # Extra JVM options for the client, space-separated.
        java.opts=
        """;

    private final Properties p = new Properties();

    static Settings load(Path file) {
        Settings s = new Settings();
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

    String heap()          {return get("heap", "2g");}
    String repo()          {return get("repo", "irongete/brodgar-io-client");}
    String assetPrefix()   {return get("asset.prefix", "brodgar-io-client-");}
    String resUrl()        {return get("resurl", "http://brodgar.io/res/");}
    boolean checkUpdates() {return !"false".equalsIgnoreCase(get("check.updates", "true"));}

    List<String> javaOpts() {
        String v = get("java.opts", "");
        return v.isEmpty() ? List.of() : Arrays.asList(v.split("\\s+"));
    }
}
