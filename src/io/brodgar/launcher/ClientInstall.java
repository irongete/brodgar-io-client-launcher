package io.brodgar.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <code>client/</code>: the release zip unpacked over it ({@link Unzip}: files in the zip replaced, others kept —
 * <code>savedata/</code>, hand-added addons) and <code>installed-version</code>. The zip includes
 * <code>haven-config.properties</code>; {@link #configure} rewrites the launcher-owned lines of it after each
 * unpack and before each start.
 */
final class ClientInstall {
    private final Path dir;

    ClientInstall(Path dir) {
        this.dir = dir;
    }

    Path dir() {
        return dir;
    }

    boolean isInstalled() {
        return Files.exists(dir.resolve("hafen.jar"));
    }

    /** <code>client/download.tmp</code>; in progress as <code>download.tmp.part</code>. */
    Path download() {
        return dir.resolve("download.tmp");
    }

    /** Delete <code>download.tmp</code> and <code>download.tmp.part</code> if present. */
    void tidy() {
        for(Path p : new Path[] {download(), dir.resolve(download().getFileName() + ".part")}) {
            try {
                Files.deleteIfExists(p);
            } catch(IOException e) {
                // locked or read-only; the next download overwrites it
            }
        }
    }

    /** Content of <code>installed-version</code>, or null if absent, blank, or <code>hafen.jar</code> is missing. */
    String installedVersion() {
        try {
            String v = Files.readString(dir.resolve("installed-version")).trim();
            return (v.isEmpty() || !isInstalled()) ? null : v;
        } catch(IOException e) {
            return null;
        }
    }

    /** {@link Unzip#unpack} over <code>dir</code>, then write <code>installed-version</code>. */
    void install(Path zip, String tag) throws IOException {
        Unzip.unpack(zip, dir);
        Files.writeString(dir.resolve("installed-version"), tag + System.lineSeparator());
    }

    /** <code>client/haven-config.properties</code>. */
    Path config() {
        return dir.resolve("haven-config.properties");
    }

    /** {@link Settings#writeLine} for each entry of {@link #lines} on {@link #config()} (null value = line
     *  removed). No-op while {@link #isInstalled()} is false. */
    void configure(Map<String, String> settings) throws IOException {
        if(!isInstalled())
            return;
        for(Map.Entry<String, String> e : lines(settings).entrySet())
            Settings.writeLine(config(), e.getKey(), e.getValue());
    }

    /** The launcher's lines of the client's file: <code>settings</code> ({@link Settings#clientConfig}) plus
     *  <code>haven.savedatadir</code> whenever they name another addons folder — the client would otherwise
     *  keep its data beside that folder, and the launcher's promise is that it stays in <code>savedata/</code>
     *  here. Removed when no addons folder is named: the client's default is that same place. */
    Map<String, String> lines(Map<String, String> settings) {
        Map<String, String> all = new LinkedHashMap<>(settings);
        all.put("haven.savedatadir", (settings.get("haven.addondir") == null) ? null : dir.resolve("savedata").toAbsolutePath().toString());
        return all;
    }
}
