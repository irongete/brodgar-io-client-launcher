package io.brodgar.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

/**
 * <code>client/</code>: the release zip unpacked over it ({@link Unzip}: files in the zip replaced, others kept —
 * <code>savedata/</code>, hand-added addons) and <code>installed-version</code>. The zip includes
 * <code>haven-config.properties</code>; {@link #configure} rewrites the launcher-owned lines of it after each
 * unpack and before each start.
 *
 * <p><code>cache/</code>: the zips, one folder per channel holding the newest zip downloaded on it
 * (<code>cache/release/brodgar.io-client-v6.zip</code>), so that switching channels back installs from the zip
 * already here instead of downloading it again. {@link #cached} looks in every channel's folder;
 * {@link #cache} is where a download goes; {@link #prune} drops the channel's older zips after one.
 */
final class ClientInstall {
    private final Path dir;
    private final Path cache;

    ClientInstall(Path dir, Path cache) {
        this.dir = dir;
        this.cache = cache;
    }

    Path dir() {
        return dir;
    }

    boolean isInstalled() {
        return Files.exists(dir.resolve("hafen.jar"));
    }

    /** The zip named <code>asset</code> in any channel's cache folder; null if none. */
    Path cached(String asset) {
        for(Channel c : Channel.values()) {
            Path zip = cache.resolve(c.key).resolve(asset);
            if(Files.isRegularFile(zip))
                return zip;
        }
        return null;
    }

    /** <code>cache/&lt;channel&gt;/&lt;asset&gt;</code>, its folder made: where the channel's download goes. */
    Path cache(Channel channel, String asset) throws IOException {
        Path folder = cache.resolve(channel.key);
        Files.createDirectories(folder);
        return folder.resolve(asset);
    }

    /** Delete everything in <code>zip</code>'s folder but <code>zip</code>: the channel's older zips. */
    void prune(Path zip) {
        try(Stream<Path> files = Files.list(zip.getParent())) {
            files.filter(p -> !p.equals(zip)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch(IOException e) {
                    // locked; it goes with a later prune
                }
            });
        } catch(IOException e) {
            // the folder is unreadable: nothing to prune
        }
    }

    /** Delete the <code>.part</code> of a download that did not finish, in every channel's cache folder. */
    void tidy() {
        for(Channel c : Channel.values()) {
            Path folder = cache.resolve(c.key);
            if(!Files.isDirectory(folder))
                continue;
            try(Stream<Path> files = Files.list(folder)) {
                files.filter(p -> p.getFileName().toString().endsWith(".part")).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch(IOException e) {
                        // locked or read-only; the next download overwrites it
                    }
                });
            } catch(IOException e) {
                // unreadable: nothing to tidy
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

    /** {@link Settings#writeLine} for each entry of <code>config</code> on {@link #config()} (null value = line
     *  removed). No-op while {@link #isInstalled()} is false. */
    void configure(Map<String, String> config) throws IOException {
        if(!isInstalled())
            return;
        for(Map.Entry<String, String> e : config.entrySet())
            Settings.writeLine(config(), e.getKey(), e.getValue());
    }
}
