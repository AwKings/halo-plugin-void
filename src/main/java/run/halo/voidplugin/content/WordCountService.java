package run.halo.voidplugin.content;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.content.ContentWrapper;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.Extension;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Watcher;

/**
 * 统计文章字数，写进文章注解 {@code void_words}，供主题卡片、归档页显示「+ N 字」。
 *
 * <p>统计口径与主题前端（VOID.js 的 {@code postWordCount}）完全一致：
 * 去掉 HTML 标签、还原实体后，U+4E00–U+9FA5 的汉字按字计，连续的拉丁字母/数字按一个词计；
 * 中文标点（、。「」等）与假名不计入，否则卡片上的数字会比文章页大。
 *
 * <p>已发布文章按发布内容统计（读者看到的就是这个数），未发布按最新内容统计。
 * 插件启动时全量补算一次（覆盖迁移过来的历史文章），之后监听文章变更增量重算。
 */
@Component
public class WordCountService implements Watcher {

    /** 主题读取的注解键。 */
    public static final String ANNO = "void_words";

    private static final Logger log = LoggerFactory.getLogger(WordCountService.class);
    private static final long DEBOUNCE_MS = 1500L;

    private final ReactiveExtensionClient client;
    private final PostContentService contentService;
    private final Map<String, Long> lastRun = new ConcurrentHashMap<>();
    private final AtomicBoolean disposed = new AtomicBoolean(false);

    public WordCountService(ReactiveExtensionClient client, PostContentService contentService) {
        this.client = client;
        this.contentService = contentService;
    }

    @Override
    public void dispose() {
        disposed.set(true);
    }

    @PostConstruct
    public void onReady() {
        try {
            client.watch(this);
        } catch (Exception e) {
            log.warn("VOID 字数统计监听注册失败：{}", e.getMessage());
        }
        backfill();
    }

    /** 启动时把全部文章重算一遍，迁移过来的历史文章也能补上字数。 */
    private void backfill() {
        Flux.defer(() -> client.listAll(Post.class, new ListOptions(), null))
            .collectList()
            .doOnNext(all -> log.info("VOID 字数统计：扫描到 {} 篇文章", all.size()))
            .flatMapMany(Flux::fromIterable)
            .filter(this::countable)
            .flatMap(post -> refresh(post.getMetadata().getName())
                .doOnError(e -> log.warn("VOID 字数统计失败 {}：{}",
                    post.getMetadata().getName(), e.toString()))
                .onErrorResume(e -> Mono.empty()), 2)
            .doOnComplete(() -> log.info("VOID 字数统计补算完成"))
            .doOnError(e -> log.warn("VOID 字数统计补算出错：{}", e.toString()))
            .subscribe();
    }

    @Override
    public void onAdd(Extension extension) {
        if (extension instanceof Post post && countable(post)) {
            schedule(post.getMetadata().getName());
        }
    }

    @Override
    public void onUpdate(Extension oldExtension, Extension newExtension) {
        if (newExtension instanceof Post post && countable(post)) {
            schedule(post.getMetadata().getName());
        }
    }

    private boolean countable(Post post) {
        return post.getSpec() != null && !Boolean.TRUE.equals(post.getSpec().getDeleted());
    }

    /** 简单去抖：同一篇文章 1.5 秒内只算一次，避免保存时的连串事件。 */
    private void schedule(String name) {
        if (disposed.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastRun.get(name);
        if (last != null && now - last < DEBOUNCE_MS) {
            return;
        }
        lastRun.put(name, now);
        Mono.delay(Duration.ofMillis(800))
            .then(refresh(name))
            .doOnError(e -> log.warn("VOID 字数统计失败 {}：{}", name, e.toString()))
            .onErrorResume(e -> Mono.empty())
            .subscribe();
    }

    private Mono<Void> refresh(String name) {
        return client.fetch(Post.class, name)
            .flatMap(post -> contentOf(post).flatMap(html -> apply(post, count(html))));
    }

    /** 已发布取发布内容，未发布取最新内容。 */
    private Mono<String> contentOf(Post post) {
        String name = post.getMetadata().getName();
        boolean released = post.getSpec() != null && post.getSpec().getReleaseSnapshot() != null;
        Mono<ContentWrapper> mono = released
            ? contentService.getReleaseContent(name)
            : contentService.getHeadContent(name);
        return mono.map(wrapper -> wrapper.getContent() == null ? "" : wrapper.getContent());
    }

    private Mono<Void> apply(Post post, int count) {
        return write(post.getMetadata().getName(), String.valueOf(count), 1);
    }

    /**
     * 写入注解并回读校验。
     *
     * <p>Halo 内核的文章 reconciler 会按自己的副本回写 metadata，插件刚写的注解可能被它覆盖，
     * 所以写完等一下再回读，发现被覆盖就重试几次。
     */
    private Mono<Void> write(String name, String value, int attempt) {
        Mono<Void> once = client.fetch(Post.class, name)
            .flatMap(p -> {
                Map<String, String> annotations = p.getMetadata().getAnnotations();
                if (annotations == null) {
                    annotations = new HashMap<>();
                    p.getMetadata().setAnnotations(annotations);
                }
                if (value.equals(annotations.get(ANNO))) {
                    return Mono.empty();
                }
                annotations.put(ANNO, value);
                return client.update(p).then();
            });

        return once
            .then(Mono.delay(Duration.ofMillis(1200)))
            .then(client.fetch(Post.class, name))
            .flatMap(p -> {
                Map<String, String> annotations = p.getMetadata().getAnnotations();
                String now = annotations == null ? null : annotations.get(ANNO);
                if (value.equals(now)) {
                    return Mono.empty();
                }
                if (attempt >= 4) {
                    log.warn("VOID 字数统计 {} 写入 {} 次仍被覆盖（当前 {}）", name, attempt, now);
                    return Mono.empty();
                }
                return write(name, value, attempt + 1);
            });
    }

    /**
     * 字数统计：去标签、还原实体后，中文按字计、英文按词计。
     *
     * <p>与主题前端 {@code postWordCount()} 同口径：只把 U+4E00–U+9FA5 当字，
     * 中文标点、假名等不计入；连续字母数字算一个词。
     */
    public static int count(String html) {
        if (html == null || html.isEmpty()) {
            return 0;
        }
        String text = strip(html);
        int total = 0;
        boolean inLatinWord = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                total++;
                inLatinWord = false;
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                if (!inLatinWord) {
                    total++;
                    inLatinWord = true;
                }
            } else {
                inLatinWord = false;
            }
        }
        return total;
    }

    /** 去掉脚本、样式与标签，并还原 HTML 实体。 */
    private static String strip(String html) {
        String text = html
            .replaceAll("(?s)<script.*?</script>", " ")
            .replaceAll("(?s)<style.*?</style>", " ")
            .replaceAll("<[^>]+>", " ");
        return decodeEntities(text);
    }

    private static String decodeEntities(String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        String s = text
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&");
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 2 < s.length() && s.charAt(i + 1) == '#') {
                int end = s.indexOf(';', i);
                if (end > 0 && end - i <= 10) {
                    String body = s.substring(i + 2, end);
                    try {
                        int code = (body.startsWith("x") || body.startsWith("X"))
                            ? Integer.parseInt(body.substring(1), 16)
                            : Integer.parseInt(body);
                        out.appendCodePoint(code);
                        i = end;
                        continue;
                    } catch (RuntimeException ignored) {
                        // 不是合法实体，原样保留
                    }
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isCjk(char c) {
        return c >= '\u4e00' && c <= '\u9fa5';
    }
}
