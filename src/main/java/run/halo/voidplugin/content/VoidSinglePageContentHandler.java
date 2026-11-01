package run.halo.voidplugin.content;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.theme.ReactiveSinglePageContentHandler;

/**
 * 独立页面也套用 VOID 的扩展语法（提示块、注音、图片排版等）。
 */
@Component
public class VoidSinglePageContentHandler implements ReactiveSinglePageContentHandler {

    private static final Logger log = LoggerFactory.getLogger(VoidSinglePageContentHandler.class);

    @Override
    public Mono<SinglePageContentContext> handle(SinglePageContentContext context) {
        try {
            var content = context.getContent();
            if (content != null && !content.isBlank()) {
                var parsed = VoidContentParser.parse(content);
                if (!parsed.equals(content)) {
                    context.setContent(parsed);
                }
            }
        } catch (Exception e) {
            log.warn("VOID 页面增强解析失败，已跳过：{}", e.getMessage());
        }
        return Mono.just(context);
    }
}
