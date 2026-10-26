package cn.datadashboard.local;

import cn.datadashboard.configuration.ProjectConfigurationStore;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

/** Explicit one-time initialization from a private project-only seed; no credentials or network. */
public final class LocalConfigurationMain {
    private LocalConfigurationMain() { }
    public static void main(String[] args) {
        try {
            if (args.length != 3 || !"--initialize".equals(args[2])) throw new IllegalArgumentException();
            Path workspace = Path.of("").toRealPath();
            if (!Files.isDirectory(workspace.resolve("backend"))) throw new IllegalArgumentException();
            Path local = workspace.resolve(".local").toRealPath();
            if (!local.startsWith(workspace)) throw new IllegalArgumentException();
            Path seed = Path.of(args[1]).toRealPath();
            if (!seed.startsWith(local) || !Files.isRegularFile(seed) || Files.size(seed) > 65536)
                throw new IllegalArgumentException();
            var mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
            var projects = ProjectConfigurationStore.parseProjects(mapper.readTree(Files.readAllBytes(seed)));
            var store = new ProjectConfigurationStore(workspace, Path.of(args[0]));
            var snapshot = store.initialize(projects);
            if (!store.snapshot().revision().equals(snapshot.revision())) throw new IllegalStateException();
            System.out.println("CONFIGURATION_INITIALIZED_VERIFIED");
            System.out.println("Revision: " + snapshot.revision());
        } catch (Exception failure) {
            System.err.println("CONFIGURATION_INITIALIZATION_REJECTED");
            System.exit(2);
        }
    }
}
