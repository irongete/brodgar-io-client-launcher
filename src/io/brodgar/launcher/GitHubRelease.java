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
import java.util.function.DoubleConsumer;

/**
 * The two things the launcher asks GitHub, both without its API: the latest release's tag, read off the
 * redirect <code>releases/latest</code> answers with, and a release asset, downloaded from the fixed
 * <code>releases/download/&lt;tag&gt;/&lt;name&gt;</code> URL. No JSON, no token, no rate limit.
 *
 * <p>The one thing this cannot see is a release marked <b>pre-release</b>: GitHub's <code>latest</code>
 * skips those, so a release the launcher should install is published as a plain release.
 */
final class GitHubRelease {
    private GitHubRelease() {}

    private static final Duration CONNECT = Duration.ofSeconds(10);

    /** The tag of the latest release of <code>owner/repo</code>, e.g. <code>v0.1.0</code>. */
    static String latestTag(String repo) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(CONNECT).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://github.com/" + repo + "/releases/latest"))
            .timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
        String location = res.headers().firstValue("location").orElse(null);
        if((res.statusCode() / 100 != 3) || (location == null) || !location.contains("/releases/tag/"))
            throw new IOException("no release found at github.com/" + repo + " (HTTP " + res.statusCode() + ")");
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
