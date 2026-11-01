package run.halo.voidplugin.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 一条投票记录，对应 Typecho 插件中的 table.votes。
 * 用于文章点赞与评论赞踩，并为后台「互动」面板提供数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "void.halo.run", version = "v1alpha1", kind = "VoidVote",
    plural = "voidvotes", singular = "voidvote")
public class VoidVote extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    @Schema(name = "VoidVoteSpec")
    public static class Spec {

        /** post 或 comment */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 32)
        private String subjectType;

        /** 目标对象的 metadata.name */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 253)
        private String subjectName;

        /** up 或 down */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 8)
        private String action;

        @Schema(maxLength = 128)
        private String ip;

        @Schema(maxLength = 256)
        private String userAgent;

        /** 冗余存储，供互动列表展示，避免二次查询 */
        @Schema(maxLength = 256)
        private String title;

        @Schema(maxLength = 512)
        private String url;
    }
}
