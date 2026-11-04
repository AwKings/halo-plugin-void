package run.halo.voidplugin.content;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.content.PostContentService;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.Extension;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Watcher;

/**
 * 统计文章字数，写进文章注解 {@code void_words}，供主题卡片显示「+ N 字」。
 *
 * <p>统计口径与 Typecho 的 wordCount 一致：中文字符按字计，拉丁文按词计。
 * 插件启动时全量补算一次；之后监听文章变更，正文有改动就重算。
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
    private final java.util.concurrent.atomic.AtomicBoolean disposed =
        new java.util.concurrent.atomic.AtomicBoolean(false);

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

    /** 启动时给还没有字数的文章补算一遍。 */
    private void backfill() {
        client.list(Post.class, null, null)
            .flatMap(post -> refresh(post.getMetadata().getName())
                .onErrorResume(e -> Mono.empty()), 2)
            .doOnComplete(() -> log.info("VOID 字数统计补算完成"))
            .doOnError(e -> log.warn("VOID 字数统计补算出错：{}", e.getMessage()))
            .subscribe();
    }

    @Override
    public void onAdd(Extension extension) {
        if (extension instanceof Post post) {
            schedule(post.getMetadata().getName());
        }
    }

    @Override
    public void onUpdate(Extension oldExtension, Extension newExtension) {
        if (newExtension instanceof Post post) {
            schedule(post.getMetadata().getName());
        }
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
            .onErrorResume(e -> {
                log.debug("VOID 字数统计失败 {}：{}", name, e.getMessage());
                return Mono.empty();
            })
            .subscribe();
    }

    private Mono<Void> refresh(String name) {
        return contentService.getHeadContent(name)
            .flatMap(wrapper -> {
                String count = String.valueOf(count(wrapper.getContent()));
                return client.fetch(Post.class, name)
                    .flatMap(post -> {
                        Map<String, String> annotations = post.getMetadata().getAnnotations();
                        if (annotations == null) {
                            annotations = new HashMap<>();
                            post.getMetadata().setAnnotations(annotations);
                        }
                        if (count.equals(annotations.get(ANNO))) {
                            return Mono.empty();
                        }
                        annotations.put(ANNO, count);
                        return client.update(post).then();
                    });
            })
            .then();
    }

    /**
     * 字数统计：去标签后，中文按字计、英文按词计。
     */
    public static int count(String html) {
        if (html == null || html.isEmpty()) {
            return 0;
        }
        String text = html
            .replaceAll("(?s)<script.*?</script>", " ")
            .replaceAll("(?s)<style.*?</style>", " ")
            .replaceAll("<[^>]+>", " ")
            .replaceAll("&[a-zA-Z#0-9]+;", " ");

        int total = 0;
        boolean inLatinWord = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                total++;
                inLatinWord = false;
            } else if (Character.isLetterOrDigit(c)) {
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

    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
            || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
            || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
            || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
            || block == Character.UnicodeBlock.HIRAGANA
            || block == Character.UnicodeBlock.KATAKANA
            || block == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }
}
