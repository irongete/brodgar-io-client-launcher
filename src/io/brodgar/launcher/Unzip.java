package io.brodgar.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Zip extraction over a folder: entries are written with <code>REPLACE_EXISTING</code>; existing files not in
 * the zip are kept. If every entry is under one top-level folder, that folder is stripped
 * (<code>brodgar.io-client-v1/hafen.jar</code> → <code>hafen.jar</code>). An entry resolving outside the
 * target (absolute, backslash, <code>..</code>) throws <code>IOException</code>. On a file system with POSIX
 * permissions, an entry the zip marks executable ({@link #executables}) is made executable: the runtime's
 * programs and the start scripts, which <code>ZipFile</code> alone would write as plain files.
 */
final class Unzip {
    private Unzip() {}

    /** Extract <code>zip</code> into <code>into</code> (created if missing). */
    static void unpack(Path zip, Path into) throws IOException {
        Files.createDirectories(into);
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        Set<String> executables = posix ? executables(zip) : Set.of();
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
                if(executables.contains(e.getName())) {
                    Set<PosixFilePermission> perms = new HashSet<>(Files.getPosixFilePermissions(target));
                    perms.addAll(EnumSet.of(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE));
                    Files.setPosixFilePermissions(target, perms);
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

    /** The end of central directory record's signature, and a central directory header's. */
    private static final int END = 0x06054b50, HEADER = 0x02014b50;

    /**
     * The entries the zip marks executable: made on Unix (the high byte of <em>version made by</em> is 3) with
     * an execute bit in the Unix mode, the top half of the <em>external attributes</em>. They are in the central
     * directory, which <code>ZipFile</code> reads but does not show. ant's <code>zip</code> writes a Unix mode
     * for every entry (<code>filemode</code>, 644 unless set otherwise), so a zip built on Windows says it as
     * well. Empty for a zip with no Unix modes, or one past what this reads (ZIP64: over 4 GB or 65535 entries).
     */
    private static Set<String> executables(Path zip) throws IOException {
        try(FileChannel ch = FileChannel.open(zip, StandardOpenOption.READ)) {
            long size = ch.size();
            int tail = (int)Math.min(size, 22 + 0xffff);    // the record, and a comment of at most 64 KB after it
            ByteBuffer end = read(ch, size - tail, tail);
            int at = tail - 22;
            while((at >= 0) && (end.getInt(at) != END))
                at--;
            if(at < 0)
                return Set.of();
            long length = end.getInt(at + 12) & 0xffffffffL, offset = end.getInt(at + 16) & 0xffffffffL;
            if((length == 0xffffffffL) || (offset == 0xffffffffL) || (offset + length > size))
                return Set.of();
            ByteBuffer dir = read(ch, offset, (int)length);
            Set<String> found = new HashSet<>();
            for(int p = 0; (p + 46 <= length) && (dir.getInt(p) == HEADER);) {
                int host = (dir.getShort(p + 4) >> 8) & 0xff;
                int nameLength = dir.getShort(p + 28) & 0xffff, extra = dir.getShort(p + 30) & 0xffff, comment = dir.getShort(p + 32) & 0xffff;
                int mode = (dir.getInt(p + 38) >>> 16) & 0xffff;
                if((host == 3) && ((mode & 0111) != 0)) {
                    byte[] name = new byte[nameLength];
                    dir.get(p + 46, name);
                    found.add(new String(name, StandardCharsets.UTF_8));   // as ZipFile decodes them
                }
                p += 46 + nameLength + extra + comment;
            }
            return found;
        }
    }

    /** <code>length</code> bytes of <code>ch</code> from <code>position</code>, little-endian. */
    private static ByteBuffer read(FileChannel ch, long position, int length) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        while(b.hasRemaining()) {
            if(ch.read(b, position + b.position()) < 0)
                throw new IOException("the zip ends early");
        }
        return b.flip();
    }
}
