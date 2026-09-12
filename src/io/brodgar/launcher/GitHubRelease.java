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
 * What the launcher asks GitHub. The releases of a repository come from the API's list — one call, no token,
 * with each release's <code>prerelease</code> flag — and the channel picks among them by version: the highest
 * <code>vMAJOR.MINOR.PATCH[-pre]</code>, a plain version above its own pre-releases, so <code>v0.1.0</code>
 * outranks <code>v0.1.0-beta.3</code>. Nothing here trusts the order GitHub lists in: a release edited long
 * after it was made would otherwise look new. Should the API be out of reach (a shared address past its
 * unauthenticated limit), the redirect <code>releases/latest</code> answers with — GitHub's own latest plain
 * release — stands in: for either channel when there is one; when there is none, the Release channel is told
 * so, while the Beta channel, which cannot tell a beta from nothing this way, is told GitHub is out of reach.
 * An asset is downloaded from the fixed <code>releases/download/&lt;tag&gt;/&lt;name&gt;</code> URL.
 */
final class GitHubRelease {
    private GitHubRelease() {}

    private static final Duration CONNECT = Duration.ofSeconds(10);
    /** How long a download may go without a byte before it is given up: the body has no timeout of its own, and
     *  a stalled one would otherwise hold the launcher in "Downloading..." for good. */
    private static final Duration STALL = Duration.ofSeconds(60);
    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern PRERELEASE = Pattern.compile("\"prerelease\"\\s*:\\s*(true|false)");
    private static final Pattern DRAFT = Pattern.compile("\"draft\"\\s*:\\s*(true|false)");
    private static final Pattern VERSION = Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.]+))?");

    /** One release as the API lists it. */
    record Release(String tag, boolean prerelease) {}

    /** GitHub answered, and the channel has nothing: not a network failure, and nothing to retry — the
     *  dropdown is the way out when the other channel has something, which {@link #beta} names. Nothing here
     *  says anything about what is installed: that is the player's disk, not GitHub's answer. */
    static final class NoReleaseException extends IOException {
        private static final long serialVersionUID = 1L;
        /** The tag the Beta channel would install while the Release channel has nothing, or null: there is no
         *  beta either, or the answer came off the fallback, which cannot see one. */
        final String beta;

        NoReleaseException(String message, String beta) {
            super(message);
            this.beta = beta;
        }
    }

    /** The tag the channel should have installed: the highest version the channel admits, or GitHub's own
     *  latest plain release when the API cannot be asked. Without one, the Release channel has nothing to install
     *  and hears so; the Beta channel might have a beta the redirect cannot show, so it hears the API is out of
     *  reach — the installed client is offered, or a retry. */
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

    /** The channel's pick among the releases listed: the highest version it admits. With none, the exception
     *  names the highest of everything, which is then a beta the Release channel skipped — or nothing at all. */
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

    /** The releases of <code>owner/repo</code>, drafts left out, as the API lists them (up to a hundred). */
    static List<Release> releases(String repo) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(CONNECT).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + repo + "/releases?per_page=100"))
            .timeout(Duration.ofSeconds(20)).header("Accept", "application/vnd.github+json").GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if(res.statusCode() != 200)
            throw new IOException("HTTP " + res.statusCode() + " from the releases API of " + repo);
        return parse(res.body());
    }

    /** The releases in an API listing. A release object carries <code>tag_name</code>, then <code>draft</code>
     *  and <code>prerelease</code>, before its assets; the keys are read in that order between one
     *  <code>tag_name</code> and the next, and no nested object (an author, an asset) carries them. A quote
     *  inside a JSON string is escaped, so the keys cannot be mistaken for text in a release's notes. */
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

    /** Version order over tags: numbers first, then a plain version above its pre-releases, then the
     *  pre-release identifiers dot by dot (numbers as numbers, words as words, the shorter one lower). A tag that
     *  is not a version is lowest. */
    static int compare(String a, String b) {
        Matcher ma = VERSION.matcher(a), mb = VERSION.matcher(b);
        boolean va = ma.matches(), vb = mb.matches();
        if(!va || !vb)
            return Boolean.compare(va, vb);
        for(int g = 1; g <= 3; g++) {
            int c = Long.compare(Long.parseLong(ma.group(g)), Long.parseLong(mb.group(g)));
            if(c != 0)
                return c;
        }
        String pa = ma.group(4), pb = mb.group(4);
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

    /** GitHub's own latest plain release of <code>owner/repo</code>, off the redirect and with no API: the
     *  fallback, and what a repository without the API in reach still answers. A repository with no plain release
     *  (none at all, or pre-releases only) has no latest: GitHub then sends the releases page itself instead of a
     *  <code>releases/tag/</code> one — a redirect to it today, the page outright once — and that is
     *  {@link NoReleaseException}, not a failure. */
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

    /** The zip a release carries: <code>&lt;prefix&gt;&lt;version&gt;.zip</code>, the version being the tag
     *  without its leading <code>v</code> — <code>brodgar-io-client-0.1.0.zip</code> under <code>v0.1.0</code>. */
    static String assetUrl(String repo, String tag, String prefix) {
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        return "https://github.com/" + repo + "/releases/download/" + tag + "/" + prefix + version + ".zip";
    }

    /** Download <code>url</code> to <code>to</code>, reporting the fraction done (or -1 while the size is unknown).
     *  The request's timeout covers the headers alone; the body is read as it comes, with a watchdog that gives
     *  the download up after {@link #STALL} without a byte, an ordinary failure to the caller. */
    @SuppressWarnings("try")   // the watchdog closes the stream on purpose, from its own thread
    static void download(String url, Path to, DoubleConsumer progress) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(CONNECT).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(STALL).GET().build();
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if(res.statusCode() != 200) {
            res.body().close();
            throw new IOException("HTTP " + res.statusCode() + " for " + url);
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
                    // the download is over, or the stream would not close: nothing left to watch
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
                Files.deleteIfExists(part);     // of no use without a resume; what a closed window leaves, tidy() takes
            } catch(IOException held) {
                // the next download truncates it
            }
            throw stalled.get() ? new IOException("no data for " + STALL.toSeconds() + " seconds", e) : e;
        }
        Files.move(part, to, StandardCopyOption.REPLACE_EXISTING);
        progress.accept(1);
    }
}
