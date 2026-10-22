package cn.datadashboard.export;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static cn.datadashboard.export.CollectedExportService.*;

/** Current successful machine and manual facts. One repeatable-read, read-only transaction per export. */
public final class JdbcCollectedExportReader implements CollectedExportService.Reader {
    private final DataSource dataSource;
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final JsonMapper PRECISE_JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    public JdbcCollectedExportReader(DataSource dataSource){this.dataSource=dataSource;}
    public Source read(String account,LocalDate start,LocalDate end){return readAccounts(List.of(account),start,end);}
    public Source readAccounts(List<String> accounts,LocalDate start,LocalDate end){
        check(!accounts.isEmpty() && new HashSet<>(accounts).size()==accounts.size(),"EXPORT_ACCOUNTS_INVALID");
        try(Connection c=dataSource.getConnection()){
            check(c.getAutoCommit(),"EXPORT_FRESH_CONNECTION_REQUIRED");
            boolean readOnly=c.isReadOnly();int isolation=c.getTransactionIsolation();
            try{
                c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
                try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT @@session.transaction_read_only")){check(r.next()&&r.getInt(1)==1,"EXPORT_READ_ONLY_REQUIRED");}
                List<JsonNode> result=new ArrayList<>();List<Day> days=new ArrayList<>();Map<LocalDate,String> sources=new HashMap<>();
                try(PreparedStatement q=c.prepareStatement("""
                    SELECT s.account_id state_account,s.stat_date state_date,s.current_input_version_id pointer_id,
                           v.id version_id,v.account_id version_account,v.stat_date version_date,v.source_type,v.disposition,v.row_count,v.content_hash,
                           f.id fact_id,f.account_id fact_account,f.stat_date fact_date,f.input_version_id fact_version,
                           f.material_id,f.source_row_number,f.raw_fact_json,f.row_sha256
                    FROM scsj_fact_day_state s
                    LEFT JOIN scsj_fact_input_version v ON v.id=s.current_input_version_id
                    LEFT JOIN scsj_material_day_fact f ON f.input_version_id=v.id
                    WHERE s.account_id IN (%s) AND s.stat_date BETWEEN ? AND ? AND s.current_input_version_id IS NOT NULL
                    ORDER BY s.stat_date,f.source_row_number
                    """.formatted(String.join(",", Collections.nCopies(accounts.size(), "?"))))){
                    int parameter=1;for(String account:accounts)q.setString(parameter++,account);q.setDate(parameter++,java.sql.Date.valueOf(start));q.setDate(parameter,java.sql.Date.valueOf(end));q.setQueryTimeout(60);
                    try(ResultSet rs=q.executeQuery()){
                        Buffer buffer=null;
                        while(rs.next()){
                            LocalDate date=rs.getDate("state_date").toLocalDate();long version=rs.getLong("version_id");String source=rs.getString("source_type");
                            String account=rs.getString("state_account");
                            check(accounts.contains(account)&&account.equals(rs.getString("version_account"))
                                &&rs.getDate("version_date")!=null&&date.equals(rs.getDate("version_date").toLocalDate())
                                &&version>0&&version==rs.getLong("pointer_id")&&"CURRENT".equals(rs.getString("disposition"))
                                &&Set.of("MCP_AUTO","MANUAL_HISTORY").contains(source),"EXPORT_IDENTITY_INVALID");
                            if(buffer==null||!buffer.date.equals(date)){
                                if(buffer!=null)buffer.finish(days,result);
                                check(sources.putIfAbsent(date,source)==null,"EXPORT_DUPLICATE_DAY");
                                buffer=new Buffer(date,version,source,rs.getInt("row_count"),rs.getString("content_hash"));
                            }
                            check(buffer.version==version&&buffer.source.equals(source)&&buffer.count==rs.getInt("row_count")
                                &&Objects.equals(buffer.hash,rs.getString("content_hash")),"EXPORT_VERSION_CHANGED");
                            if(rs.getObject("fact_id")!=null){
                                check(account.equals(rs.getString("fact_account"))&&date.equals(rs.getDate("fact_date").toLocalDate())
                                    &&version==rs.getLong("fact_version")&&rs.getInt("source_row_number")==buffer.rows.size()+1,"EXPORT_FACT_IDENTITY_INVALID");
                                String raw=rs.getString("raw_fact_json");check(raw!=null&&sha(raw).equals(rs.getString("row_sha256")),"EXPORT_HASH_MISMATCH");
                                JsonNode row=PRECISE_JSON.readTree(raw);
                                check(row.isObject()&&date.toString().equals(row.path("stat_time_day").asString())
                                    &&rs.getString("material_id").equals(row.path("material_id").asString())
                                    &&buffer.ids.add(rs.getString("material_id")),"EXPORT_RAW_IDENTITY_INVALID");
                                buffer.rows.add(row);buffer.rawRows.add(raw);check(result.size()+buffer.rows.size()<=100000,"EXPORT_ROW_LIMIT");
                            }
                        }
                        if(buffer!=null)buffer.finish(days,result);
                    }
                }
                List<Excluded> excluded=new ArrayList<>();
                for(LocalDate d=start;!d.isAfter(end);d=d.plusDays(1))if(!sources.containsKey(d))excluded.add(new Excluded(d.toString(),"MISSING"));
                return new Source(List.copyOf(result),List.copyOf(days),List.copyOf(excluded));
            }finally{c.rollback();c.setAutoCommit(true);c.setTransactionIsolation(isolation);c.setReadOnly(readOnly);}
        }catch(Failure e){throw new Failure(e.code);}catch(Exception e){throw new Failure("EXPORT_READ_FAILED");}
    }
    private static final class Buffer {
        final LocalDate date;final long version;final String source,hash;final int count;
        final List<JsonNode> rows=new ArrayList<>();final Set<String> ids=new HashSet<>();
        final List<String> rawRows=new ArrayList<>();
        Buffer(LocalDate date,long version,String source,int count,String hash){this.date=date;this.version=version;this.source=source;this.count=count;this.hash=hash;}
        void finish(List<Day> days,List<JsonNode> target){
            check(rows.size()==count&&count>=0,"EXPORT_COUNT_MISMATCH");
            String actual;
            if("MANUAL_HISTORY".equals(source))actual=sha("["+String.join(",",rawRows)+"]");
            else {
                // MCP hashes use the original importer canonicalization, independently of precise export values.
                ArrayNode array=JSON.createArrayNode();rawRows.forEach(raw->array.add(JSON.readTree(raw)));
                actual=sha(JSON.writeValueAsString(canonical(array)));
            }
            check(actual.equals(hash),"EXPORT_HASH_MISMATCH");
            days.add(new Day(date.toString(),count,source));target.addAll(rows);
        }
    }
    private static JsonNode canonical(JsonNode n){
        if(n.isObject()){ObjectNode o=JSON.createObjectNode();n.properties().stream().sorted(Map.Entry.comparingByKey()).forEach(e->o.set(e.getKey(),canonical(e.getValue())));return o;}
        if(n.isArray()){ArrayNode a=JSON.createArrayNode();n.forEach(v->a.add(canonical(v)));return a;}return n.deepCopy();
    }
    private static String sha(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new Failure("EXPORT_HASH_FAILED");}}
}
