package run.halo.voidplugin.content;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VOID 主题正文增强解析。
 *
 * <p>对应 Typecho 原主题 {@code libs/Contents.php} 里的四个解析器：
 * <ul>
 *   <li>{@code parsePhotoSet}：{@code [photos]…[/photos]} 图片排版 / 相册</li>
 *   <li>{@code parseNotice}：{@code [notice]…[/notice]} 提示块</li>
 *   <li>{@code parseRuby}：{@code {{汉字:注音}}} 注音</li>
 *   <li>{@code parseBoardCallback2}：{@code [links]…[/links]} 友链列表</li>
 * </ul>
 * 另外补齐原主题由 {@code parseFancyBox} / {@code parseHeader} 完成的
 * 「图片包 figure + fancybox 相册」与「h2~h6 锚点」。
 *
 * <p>所有替换都会先保护 {@code <pre>}/{@code <code>} 区域，
 * 避免把代码示例里的标记当成语法解析。
 */
public final class VoidContentParser {

    private VoidContentParser() {
    }

    private static final int FLAGS = Pattern.DOTALL | Pattern.CASE_INSENSITIVE;

    private static final Pattern PRE_BLOCK =
        Pattern.compile("<pre\\b[^>]*>.*?</pre>", FLAGS);
    private static final Pattern CODE_INLINE =
        Pattern.compile("<code\\b[^>]*>.*?</code>", FLAGS);
    private static final Pattern ANCHOR =
        Pattern.compile("<a\\b[^>]*>.*?</a>", FLAGS);
    private static final Pattern PHOTOS_BLOCK =
        Pattern.compile("(?:<p>)?\\s*\\[photos(?:[^\\]]*)?\\](.*?)\\[/photos\\]\\s*(?:</p>)?", FLAGS);
    private static final Pattern NOTICE_BLOCK =
        Pattern.compile("(?:<p>)?\\s*\\[notice(?:[^\\]]*)?\\](.*?)\\[/notice\\]\\s*(?:</p>)?", FLAGS);
    private static final Pattern RUBY =
        Pattern.compile("\\{\\{(.*?):(.*?)\\}\\}", FLAGS);
    private static final Pattern LINKS_BLOCK =
        Pattern.compile("(?:<p>)?\\s*\\[links(?:[^\\]]*)?\\](.*?)\\[/links\\]\\s*(?:</p>)?", FLAGS);
    private static final Pattern LINK_HTML_ENTRY =
        Pattern.compile("<a\\b[^>]*href\\s*=\\s*\"([^\"]*)\"[^>]*>(.*?)</a>\\s*\\+\\(([^)]*)\\)", FLAGS);
    private static final Pattern LINK_RAW_ENTRY =
        Pattern.compile("\\[(.*?)\\]\\((.*?)\\)\\s*\\+\\(([^)]*)\\)", FLAGS);
    private static final Pattern IMG =
        Pattern.compile("<img\\b[^>]*>", FLAGS);
    private static final Pattern IMG_IN_P =
        Pattern.compile("<p>(?:\\s|<br\\s*/?>)*(<img\\b[^>]*>)(?:\\s|<br\\s*/?>)*</p>", FLAGS);
    private static final Pattern HEADING =
        Pattern.compile("<h([2-6])([^>]*)>(.*?)</h\\1>", FLAGS);
    private static final Pattern SRC_ATTR =
        Pattern.compile("\\bsrc\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALT_ATTR =
        Pattern.compile("\\balt\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    /**
     * 执行全部解析。
     *
     * @param html 已渲染的正文 HTML
     * @return 处理后的 HTML
     */
    public static String parse(String html) {
        if (html == null || html.isBlank()) {
            return html;
        }
        Bag bag = new Bag();
        String s = normalizeCodeLang(html);

        // 代码区域先保护，避免误解析
        s = bag.protect(PRE_BLOCK, s);
        s = bag.protect(CODE_INLINE, s);

        s = parsePhotos(s, bag);
        s = NOTICE_BLOCK.matcher(s).replaceAll(m -> Matcher.quoteReplacement(
            "<p class=\"notice\">" + m.group(1).trim() + "</p>"));
        s = RUBY.matcher(s).replaceAll(m -> Matcher.quoteReplacement(
            "<ruby>" + m.group(1) + "<rp>(</rp><rt>" + m.group(2) + "</rt><rp>)</rp></ruby>"));
        s = parseLinks(s);

        // 已有链接与相册块保护起来，剩下的图片才需要包 figure
        s = bag.protect(ANCHOR, s);
        s = bag.protect(PHOTOS_HTML, s);
        s = parseFigures(s, bag);

        s = parseHeadings(s);
        return bag.restore(s);
    }

    private static final Pattern PHOTOS_HTML =
        Pattern.compile("<div class=\"photos(?: large)?\">.*?</div>", FLAGS);

    // ---------------------------------------------------------------- photos

    private static String parsePhotos(String html, Bag bag) {
        Matcher m = PHOTOS_BLOCK.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String body = m.group(1);
            List<String> imgs = new ArrayList<>();
            Matcher im = IMG.matcher(body);
            while (im.find()) {
                imgs.add(im.group());
            }
            if (imgs.isEmpty()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(""));
                continue;
            }
            StringBuilder block = new StringBuilder("<div class=\"photos\">");
            for (String img : imgs) {
                block.append(buildFigure(img));
            }
            block.append("</div>");
            m.appendReplacement(sb, Matcher.quoteReplacement(bag.hold(block.toString())));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String buildFigure(String imgTag) {
        String src = attr(SRC_ATTR, imgTag);
        String alt = attr(ALT_ATTR, imgTag);
        String caption = alt == null ? "" : alt;
        StringBuilder sb = new StringBuilder(256);
        sb.append("<figure><a data-fancybox=\"gallery\" data-caption=\"").append(escape(caption))
            .append("\" href=\"").append(escape(src)).append("\" class=\"void-img\">")
            .append("<img src=\"").append(escape(src)).append("\" alt=\"").append(escape(caption))
            .append("\" loading=\"lazy\"></a>");
        if (!caption.isBlank()) {
            sb.append("<figcaption>").append(escape(caption)).append("</figcaption>");
        }
        sb.append("</figure>");
        return sb.toString();
    }

    // ----------------------------------------------------------------- links

    private static String parseLinks(String html) {
        Matcher m = LINKS_BLOCK.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String body = m.group(1);
            StringBuilder items = new StringBuilder();
            Matcher h = LINK_HTML_ENTRY.matcher(body);
            while (h.find()) {
                items.append(linkItem(h.group(2), h.group(1), h.group(3)));
            }
            if (items.isEmpty()) {
                Matcher r = LINK_RAW_ENTRY.matcher(body);
                while (r.find()) {
                    items.append(linkItem(r.group(1), r.group(2), r.group(3)));
                }
            }
            String replacement = items.isEmpty() ? body
                : "<div class=\"board-list link-list\">" + items + "</div>";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String linkItem(String name, String url, String thumb) {
        return "<a target=\"_blank\" rel=\"noopener\" href=\"" + escape(url)
            + "\" class=\"board-item link-item\"><div class=\"board-thumb\" data-thumb=\""
            + escape(thumb) + "\"><img src=\"" + escape(thumb) + "\" alt=\"" + escape(stripTags(name))
            + "\" loading=\"lazy\"></div><div class=\"board-title\">" + name + "</div></a>";
    }

    private static String stripTags(String s) {
        return s == null ? "" : s.replaceAll("<[^>]+>", "").trim();
    }

    // --------------------------------------------------------------- figures

    private static String parseFigures(String html, Bag bag) {
        // 独占一段的图片：<p><img></p> → <figure>
        String s = replaceAll(IMG_IN_P, html, m -> bag.hold(buildFigure(m.group(1))));
        // 段落里的行内图片：包一层可点击的链接
        s = replaceAll(IMG, s, m -> {
            String img = m.group();
            String src = attr(SRC_ATTR, img);
            String alt = attr(ALT_ATTR, img);
            return "<a data-fancybox=\"gallery\" data-caption=\"" + escape(alt)
                + "\" href=\"" + escape(src) + "\" class=\"void-img-inline\">" + img + "</a>";
        });
        return s;
    }

    // -------------------------------------------------------------- headings

    private static String parseHeadings(String html) {
        Matcher m = HEADING.matcher(html);
        StringBuilder sb = new StringBuilder();
        int index = 0;
        while (m.find()) {
            String attrs = m.group(2);
            String replacement;
            if (attrs != null && attrs.matches("(?is).*\\bid\\s*=.*")) {
                replacement = m.group();
            } else {
                replacement = "<h" + m.group(1) + attrs + " id=\"toc_" + (index++) + "\">"
                    + m.group(3) + "</h" + m.group(1) + ">";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ----------------------------------------------------------------- utils

    private static String normalizeCodeLang(String html) {
        return html
            .replace("language-objective-c", "language-objectivec")
            .replace("language-c++", "language-cpp")
            .replace("language-c%2B%2B", "language-cpp")
            .replace("language-c#", "language-csharp")
            .replace("language-c%23", "language-csharp")
            .replace("language-f#", "language-fsharp")
            .replace("language-F#", "language-fsharp");
    }

    private static String replaceAll(Pattern pattern, String input, Function<Matcher, String> fn) {
        Matcher m = pattern.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(fn.apply(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String attr(Pattern p, String tag) {
        Matcher m = p.matcher(tag);
        return m.find() ? m.group(1) : "";
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("\"", "&quot;")
            .replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 占位符容器：把片段换成不可被正则命中的记号，最后还原。 */
    private static final class Bag {
        private final List<String> items = new ArrayList<>();

        String hold(String value) {
            items.add(value);
            return "\u0001" + (items.size() - 1) + "\u0001";
        }

        String protect(Pattern pattern, String input) {
            Matcher m = pattern.matcher(input);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(hold(m.group())));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        String restore(String input) {
            String s = input;
            for (int i = 0; i < items.size(); i++) {
                s = s.replace("\u0001" + i + "\u0001", items.get(i));
            }
            return s;
        }
    }
}
