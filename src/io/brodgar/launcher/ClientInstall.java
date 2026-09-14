package io.brodgar.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The <code>client/</code> folder: the release zip unpacked over it, and the tag that was unpacked. Unpacking
 * overwrites what the zip carries and touches nothing else, so <code>savedata/</code> (which no release zip
 * holds) and an addon the player dropped in stay as they are; an addon the release ships is replaced by its own
 * folder in the zip.
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

    /** Where a release zip is downloaded to before it is unpacked; the download in progress is beside it as
     *  <code>download.tmp.part</code>. */
    Path download() {
        return dir.resolve("download.tmp");
    }

    /** Remove what a download left behind — the zip of an install that failed, the part of one the window was
     *  closed on — so a leftover does not stay for good. Nothing else is touched. */
    void tidy() {
        for(Path p : new Path[] {download(), dir.resolve(download().getFileName() + ".part")}) {
            try {
                Files.deleteIfExists(p);
            } catch(IOException e) {
                // in use, or read-only: the next download overwrites it anyway
            }
        }
    }

    /** The tag last installed, or null. */
    String installedVersion() {
        try {
            String v = Files.readString(dir.resolve("installed-version")).trim();
            return (v.isEmpty() || !isInstalled()) ? null : v;
        } catch(IOException e) {
            return null;
        }
    }

    /** Unpack <code>zip</code> over the folder and record <code>tag</code>. */
    void install(Path zip, String tag) throws IOException {
        Unzip.unpack(zip, dir);
        Files.writeString(dir.resolve("installed-version"), tag + System.lineSeparator());
    }
}
