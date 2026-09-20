package io.brodgar.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub releases API client. <code>GET /repos/{repo}/releases?per_page=100</code>, unauthenticated; the
 * channel picks the highest version-shaped tag by {@link #compare}, ignoring API order. A version is dotted
 * numbers with an optional suffix: the client's and the launcher's own are <code>vN</code> for a release and
 * <code>vN.X-beta</code> for the X-th beta since it (<code>v5 &lt; v5.1-beta &lt; v5.2-beta &lt; v6</code>);
 * the older <code>vMAJOR.MINOR.PATCH[-pre]</code> tags order the same way. If the API call fails (rate limit, network), the fallback is the
 * <code>Location</code> of <code>github.com/{repo}/releases/latest</code> — GitHub's latest non-prerelease —
 * for either channel; with no such release, RELEASE gets {@link NoReleaseException}, BETA gets the API's
 * <code>IOException</code> (the redirect cannot show a prerelease). Assets:
 * <code>releases/download/{tag}/{name}</code>.
 */
final class GitHubRelease {
    private GitHubRelease() {}

    private static final Duration CONNECT = Duration.ofSeconds(10);
    /** Body read watchdog: no byte for this long fails the download (the request timeout covers headers only). */
    static final Duration STALL = Duration.ofSeconds(60);
    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern PRERELEASE = Pattern.compile("\"prerelease\"\\s*:\\s*(true|false)");
    private static final Pattern DRAFT = Pattern.compile("\"draft\"\\s*:\\s*(true|false)");
    private static final Pattern VERSION = Pattern.compile("v?(\\d+(?:\\.\\d+)*)(?:-([0-9A-Za-z.]+))?");

    /** <code>tag_name</code> and <code>prerelease</code> of one API release object. */
    record Release(String tag, boolean prerelease) {}

    /** GitHub answered and the channel has no release. Not a network failure. */
    static final class NoReleaseException extends IOException {
        private static final long serialVersionUID = 1L;
        /** Highest prerelease tag while RELEASE has nothing; null if none, or if answered via the fallback. */
        final String beta;

        NoReleaseException(String message, String beta) {
            super(message);
            this.beta = beta;
        }
    }

    /** Highest tag the channel admits, via the API; via {@link #latestTag} if the API fails. See the class
     *  comment for the no-release cases. */
    static String newestTag(String repo, Channel channel) throws IOException, InterruptedException {
        List<Release> all;
        try {
            all = releases(repo);
        } catch(IOException api) {
            try {
                return latestTag(repo);
            } catch(NoReleaseException none) {
                if(channel == Channel.BETA)
                    throw new IOException("the releases API is out of reach, and no release stands in for a beta", api);
                throw none;
            }
        }
        return newest(all, channel, repo);
    }

    /** Highest version-shaped tag the channel admits; {@link NoReleaseException} carrying the highest of all
     *  (a prerelease RELEASE skipped, or null) if none. */
    static String newest(List<Release> all, Channel channel, String repo) throws NoReleaseException {
        Release best = null, any = null;
        for(Release r : all) {
            if(!VERSION.matcher(r.tag()).matches())
                continue;
            if((any == null) || (compare(r.tag(), any.tag()) > 0))
                any = r;
            if(r.prerelease() && (channel != Channel.BETA))
                continue;
            if((best == null) || (compare(r.tag(), best.tag()) > 0))
                best = r;
        }
        if(best == null)
            throw new NoReleaseException("no " + channel.label.toLowerCase() + " published at github.com/" + repo
                                         + ((any == null) ? "" : " (" + any.tag() + " is a beta)"), (any == null) ? null : any.tag());
        return best.tag();
    }

    /** Non-draft releases of <code>owner/repo</code> from the API, at most 100. */
    static List<Release> releases(String repo) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(CONNECT).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + repo + "/releases?per_page=100"))
            .timeout(Duration.ofSeconds(20)).header("Accept", "application/vnd.github+json").GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if(res.statusCode() != 200)
            throw new IOException("HTTP " + res.statusCode() + " from the releases API of " + repo);
        return parse(res.body());
    }

    /** Regex parse of the listing: per <code>tag_name</code>, the <code>draft</code> and <code>prerelease</code>
     *  keys up to the next <code>tag_name</code>. Valid because a release object lists them before its assets,
     *  no nested object has them, and JSON escapes quotes inside strings. */
    static List<Release> parse(String json) {
        List<Release> out = new ArrayList<>();
        Matcher tag = TAG.matcher(json);
        List<int[]> spans = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        while(tag.find()) {
            spans.add(new int[] {tag.end(), json.length()});
            if(spans.size() > 1)
                spans.get(spans.size() - 2)[1] = tag.start();
            tags.add(tag.group(1));
        }
        for(int i = 0; i < tags.size(); i++) {
            String obj = json.substring(spans.get(i)[0], spans.get(i)[1]);
            Matcher d = DRAFT.matcher(obj);
            if(d.find() && d.group(1).equals("true"))
                continue;
            Matcher p = PRERELEASE.matcher(obj);
            out.add(new Release(tags.get(i), p.find() && p.group(1).equals("true")));
        }
        return out;
    }

    /** Version order: the dotted numbers component by component, a missing one counting as 0
     *  (<code>5.1 &gt; 5</code>, <code>6 &gt; 5.3</code>); at equal numbers no suffix > suffix
     *  (<code>5.1 &gt; 5.1-beta</code>); suffix identifiers dot-wise (numeric, else lexical; shorter list
     *  lower). Non-version tags lowest. */
    static int compare(String a, String b) {
        Matcher ma = VERSION.matcher(a), mb = VERSION.matcher(b);
        boolean va = ma.matches(), vb = mb.matches();
        if(!va || !vb)
            return Boolean.compare(va, vb);
        String[] da = ma.group(1).split("\\."), db = mb.group(1).split("\\.");
        for(int i = 0; i < Math.max(da.length, db.length); i++) {
            long xa = (i < da.length) ? Long.parseLong(da[i]) : 0, xb = (i < db.length) ? Long.parseLong(db[i]) : 0;
            int c = Long.compare(xa, xb);
            if(c != 0)
                return c;
        }
        String pa = ma.group(2), pb = mb.group(2);
        if((pa == null) || (pb == null))
            return Boolean.compare(pb != null, pa != null);   // no suffix outranks a suffix
        String[] ia = pa.split("\\."), ib = pb.split("\\.");
        for(int i = 0; i < Math.min(ia.length, ib.length); i++) {
            boolean na = ia[i].chars().allMatch(Character::isDigit), nb = ib[i].chars().allMatch(Character::isDigit);
            int c = (na && nb) ? Long.compare(Long.parseLong(ia[i]), Long.parseLong(ib[i]))
                  : (na != nb) ? Boolean.compare(nb, na)          // a number ranks below a word, as semver has it
                  : ia[i].compareTo(ib[i]);
            if(c != 0)
                return c;
        }
        return Integer.compare(ia.length, ib.length);
    }

    /** Tag from the <code>Location</code> of <code>github.com/{repo}/releases/latest</code> (no API). A
     *  <code>Location</code> without <code>/releases/tag/</code>, or a 200 (the releases page), means no
     *  non-prerelease exists: {@link NoReleaseException}. */
    static String latestTag(String repo) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(CONNECT).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://github.com/" + repo + "/releases/latest"))
            .timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
        String location = res.headers().firstValue("location").orElse(null);
        boolean redirect = (res.statusCode() / 100 == 3) && (location != null);
        if(redirect && location.contains("/releases/tag/"))
            return location.substring(location.lastIndexOf('/') + 1);
        if((res.statusCode() == 200) || (redirect && location.matches(".*/releases/?")))
            throw new NoReleaseException("no release published at github.com/" + repo, null);
        throw new IOException("unexpected answer from github.com/" + repo + " (HTTP " + res.statusCode() + ")");
    }

    /** True if <code>s</code> is a version the channels know: dotted numbers, an optional suffix. False for
     *  null and for <code>dev</code>, what <code>ant</code> stamps a build without <code>-Dversion</code>. */
    static boolean isVersion(String s) {
        return (s != null) && VERSION.matcher(s).matches();
    }

    /** Tag without its leading <code>v</code>. */
    static String version(String tag) {
        return tag.startsWith("v") ? tag.substring(1) : tag;
    }

    /** <code>https://github.com/{repo}/releases/download/{tag}/{asset}</code>. */
    static String assetUrl(String repo, String tag, String asset) {
        return "https://github.com/" + repo + "/releases/download/" + tag + "/" + asset;
    }

    /** Download <code>url</code> to <code>to</code>; <code>progress</code> gets the fraction done, -1 without
     *  <code>content-length</code>. Fails with <code>IOException</code> after {@link #STALL} without a byte. */
    static void download(String url, Path to, DoubleConsumer progress) throws IOException, InterruptedException {
        download(HttpRequest.newBuilder(URI.create(url)).timeout(STALL).GET().build(), to, progress);
    }

    /** The request's body to <code>to</code>, as {@link #download(String, Path, DoubleConsumer)}: the response
     *  headers, or <code>null</code> for a <code>304</code> (a conditional request: nothing is written). A body
     *  shorter than its <code>content-length</code> is a failure, not a file. */
    @SuppressWarnings("try")   // the watchdog thread closes the stream
    static java.net.http.HttpHeaders download(HttpRequest req, Path to, DoubleConsumer progress) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(CONNECT).build();
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if(res.statusCode() == 304) {
            res.body().close();
            return null;
        }
        if(res.statusCode() != 200) {
            res.body().close();
            throw new IOException("HTTP " + res.statusCode() + " for " + req.uri());
        }
        long total = res.headers().firstValueAsLong("content-length").orElse(-1);
        Path part = to.resolveSibling(to.getFileName() + ".part");
        AtomicLong done = new AtomicLong();
        AtomicBoolean stalled = new AtomicBoolean();
        try(InputStream in = res.body(); OutputStream out = Files.newOutputStream(part)) {
            // Every STALL, the watchdog looks at the count; unchanged, it closes the stream, which fails the read
            // below at once. It is interrupted when the read is done, and a daemon, so it holds nothing up.
            Thread watchdog = new Thread(() -> {
                try {
                    long seen = -1;
                    while(done.get() != seen) {
                        seen = done.get();
                        Thread.sleep(STALL.toMillis());
                    }
                    stalled.set(true);
                    in.close();
                } catch(InterruptedException | IOException e) {
                    // interrupted: the download ended
                }
            }, "download-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            try {
                byte[] buf = new byte[1 << 16];
                int n;
                while((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    long d = done.addAndGet(n);
                    progress.accept((total > 0) ? (double)d / total : -1);
                }
            } finally {
                watchdog.interrupt();
            }
        } catch(IOException e) {
            try {
                Files.deleteIfExists(part);     // no resume; ClientInstall.tidy() takes what is left
            } catch(IOException held) {
                // overwritten by the next download
            }
            throw stalled.get() ? new IOException("no data for " + STALL.toSeconds() + " seconds", e) : e;
        }
        if(total > 0 && done.get() != total) {
            try {
                Files.deleteIfExists(part);
            } catch(IOException held) {
                // overwritten by the next download
            }
            throw new IOException("the download ended at " + done.get() + " of " + total + " bytes");
        }
        Files.move(part, to, StandardCopyOption.REPLACE_EXISTING);
        progress.accept(1);
        return res.headers();
    }
}
