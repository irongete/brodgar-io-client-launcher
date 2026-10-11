package io.brodgar.launcher;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.function.DoubleConsumer;

/**
 * The brodgar.io resource pack: <code>brodgar-res.jar</code> beside <code>hafen.jar</code>, every resource
 * at the version the cache held when it was packed, so a fresh client has them all from its first start
 * and downloads only what the pack has not got. <code>hafen.jar</code>'s <code>Class-Path</code> names the
 * jar; absent, the client runs as before.
 *
 * <p>One URL (<code>resource.pack.url</code>, <code>/res/?jar</code> on the cache's server) answers a GET
 * with the jar, <code>Last-Modified</code> and no caching, and a GET carrying <code>If-Modified-Since</code>
 * with <code>304</code> when nothing is newer. The launcher asks on every start; the installed jar's
 * modification time is the date of the pack it holds, the server's <code>Last-Modified</code>, and it is what
 * every request asks about: only a pack the server dates later is downloaded.
 * The pack rebuilds only when the cache changed, so its date only moves with new content.
 */
final class ResourcePack {
    private ResourcePack() {}

    static final String JAR = "brodgar-res.jar";
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;

    /** What {@link #sync} found. */
    enum Outcome { DOWNLOADED, UNCHANGED }

    /** Bring <code>dir/brodgar-res.jar</code> up to date: <code>asking</code> runs and a conditional GET follows,
     *  <code>304</code> leaves the jar as it is, <code>200</code>
     *  replaces the jar and dates it as the server does. A failed download leaves the installed jar as it was. */
    static Outcome sync(String url, Path dir, Runnable asking, DoubleConsumer progress) throws IOException, InterruptedException {
        Path jar = dir.resolve(JAR);
        FileTime have = Files.exists(jar) ? Files.getLastModifiedTime(jar) : null;
        asking.run();
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(GitHubRelease.STALL).GET();
        if(have != null)
            req.header("If-Modified-Since", HTTP_DATE.format(have.toInstant().atZone(ZoneOffset.UTC)));
        HttpHeaders headers = GitHubRelease.download(req.build(), jar, progress);
        if(headers == null)
            return Outcome.UNCHANGED;
        Instant dated = Instant.now();
        try {
            String lm = headers.firstValue("last-modified").orElse(null);
            if(lm != null)
                dated = Instant.from(HTTP_DATE.parse(lm));
        } catch(DateTimeParseException e) {
            // an unreadable date: the download time will do
        }
        Files.setLastModifiedTime(jar, FileTime.from(dated));
        return Outcome.DOWNLOADED;
    }
}
