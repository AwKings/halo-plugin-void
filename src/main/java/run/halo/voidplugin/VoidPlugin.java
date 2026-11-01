package run.halo.voidplugin;

import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import run.halo.voidplugin.extension.VoidVote;

@Component
public class VoidPlugin extends BasePlugin {

    private final SchemeManager schemeManager;

    public VoidPlugin(PluginContext pluginContext, SchemeManager schemeManager) {
        super(pluginContext);
        this.schemeManager = schemeManager;
    }

    @Override
    public void start() {
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
}
