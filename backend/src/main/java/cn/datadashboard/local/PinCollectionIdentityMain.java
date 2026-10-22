package cn.datadashboard.local;

import cn.datadashboard.configuration.AnchorResolver;
import cn.datadashboard.configuration.ProjectConfigurationStore;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;

/** One-time, explicit metadata upgrade. Historical successful source evidence and fresh authorized
 * lookup must agree. Does not connect to the database or change theme/advertiser/project identities. */
public final class PinCollectionIdentityMain {
    public static void main(String[] args) {
        try {
            if(args.length!=2 || !args[1].matches("[a-f0-9]{64}"))throw new IllegalArgumentException();
            Path root=Path.of("").toRealPath();
            var mapper=JsonMapper.builder().build();
            var store=new ProjectConfigurationStore(root,Path.of(args[0]));
            var before=store.snapshot();
            if(!before.revision().equals(args[1]))throw new IllegalStateException();
            var resolver=AnchorResolver.local(root);
            var projects=new ArrayList<ProjectConfigurationStore.Project>();
            for(var p:before.document().projects()) {
                if(p.publicDouyinId()==null || !p.collectionTheme().equals("QUANYU"))throw new IllegalStateException();
                Set<String> historical=new HashSet<>();
                try(var directories=Files.list(root.resolve(".local/collector-runs"))) {
                    for(Path dir:directories.toList()) {
                        Path file=dir.resolve("receipt.json");
                        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>1024*1024)continue;
                        var r=mapper.readTree(Files.readAllBytes(file));
                        if(!p.id().equals(r.path("projectRef").asText()) || !p.advertiserId().equals(r.path("advertiserId").asText())
                                || !"COLLECTED_NOT_IMPORTED".equals(r.path("status").asText()) || !r.has("anchorResolution"))continue;
                        boolean verified=true;
                        var evidence=r.path("anchorResolution").path("evidence");
                        if(!evidence.isArray() || evidence.isEmpty())continue;
                        for(JsonNode item:evidence) {
                            Path source=Path.of(item.path("absolutePath").asText());
                            if(!source.startsWith(root.resolve(".local")) || !Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)
                                    || !source.toRealPath().equals(source) || Files.size(source)!=item.path("sizeBytes").asLong()
                                    || !LocalFactVerifyMain.sha(Files.readAllBytes(source)).equals(item.path("sha256").asText())) { verified=false;break; }
                        }
                        if(verified) historical.add(r.path("anchorResolution").path("resolvedAwemeId").asText());
                    }
                }
                var current=resolver.resolve(p.advertiserId(),p.publicDouyinId());
                if(!historical.equals(Set.of(current.awemeId())) || (!p.douyinIds().isEmpty() && !p.douyinIds().equals(List.of(current.awemeId()))))
                    throw new IllegalStateException();
                projects.add(new ProjectConfigurationStore.Project(p.id(),p.name(),p.advertiserId(),List.of(current.awemeId()),
                        p.enabled(),p.publicDouyinId(),p.awemeName(),p.deleted(),p.category(),p.collectionTheme(),p.storageAccountId()));
            }
            var checked=store.preflight(before.revision(),projects);
            var applied=store.apply(before.revision(),projects,checked.candidateHash());
            System.out.println(mapper.writeValueAsString(Map.of("status","D223_STABLE_DOUYIN_IDENTITIES_PINNED",
                    "projects",projects.size(),"previousRevision",before.revision(),"revision",applied.revision(),
                    "historicalEvidenceAndLiveLookupAgree",true,"databaseConnected",false,"themesUnchanged",true)));
        } catch(Exception e) {
            System.err.println("D223_IDENTITY_PIN_STOPPED_READ_CURRENT_CONFIGURATION_BEFORE_RETRY");System.exit(2);
        }
    }
}
