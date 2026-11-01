package run.halo.voidplugin.service;

import static run.halo.app.extension.index.query.Queries.and;
import static run.halo.app.extension.index.query.Queries.equal;
import static run.halo.app.extension.index.query.Queries.in;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequest;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.voidplugin.extension.VoidVote;

/**
 * 文章点赞与评论赞踩。
 *
 * <p>行为对齐 Typecho 版 VOID 插件：同一 IP 对同一目标只能投一次，且不能更改投票方向。
 * 通过「目标 + IP」派生出确定性的 metadata.name 实现幂等。
 */
@Service
@RequiredArgsConstructor
public class VoteService {

    public static final String TYPE_POST = "post";
    public static final String TYPE_COMMENT = "comment";
    public static final String ACTION_UP = "up";
    public static final String ACTION_DOWN = "down";

    private final ReactiveExtensionClient client;

    public record VoteRequest(String subjectType, String subjectName, String action,
                              String title, String url) {
    }

    /** code 语义与原插件一致：200 新增成功，302 已经投过，403 不允许改票，400 参数错误。 */
    public record VoteOutcome(int code, String msg, long up, long down) {
    }

    public Mono<VoteOutcome> vote(VoteRequest request, String ip) {
        return vote(request, ip, null);
    }

    public Mono<VoteOutcome> vote(VoteRequest request, String ip, String userAgent) {
        var type = normalizeType(request.subjectType());
        var action = normalizeAction(request.action());
        if (type == null || action == null || StringUtils.isBlank(request.subjectName())) {
            return Mono.just(new VoteOutcome(400, "invalid request", 0, 0));
        }
        var subjectName = request.subjectName().trim();
        var name = voteName(type, subjectName, ip);

        return client.fetch(VoidVote.class, name)
            .flatMap(existing -> {
                var votedAction = existing.getSpec() == null ? null : existing.getSpec().getAction();
                var code = action.equals(votedAction) ? 302 : 403;
                var msg = code == 302 ? "done" : "can't change vote";
                return counts(type, subjectName)
                    .map(c -> new VoteOutcome(code, msg, c[0], c[1]));
            })
            .switchIfEmpty(Mono.defer(
                () -> create(type, subjectName, action, request, ip, userAgent, name)));
    }

    private Mono<VoteOutcome> create(String type, String subjectName, String action,
                                     VoteRequest request, String ip, String userAgent,
                                     String name) {
        var vote = new VoidVote();
        var metadata = new Metadata();
        metadata.setName(name);
        vote.setMetadata(metadata);

        var spec = new VoidVote.Spec();
        spec.setSubjectType(type);
        spec.setSubjectName(subjectName);
        spec.setAction(action);
        spec.setIp(ip);
        spec.setUserAgent(truncate(userAgent, 256));
        spec.setTitle(truncate(request.title(), 256));
        spec.setUrl(truncate(request.url(), 512));
        vote.setSpec(spec);

        return client.create(vote)
            .then(counts(type, subjectName))
            .map(c -> new VoteOutcome(200, "done", c[0], c[1]));
    }

    public Mono<long[]> counts(String type, String subjectName) {
        return Mono.zip(
                client.countBy(VoidVote.class, options(type, subjectName, ACTION_UP)),
                client.countBy(VoidVote.class, options(type, subjectName, ACTION_DOWN)))
            .map(tuple -> new long[] {tuple.getT1(), tuple.getT2()});
    }

    /** 批量取某个目标的赞踩数，用于一次渲染一页评论。 */
    public Mono<Map<String, long[]>> stats(String subjectType, List<String> names) {
        var type = normalizeType(subjectType);
        if (type == null || names == null || names.isEmpty()) {
            return Mono.just(Map.of());
        }
        var distinct = names.stream().filter(StringUtils::isNotBlank).distinct().toList();
        var options = ListOptions.builder()
            .fieldQuery(and(equal("spec.subjectType", type),
                in("spec.subjectName", distinct.toArray())))
            .build();
        return client.listAll(VoidVote.class, options, Sort.unsorted())
            .collectList()
            .map(votes -> {
                Map<String, long[]> result = new HashMap<>();
                for (var name : distinct) {
                    result.put(name, new long[] {0, 0});
                }
                for (var vote : votes) {
                    var spec = vote.getSpec();
                    if (spec == null) {
                        continue;
                    }
                    var slot = result.get(spec.getSubjectName());
                    if (slot == null) {
                        continue;
                    }
                    if (ACTION_UP.equals(spec.getAction())) {
                        slot[0]++;
                    } else if (ACTION_DOWN.equals(spec.getAction())) {
                        slot[1]++;
                    }
                }
                return result;
            });
    }

    /** 最近互动，按时间倒序。 */
    public Mono<ListResult<VoidVote>> activity(int page, int size) {
        var pageRequest = PageRequestImpl.of(page, size,
            Sort.by(Sort.Order.desc("metadata.creationTimestamp")));
        return client.listBy(VoidVote.class, new ListOptions(), pageRequest);
    }

    /** 某一目标下的全部投票记录，用于管理端查看明细。 */
    public Mono<ListResult<VoidVote>> listBySubject(String subjectType, String subjectName,
                                                    PageRequest pageRequest) {
        var options = ListOptions.builder()
            .fieldQuery(and(equal("spec.subjectType", normalizeType(subjectType)),
                equal("spec.subjectName", subjectName)))
            .build();
        return client.listBy(VoidVote.class, options, pageRequest);
    }

    public Mono<Long> countAll() {
        return client.countBy(VoidVote.class, new ListOptions());
    }

    private ListOptions options(String type, String subjectName, String action) {
        return ListOptions.builder()
            .fieldQuery(and(equal("spec.subjectType", type),
                equal("spec.subjectName", subjectName),
                equal("spec.action", action)))
            .build();
    }

    private static String normalizeType(String type) {
        if (type == null) {
            return null;
        }
        return switch (type.trim().toLowerCase()) {
            case TYPE_POST, "posts", "content" -> TYPE_POST;
            case TYPE_COMMENT, "comments" -> TYPE_COMMENT;
            default -> null;
        };
    }

    private static String normalizeAction(String action) {
        if (action == null) {
            return null;
        }
        return switch (action.trim().toLowerCase()) {
            case ACTION_UP, "like" -> ACTION_UP;
            case ACTION_DOWN, "dislike" -> ACTION_DOWN;
            default -> null;
        };
    }

    private static String voteName(String type, String subjectName, String ip) {
        var raw = type + '|' + subjectName + '|' + (ip == null ? "" : ip);
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8));
            return "v-" + HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 供端点做批量查询时复用。 */
    public static List<String> splitNames(String raw) {
        if (StringUtils.isBlank(raw)) {
            return new ArrayList<>();
        }
        return List.of(raw.split(","));
    }
}
