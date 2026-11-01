package run.halo.voidplugin.endpoint;

import static org.springframework.http.MediaType.APPLICATION_JSON;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.voidplugin.service.VoteService;

/**
 * 面向主题（匿名访客）的投票接口。
 *
 * <pre>
 * POST /apis/api.void.halo.run/v1alpha1/votes        {"subjectType":"post|comment","subjectName":"…","action":"up|down"}
 * GET  /apis/api.void.halo.run/v1alpha1/votes/stats  ?subjectType=comment&amp;names=a,b,c
 * </pre>
 */
@Component
@RequiredArgsConstructor
public class VoteEndpoint implements CustomEndpoint {

    private final VoteService voteService;

    public record VoteBody(String subjectType, String subjectName, String action,
                           String title, String url) {
    }

    public record VoteView(String name, long up, long down) {
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .POST("/votes", this::vote)
            .GET("/vote-stats", this::stats)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("api.void.halo.run", "v1alpha1");
    }

    private Mono<ServerResponse> vote(ServerRequest request) {
        return request.bodyToMono(VoteBody.class)
            .flatMap(body -> {
                var voteRequest = new VoteService.VoteRequest(body.subjectType(),
                    body.subjectName(), body.action(), body.title(), body.url());
                return voteService.vote(voteRequest, clientIp(request),
                    request.headers().firstHeader("User-Agent"));
            })
            .flatMap(outcome -> ServerResponse.ok()
                .contentType(APPLICATION_JSON)
                .bodyValue(outcome));
    }

    private Mono<ServerResponse> stats(ServerRequest request) {
        var subjectType = request.queryParam("subjectType").orElse(VoteService.TYPE_COMMENT);
        var names = VoteService.splitNames(request.queryParam("names").orElse(""));
        return voteService.stats(subjectType, names)
            .map(result -> {
                List<VoteView> items = result.entrySet().stream()
                    .map(entry -> new VoteView(entry.getKey(), entry.getValue()[0],
                        entry.getValue()[1]))
                    .toList();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("items", items);
                return body;
            })
            .flatMap(body -> ServerResponse.ok()
                .contentType(APPLICATION_JSON)
                .bodyValue(body));
    }

    static String clientIp(ServerRequest request) {
        var forwarded = request.headers().firstHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        var realIp = request.headers().firstHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.remoteAddress()
            .map(VoteEndpoint::hostOf)
            .orElse("");
    }

    private static String hostOf(InetSocketAddress address) {
        var resolved = address.getAddress();
        return resolved != null ? resolved.getHostAddress() : address.getHostString();
    }
}
