package cn.datadashboard.local;

import cn.datadashboard.configuration.AnchorResolver;
import cn.datadashboard.configuration.ProjectConfigurationStore;
import cn.datadashboard.query.JdbcDashboardSnapshotReader;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;

/** Explicit operator-requested addition only. Existing projects/facts are never rewritten. */
public final class AddVerifiedCollectionProjectMain {
    public static void main(String[] args) {
        try {
            if (args.length != 4) throw new IllegalArgumentException();
            var root = Path.of("").toRealPath();
            var store = new ProjectConfigurationStore(root, Path.of(args[0]));
            var before = store.snapshot();
            if (!before.revision().equals(args[1])) throw new IllegalStateException();
            var json = new JsonMapper();
            var requested = ProjectConfigurationStore.parseProjects(json.readTree(Files.readAllBytes(Path.of(args[2]))));
            if (requested.size() != 1) throw new IllegalArgumentException();
            var p = requested.getFirst();
            var resolved = AnchorResolver.local(root).resolve(p.advertiserId(), p.publicDouyinId());
            if (!p.douyinIds().equals(List.of(resolved.awemeId())) || !p.storageAccountId().equals(resolved.awemeId())
                    || before.document().projects().stream().anyMatch(old -> old.id().equals(p.id())
                    || old.douyinIds().contains(resolved.awemeId()) || Objects.equals(old.publicDouyinId(), p.publicDouyinId())))
                throw new IllegalStateException();
            var plan = LocalFactImportMain.readDashboardPlan(Path.of(args[3]), root);
            var data = new DriverManagerDataSource(LocalFactVerifyMain.jdbcUrl(plan), plan.credentials().username(), plan.credentials().password());
            if (!new JdbcDashboardSnapshotReader(data).read(p.storageAccountId()).days().isEmpty()) throw new IllegalStateException();
            var projects = new ArrayList<>(before.document().projects()); projects.add(p);
            var checked = store.preflight(before.revision(), projects);
            var applied = store.apply(before.revision(), projects, checked.candidateHash());
            if (!store.snapshot().document().projects().equals(projects)) throw new IllegalStateException();
            System.out.println(json.writeValueAsString(Map.of("status", "D223_VERIFIED_PROJECT_ADDED", "project", p.id(),
                    "enabled", p.enabled(), "theme", p.collectionTheme(), "previousProjectsUnchanged", true,
                    "databaseWrites", false, "collectionTriggered", false, "revision", applied.revision())));
        } catch (Exception failure) {
            System.err.println("D223_ADD_PROJECT_STOPPED_READ_CONFIGURATION_BEFORE_RETRY"); System.exit(2);
        }
    }
}
