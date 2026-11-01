package run.halo.voidplugin.endpoint;

import static org.springframework.http.MediaType.APPLICATION_JSON;

import java.util.LinkedHashMap;
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
 * Console 端「互动」面板数据接口，对应 Typecho 插件的后台互动页。
 *
 * <pre>
 * GET /apis/console.api.void.halo.run/v1alpha1/votes/activity?page=1&amp;size=20
 * </pre>
 */
@Component
@RequiredArgsConstructor
public class ActivityEndpoint implements CustomEndpoint {

    private final VoteService voteService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .GET("/activity", this::activity)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.void.halo.run", "v1alpha1");
    }

    private Mono<ServerResponse> activity(ServerRequest request) {
        var page = request.queryParam("page").map(Integer::parseInt).orElse(1);
        var size = request.queryParam("size").map(Integer::parseInt).orElse(20);
        return voteService.activity(page, size)
            .map(result -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("page", result.getPage());
                body.put("size", result.getSize());
                body.put("total", result.getTotal());
                body.put("totalPages", result.getTotalPages());
                body.put("first", result.hasPrevious());
                body.put("last", result.hasNext() == false);
                body.put("hasNext", result.hasNext());
                body.put("hasPrevious", result.hasPrevious());
                body.put("items", result.getItems());
                return body;
            })
            .flatMap(body -> ServerResponse.ok()
                .contentType(APPLICATION_JSON)
                .bodyValue(body));
    }
}
