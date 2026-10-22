package cn.datadashboard.api;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Resolves account identity from one server-owned configuration snapshot per request. */
public final class ProjectDirectory {
    public record Binding(String id, String name, String accountId, boolean enabled, String category) {
        public Binding(String id, String name, String accountId, boolean enabled) {
            this(id, name, accountId, enabled, "OTHER");
        }
    }
    private final Supplier<List<Binding>> bindings;
    public ProjectDirectory(Supplier<List<Binding>> bindings) { this.bindings = bindings; }
    public static ProjectDirectory single(String accountId) {
        return new ProjectDirectory(() -> List.of(new Binding("sampleproject", "示例项目", accountId, true)));
    }
    public List<Map<String, Object>> projects() {
        return bindings.get().stream().filter(Binding::enabled)
                .map(p -> Map.<String, Object>of("id", p.id(), "name", p.name(), "enabled", true, "category", p.category())).toList();
    }
    public String account(String projectId) {
        return bindings.get().stream().filter(p -> p.enabled() && p.id().equals(projectId))
                .findFirst().orElseThrow(ProjectNotEnabled::new).accountId();
    }
    public static final class ProjectNotEnabled extends RuntimeException { }
}
