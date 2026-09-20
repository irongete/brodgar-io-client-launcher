package io.brodgar.launcher;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * <code>launcher.properties</code>: created from {@link #DEFAULTS} if absent, loaded at start. Each change is
 * written back at once as one <code>key=value</code> line ({@link #writeLine}); other lines are kept.
 */
public final class Settings {
    private static final String DEFAULTS = """
        # brodgar.io launcher settings. A missing key takes its default. See README.md, "Settings".

        # -Xms<heap> -Xmx<heap>
        heap=2g

        # -XX:+AlwaysPreTouch
        heap.pretouch=true

        # zgc: -XX:+UseZGC; g1: JVM default
        gc=zgc

        # false: -Dsun.java2d.uiScale.enabled=false
        ui.scale=false

        # -Djava.net.preferIPv6Addresses=<system|true|false>
        ipv6=system

        # extra JVM arguments, space-separated
        java.opts=

        # release: non-prerelease tags only; beta: all tags
        channel=beta

        # true: java.exe in a cmd window; false: javaw.exe, output to client.log
        console=false

        # haven.resurl in client/haven-config.properties := resource.proxy ? resource.proxy.url : resource.url
        resource.proxy=false
        resource.url=https://game.havenandhearth.com/res/
        resource.proxy.url=https://res.brodgar.io/

        # the brodgar.io resource pack, client/brodgar-res.jar: every resource from the first start. With
        # resource.proxy on: downloaded when missing, renewed when the server's is newer and the installed one is
        # older than resource.pack.renew.days (0: whenever the server's is newer)
        resource.pack=true
        resource.pack.url=https://brodgar.io/res/?jar
        resource.pack.renew.days=30

        # true: haven.addondir in client/haven-config.properties := addons.dir; false: line removed (client/addons)
        addons.override=false
        addons.dir=

        # true: haven.savedatadir in client/haven-config.properties := savedata.dir; false: line removed (client/savedata)
        savedata.override=false
        savedata.dir=

        # false: no release lookup for the launcher or the client
        check.updates=true

        # GitHub owner/repo of the client releases; asset name = <asset.prefix><tag>.zip
        repo=irongete/brodgar-io-client
        asset.prefix=brodgar.io-client-

        # GitHub owner/repo of the launcher releases
        launcher.repo=irongete/brodgar-io-client-launcher
        """;

    private final Path file;
    private final Properties p = new Properties();

    private Settings(Path file) {
        this.file = file;
    }

    /** Load <code>file</code> (UTF-8). If absent and <code>create</code>, write {@link #DEFAULTS} first;
     *  <code>--check</code> passes false. */
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
            // unreadable or unwritable: defaults
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
    String assetPrefix()      {return get("asset.prefix", "brodgar.io-client-");}
    String launcherRepo()     {return get("launcher.repo", "irongete/brodgar-io-client-launcher");}
    Channel channel()         {return Channel.of(get("channel", "beta"), Channel.BETA);}
    boolean console()         {return "true".equalsIgnoreCase(get("console", "false"));}
    boolean resourceProxy()   {return "true".equalsIgnoreCase(get("resource.proxy", "false"));}
    String resourceUrl()      {return get("resource.url", "https://game.havenandhearth.com/res/");}
    String resourceProxyUrl() {return get("resource.proxy.url", "https://res.brodgar.io/");}
    boolean resourcePack()    {return !"false".equalsIgnoreCase(get("resource.pack", "true"));}
    String resourcePackUrl()  {return get("resource.pack.url", "https://brodgar.io/res/?jar");}
    int resourcePackRenewDays() {try {return Math.max(0, Integer.parseInt(get("resource.pack.renew.days", "30")));} catch(NumberFormatException e) {return 30;}}
    /** Absent from a file written before the flag existed: on when a folder is set. */
    boolean addonsOverride()  {return "true".equalsIgnoreCase(get("addons.override", addonsDir().isBlank() ? "false" : "true"));}
    String addonsDir()        {return get("addons.dir", "");}
    boolean savedataOverride() {return "true".equalsIgnoreCase(get("savedata.override", "false"));}
    String savedataDir()      {return get("savedata.dir", "");}
    boolean checkUpdates()    {return !"false".equalsIgnoreCase(get("check.updates", "true"));}

    /** {@link Launch} from the current values. */
    Launch launch() {
        return new Launch(heap(), pretouch(), gc(), uiScale(), ipv6(), split(javaOptsText()));
    }

    /** Inputs of {@link Launcher#command}. */
    record Launch(String heap, boolean pretouch, String gc, boolean uiScale, String ipv6, List<String> opts) {}

    /** Lines the launcher owns in <code>client/haven-config.properties</code>, in write order:
     *  <code>haven.resurl</code>, <code>haven.addondir</code>, <code>haven.savedatadir</code>. A null value
     *  means the line is removed: an override that is off, or on with no folder. */
    Map<String, String> clientConfig() {
        return clientConfig(resourceProxy(), resourceUrl(), resourceProxyUrl(),
                            addonsOverride() ? addonsDir() : "", savedataOverride() ? savedataDir() : "");
    }

    /** {@link #clientConfig()} from explicit values; a blank folder is no override. */
    static Map<String, String> clientConfig(boolean proxy, String url, String proxyUrl, String addonsDir, String savedataDir) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("haven.resurl", proxy ? proxyUrl : url);
        m.put("haven.addondir", addonsDir.isBlank() ? null : addonsDir.trim());
        m.put("haven.savedatadir", savedataDir.isBlank() ? null : savedataDir.trim());
        return m;
    }

    /** <code>key=value</code> per non-null entry, one per line; for <code>--check</code>. */
    static String lines(Map<String, String> config) {
        StringBuilder b = new StringBuilder();
        for(Map.Entry<String, String> e : config.entrySet()) {
            if(e.getValue() == null)
                continue;
            b.append(b.length() > 0 ? System.lineSeparator() : "").append(e.getKey()).append('=').append(e.getValue());
        }
        return b.toString();
    }

    /** Split on whitespace; empty list for blank. */
    static List<String> split(String opts) {
        String v = (opts == null) ? "" : opts.trim();
        return v.isEmpty() ? List.of() : Arrays.asList(v.split("\\s+"));
    }

    /** Write <code>resource.proxy</code>. */
    void resourceProxy(boolean on) {
        set("resource.proxy", String.valueOf(on));
    }

    /** Write <code>console</code>. */
    void console(boolean on) {
        set("console", String.valueOf(on));
    }

    /** Write <code>channel</code>. */
    void channel(Channel c) {
        set("channel", c.key);
    }

    /** Set in memory and write to the file. */
    void set(String key, String value) {
        p.setProperty(key, value);
        write(key, value);
    }

    /** {@link #writeLine} on this file; an <code>IOException</code> is printed to stderr and the value holds
     *  in memory only. */
    private void write(String key, String value) {
        try {
            writeLine(file, key, value);
        } catch(IOException e) {
            System.err.println(file + " could not be written (" + key + "): " + e);
        }
    }

    /** Replace the first line starting with <code>key=</code>, <code>key </code> or <code>key:</code> by
     *  <code>key=value</code>, or append it; other lines are kept; a missing file is created. Null value:
     *  remove that line (no write if absent). Backslashes in the value are doubled for {@link Properties#load}.
     *  Used for <code>launcher.properties</code> and for <code>client/haven-config.properties</code>. */
    static void writeLine(Path file, String key, String value) throws IOException {
        List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file)) : new ArrayList<>();
        int at = -1;
        for(int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).stripLeading();
            if(t.startsWith(key + "=") || t.startsWith(key + " ") || t.startsWith(key + ":")) {
                at = i;
                break;
            }
        }
        if(value == null) {
            if(at < 0)
                return;
            lines.remove(at);
        } else {
            String line = key + "=" + value.replace("\\", "\\\\");
            if(at < 0)
                lines.add(line);
            else
                lines.set(at, line);
        }
        Files.write(file, lines);
    }
}
