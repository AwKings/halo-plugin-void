package run.halo.voidplugin;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import run.halo.voidplugin.content.ImageSizeProbe;
import run.halo.voidplugin.extension.VoidVote;

@Component
public class VoidPlugin extends BasePlugin {

    private static final Logger log = LoggerFactory.getLogger(VoidPlugin.class);

    private final SchemeManager schemeManager;
    private final Environment environment;

    public VoidPlugin(PluginContext pluginContext, SchemeManager schemeManager,
                      Environment environment) {
        super(pluginContext);
        this.schemeManager = schemeManager;
        this.environment = environment;
    }

    @Override
    public void start() {
        initImageProbe();
        schemeManager.register(VoidVote.class, indexSpecs -> {
            indexSpecs.add(IndexSpecs.<VoidVote, String>single("spec.subjectType", String.class)
                .indexFunc(vote -> vote.getSpec() == null ? null : vote.getSpec().getSubjectType()));
            indexSpecs.add(IndexSpecs.<VoidVote, String>single("spec.subjectName", String.class)
                .indexFunc(vote -> vote.getSpec() == null ? null : vote.getSpec().getSubjectName()));
            indexSpecs.add(IndexSpecs.<VoidVote, String>single("spec.action", String.class)
                .indexFunc(vote -> vote.getSpec() == null ? null : vote.getSpec().getAction()));
        });
    }

    @Override
    public void stop() {
        schemeManager.unregister(Scheme.buildFromType(VoidVote.class));
    }

    /**
     * 定位 Halo 的 attachments 目录，供相册探测本地图片尺寸。
     *
     * <p>插件运行在 Halo 进程内，读本地文件比走 HTTP 快得多；
     * 拿不到工作目录时探测会自动退回 HTTP，只是慢一点。
     */
    private void initImageProbe() {
        Path dir = null;
        try {
            String workDir = environment.getProperty("halo.work-dir");
            if (workDir != null && !workDir.isBlank()) {
                if (workDir.startsWith("~")) {
                    workDir = System.getProperty("user.home") + workDir.substring(1);
                }
                dir = Paths.get(workDir);
            } else {
                String home = System.getProperty("user.home");
                if (home != null) {
                    dir = Paths.get(home, ".halo2");
                }
            }
        } catch (Exception e) {
            log.warn("读取 Halo 工作目录失败，相册图片尺寸将走 HTTP 探测：{}", e.getMessage());
        }
        ImageSizeProbe.init(dir);
        log.info("VOID 图片尺寸探测目录：{}", dir == null ? "(未设置，仅支持绝对地址)" : dir);
    }
}
