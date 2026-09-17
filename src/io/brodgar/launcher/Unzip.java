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
 * Zip extraction over a folder: entries are written with <code>REPLACE_EXISTING</code>; existing files not in
 * the zip are kept. If every entry is under one top-level folder, that folder is stripped
 * (<code>brodgar-io-client-0.1.0/hafen.jar</code> → <code>hafen.jar</code>). An entry resolving outside the
 * target (absolute, backslash, <code>..</code>) throws <code>IOException</code>.
 */
final class Unzip {
    private Unzip() {}

    /** Extract <code>zip</code> into <code>into</code> (created if missing). */
    static void unpack(Path zip, Path into) throws IOException {
        Files.createDirectories(into);
        try(ZipFile zf = new ZipFile(zip.toFile())) {
            String strip = commonRoot(zf);
            Path root = into.toAbsolutePath().normalize();
            for(Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements();) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if(name.startsWith("/") || name.contains("\\") || Arrays.asList(name.split("/")).contains(".."))
                    throw new IOException("the zip reaches outside " + into.getFileName() + "/: " + name);
                if(!strip.isEmpty()) {
                    if(!name.startsWith(strip))
                        continue;
                    name = name.substring(strip.length());
                }
                if(name.isEmpty())
                    continue;
                Path target = root.resolve(name).normalize();
                if(!target.startsWith(root))
                    throw new IOException("the zip reaches outside " + into.getFileName() + "/: " + e.getName());
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
    }

    /** Common top-level folder of all entries, with trailing slash; "" if none. */
    private static String commonRoot(ZipFile zf) {
        String root = null;
        for(Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements();) {
            String name = en.nextElement().getName();
            int slash = name.indexOf('/');
            if(slash < 0)
                return "";                      // an entry at the root
            String top = name.substring(0, slash + 1);
            if(root == null)
                root = top;
            else if(!root.equals(top))
                return "";
        }
        return (root == null) ? "" : root;
    }
}
