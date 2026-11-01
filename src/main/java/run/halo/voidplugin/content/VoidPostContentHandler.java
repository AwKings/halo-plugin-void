package run.halo.voidplugin.content;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.theme.ReactivePostContentHandler;

/**
 * 在正文渲染前套用 VOID 的扩展语法。
 *
 * <p>Halo 会把这里的返回值作为 {@code post.content.content} 输出到主题模板，
 * 因此图片排版、提示块、注音、友链与标题锚点都能在服务端完成，不依赖前端 JS。
 */
@Component
public class VoidPostContentHandler implements ReactivePostContentHandler {

    private static final Logger log = LoggerFactory.getLogger(VoidPostContentHandler.class);

    @Override
    public Mono<PostContentContext> handle(PostContentContext context) {
        try {
            String content = context.getContent();
            if (content != null && !content.isBlank()) {
                String parsed = VoidContentParser.parse(content);
                if (!parsed.equals(content)) {
                    context.setContent(parsed);
                }
            }
        } catch (Exception e) {
            // 解析失败时保留原正文，绝不因为增强语法让整页 500
            log.warn("VOID 正文增强解析失败，已跳过：{}", e.getMessage());
        }
        return Mono.just(context);
    }
}
