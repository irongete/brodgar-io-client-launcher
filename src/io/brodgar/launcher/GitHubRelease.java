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
 * release — stands in for either channel. An asset is downloaded from the fixed
 * <code>releases/download/&lt;tag&gt;/&lt;name&gt;</code> URL.
 */
final class GitHubRelease {
    private GitHubRelease() {}

    private static final Duration CONNECT = Duration.ofSeconds(10);
    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern PRERELEASE = Pattern.compile("\"prerelease\"\\s*:\\s*(true|false)");
    private static final Pattern DRAFT = Pattern.compile("\"draft\"\\s*:\\s*(true|false)");
    private static final Pattern VERSION = Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.]+))?");

    /** One release as the API lists it. */
    record Release(String tag, boolean prerelease) {}

    /** GitHub answered, and the channel has nothing: not a network failure, and nothing to retry — the
     *  dropdown is the way out. */
    static final class NoReleaseException extends IOException {
        private static final long serialVersionUID = 1L;
        NoReleaseException(String message) {
            super(message);
        }
    }

    /** The tag the channel should have installed: the highest version the channel admits, or GitHub's own
     *  latest plain release when the API cannot be asked. */
    static String newestTag(String repo, Channel channel) throws IOException, InterruptedException {
        List<Release> all;
        try {
            all = releases(repo);
        } catch(IOException e) {
            return latestTag(repo);
        }
        Release best = null;
        for(Release r : all) {
            if(r.prerelease() && (channel != Channel.BETA))
                continue;
            if(!VERSION.matcher(r.tag()).matches())
                continue;
            if((best == null) || (compare(r.tag(), best.tag()) > 0))
                best = r;
        }
        if(best == null)
            throw new NoReleaseException("no " + channel.label.toLowerCase() + " published at github.com/" + repo);
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
     *  fallback, and what a repository without the API in reach still answers. */
    static String latestTag(String repo) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(CONNECT).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://github.com/" + repo + "/releases/latest"))
            .timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
        String location = res.headers().firstValue("location").orElse(null);
        if(res.statusCode() == 200)
            throw new NoReleaseException("no release published at github.com/" + repo);   // the releases page itself, no redirect
        if((res.statusCode() / 100 != 3) || (location == null) || !location.contains("/releases/tag/"))
            throw new IOException("unexpected answer from github.com/" + repo + " (HTTP " + res.statusCode() + ")");
        return location.substring(location.lastIndexOf('/') + 1);
    }

    /** The zip a release carries: <code>&lt;prefix&gt;&lt;version&gt;.zip</code>, the version being the tag
     *  without its leading <code>v</code> — <code>brodgar-io-client-0.1.0.zip</code> under <code>v0.1.0</code>. */
    static String assetUrl(String repo, String tag, String prefix) {
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        return "https://github.com/" + repo + "/releases/download/" + tag + "/" + prefix + version + ".zip";
    }

    /** Download <code>url</code> to <code>to</code>, reporting the fraction done (or -1 while the size is unknown). */
    static void download(String url, Path to, DoubleConsumer progress) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(CONNECT).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(30)).GET().build();
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if(res.statusCode() != 200) {
            res.body().close();
            throw new IOException("HTTP " + res.statusCode() + " for " + url);
        }
        long total = res.headers().firstValueAsLong("content-length").orElse(-1);
        Path part = to.resolveSibling(to.getFileName() + ".part");
        try(InputStream in = res.body(); OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            while((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                progress.accept((total > 0) ? (double)done / total : -1);
            }
        }
        Files.move(part, to, StandardCopyOption.REPLACE_EXISTING);
        progress.accept(1);
    }
}
