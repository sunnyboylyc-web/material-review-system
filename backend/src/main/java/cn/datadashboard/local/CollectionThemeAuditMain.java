package cn.datadashboard.local;

import cn.datadashboard.configuration.ProjectConfigurationStore;
import cn.datadashboard.query.JdbcDashboardSnapshotReader;
import cn.datadashboard.export.JdbcCollectedExportReader;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import tools.jackson.databind.json.JsonMapper;

/** Explicit maintenance audit: reads facts only; never collects/imports or starts the application. */
public final class CollectionThemeAuditMain {
    public static void main(String[] args) {
        try {
            if (args.length != 2) throw new IllegalArgumentException();
            var root=Path.of("").toRealPath();
            var plan=LocalFactImportMain.readDashboardPlan(Path.of(args[0]),root);
            var data=new DriverManagerDataSource(LocalFactVerifyMain.jdbcUrl(plan),plan.credentials().username(),plan.credentials().password());
            var store=new ProjectConfigurationStore(root,Path.of(args[1]));
            var snapshot=store.snapshot();
            var reader=new JdbcDashboardSnapshotReader(data);
            var exports=new JdbcCollectedExportReader(data);
            var results=new ArrayList<Map<String,Object>>();
            for (var p:snapshot.document().projects()) {
                var lineage=store.lineage(p.id());
                var jdbc=new org.springframework.jdbc.core.JdbcTemplate(data);
                long rows=0, days=0;java.time.LocalDate latest=null;
                for(String account:lineage.accounts()) {
                    days+=jdbc.queryForObject("SELECT COUNT(*) FROM scsj_fact_day_state WHERE account_id=?",Long.class,account);
                    rows+=jdbc.queryForObject("SELECT COUNT(*) FROM scsj_material_day_fact WHERE account_id=?",Long.class,account);
                    var last=jdbc.queryForObject("SELECT MAX(stat_date) FROM scsj_fact_day_state WHERE account_id=?",java.sql.Date.class,account);
                    if(last!=null && (latest==null || last.toLocalDate().isAfter(latest)))latest=last.toLocalDate();
                }
                int exported=latest==null?0:exports.readAccounts(lineage.accounts(),latest,latest).rows().size();
                results.add(Map.of("project",p.id(),"theme",p.collectionTheme(),"sourceAccounts",lineage.accounts().size(),
                        "days",days,"rows",rows,"lastDayExportRows",exported));
            }
            if(!store.snapshot().revision().equals(snapshot.revision()))throw new IllegalStateException();
            System.out.println(JsonMapper.builder().build().writeValueAsString(Map.of("status","D223_DATABASE_AND_QUERY_AUDIT_PASSED",
                    "configurationRevision",snapshot.revision(),"projects",results,"databaseWrites",false)));
        } catch(Exception failure) {
            System.err.println("D223_AUDIT_FAILED_"+failure.getClass().getSimpleName());
            for(var frame:failure.getStackTrace()) if(frame.getClassName().equals(CollectionThemeAuditMain.class.getName())) System.err.println("AUDIT_LINE_"+frame.getLineNumber());
            for(Throwable cause=failure;cause!=null;cause=cause.getCause()) if(cause instanceof java.sql.SQLException sql)System.err.println("SQL_CODE_"+sql.getErrorCode());
            System.exit(2);
        }
    }
}
