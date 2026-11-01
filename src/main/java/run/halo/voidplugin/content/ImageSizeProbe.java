package run.halo.voidplugin.content;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * 探测图片原始宽高，用于相册的 justified 排版。
 *
 * <p>原主题由 Typecho 插件在上传时把宽高写进图片 URL 的 hash
 * （{@code #vwid=4032&vhei=3024}），主题再据此计算 {@code padding-top}
 * 与 {@code flex-grow}。Halo 侧没有这个钩子，因此改为渲染时探测：
 *
 * <ol>
 *   <li>URL 里已经带 {@code vwid/vhei} 的，直接用；</li>
 *   <li>Halo 本地上传的图片（{@code /upload/**}），从工作目录的
 *       {@code attachments} 里读文件头；</li>
 *   <li>其余走一次 HTTP，只解码头信息，不下载完整图片。</li>
 * </ol>
 *
 * <p>结果按 URL 缓存（失败也缓存），避免每次渲染都重新探测。
 */
public final class ImageSizeProbe {

    private static final Pattern VWID = Pattern.compile("vwid=(\\d{1,6})", Pattern.CASE_INSENSITIVE);
    private static final Pattern VHEI = Pattern.compile("vhei=(\\d{1,6})", Pattern.CASE_INSENSITIVE);

    /** 缓存：URL → {宽, 高}；探测失败存 {@link #FAILED} 作为负缓存。 */
    private static final Map<String, int[]> CACHE = new ConcurrentHashMap<>();
    private static final int[] FAILED = new int[] {0, 0};
    private static final int MAX_CACHE = 2000;

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    /** Halo 工作目录下的 attachments 目录，未初始化时为 null。 */
    private static volatile Path attachmentsDir;

    private ImageSizeProbe() {
    }

    /**
     * 初始化本地图片目录。
     *
     * @param workDir Halo 的工作目录（例如 {@code ~/.halo2}），可为 null
     */
    public static void init(Path workDir) {
        if (workDir != null) {
            attachmentsDir = workDir.resolve("attachments");
        }
    }

    /**
     * 取图片宽高。
     *
     * @param url 图片地址，可带 {@code #vwid=&vhei=} 片段
     * @return {@code {宽, 高}}，探测失败返回 null
     */
    public static int[] size(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        int[] fromHash = fromHash(url);
        if (fromHash != null) {
            return fromHash;
        }
        int[] cached = CACHE.get(url);
        if (cached != null) {
            return cached == FAILED ? null : cached;
        }
        int[] probed = probe(url);
        if (CACHE.size() < MAX_CACHE) {
            CACHE.put(url, probed == null ? FAILED : probed);
        }
        return probed;
    }

    private static int[] fromHash(String url) {
        Matcher w = VWID.matcher(url);
        Matcher h = VHEI.matcher(url);
        if (w.find() && h.find()) {
            int width = Integer.parseInt(w.group(1));
            int height = Integer.parseInt(h.group(1));
            if (width > 0 && height > 0) {
                return new int[] {width, height};
            }
        }
        return null;
    }

    private static int[] probe(String url) {
        String clean = url.split("#", 2)[0];
        try (InputStream in = open(clean)) {
            if (in == null) {
                return null;
            }
            try (ImageInputStream iis = ImageIO.createImageInputStream(in)) {
                if (iis == null) {
                    return null;
                }
                Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
                if (!readers.hasNext()) {
                    return null;
                }
                ImageReader reader = readers.next();
                try {
                    // 只读头部，不解码像素
                    reader.setInput(iis, true, true);
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    return width > 0 && height > 0 ? new int[] {width, height} : null;
                } finally {
                    reader.dispose();
                }
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static InputStream open(String url) throws Exception {
        // 1. Halo 本地上传：/upload/2026/11/x.jpg → <workDir>/attachments/upload/2026/11/x.jpg
        Path dir = attachmentsDir;
        if (dir != null && url.startsWith("/upload/")) {
            Path file = dir.resolve(url.substring(1)).normalize();
            if (file.startsWith(dir) && Files.isReadable(file)) {
                return Files.newInputStream(file);
            }
        }
        // 2. 绝对地址：取一次 HTTP
        if (url.startsWith("http://") || url.startsWith("https://")) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "Mozilla/5.0 (compatible; halo-plugin-void)")
                .GET()
                .build();
            HttpResponse<InputStream> response =
                HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                response.body().close();
                return null;
            }
            return response.body();
        }
        return null;
    }
}
