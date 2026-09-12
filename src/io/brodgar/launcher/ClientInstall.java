package io.brodgar.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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

    /** Unpack <code>zip</code> over the folder and record <code>tag</code>. A zip whose every entry sits under one
     *  top-level folder is unpacked from inside it, so <code>brodgar-io-client-0.1.0/hafen.jar</code> and a bare
     *  <code>hafen.jar</code> land in the same place. */
    void install(Path zip, String tag) throws IOException {
        Files.createDirectories(dir);
        try(ZipFile zf = new ZipFile(zip.toFile())) {
            String strip = commonRoot(zf);
            Path root = dir.toAbsolutePath().normalize();
            for(Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements();) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if(name.startsWith("/") || name.contains("\\") || Arrays.asList(name.split("/")).contains(".."))
                    throw new IOException("the zip reaches outside client/: " + name);
                if(!strip.isEmpty()) {
                    if(!name.startsWith(strip))
                        continue;
                    name = name.substring(strip.length());
                }
                if(name.isEmpty())
                    continue;
                Path target = root.resolve(name).normalize();
                if(!target.startsWith(root))
                    throw new IOException("the zip reaches outside client/: " + e.getName());
                if(e.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try(InputStream in = zf.getInputStream(e)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Files.writeString(dir.resolve("installed-version"), tag + System.lineSeparator());
    }

    /** The one folder every entry is under (with its trailing slash), or "" when the entries sit at the root. */
    private static String commonRoot(ZipFile zf) {
        String root = null;
        for(Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements();) {
            String name = en.nextElement().getName();
            int slash = name.indexOf('/');
            if(slash < 0)
                return "";                      // a file at the root: nothing to strip
            String top = name.substring(0, slash + 1);
            if(root == null)
                root = top;
            else if(!root.equals(top))
                return "";
        }
        return (root == null) ? "" : root;
    }
}
