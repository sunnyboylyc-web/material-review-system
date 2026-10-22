package cn.datadashboard.export;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Read-only source export; workbook staging is separate from facts and upload evidence. */
public final class CollectedExportService {
    public record Project(String id,String name,String accountId) { }
    public record Day(String statDate,int rowCount,String sourceType) {
        public Day(String statDate,int rowCount){this(statDate,rowCount,"MCP_AUTO");}
    }
    public record Excluded(String statDate,String reason) { }
    public record Source(List<JsonNode> rows,List<Day> days,List<Excluded> excludedDates) { }
    public record Preview(String schemaVersion,String projectId,String projectName,String accountId,
            String startDate,String endDate,int rowCount,List<Day> days,List<Excluded> excludedDates,String sourceType) { }
    @FunctionalInterface public interface Projects { Project resolve(String id); }
    @FunctionalInterface public interface Reader { Source read(String account,LocalDate start,LocalDate end); }
    @FunctionalInterface public interface Workbook { byte[] build(Map<String,Object> payload); }
    private final Projects projects; private final Reader reader; private final Workbook workbook;
    public CollectedExportService(Projects projects,Reader reader,Workbook workbook){this.projects=projects;this.reader=reader;this.workbook=workbook;}
    private record Selection(Preview preview,Source source) { }
    private Selection select(String id,String start,String end){
        LocalDate from,to;
        try{from=LocalDate.parse(start);to=LocalDate.parse(end);}catch(Exception e){throw new Failure("EXPORT_RANGE_INVALID");}
        check(from.getYear()>=1900&&to.getYear()<=2200&&!to.isBefore(from)&&ChronoUnit.DAYS.between(from,to)<366,"EXPORT_RANGE_INVALID");
        Project project=projects.resolve(id);check(project!=null,"EXPORT_PROJECT_UNAVAILABLE");
        Source source=reader.read(project.accountId(),from,to);check(source.rows().size()<=100000,"EXPORT_ROW_LIMIT");
        return new Selection(new Preview("1.0",project.id(),project.name(),project.accountId(),from.toString(),to.toString(),
                source.rows().size(),source.days(),source.excludedDates(),"ALL_SAVED"),source);
    }
    public Preview preview(String id,String start,String end){return select(id,start,end).preview();}
    public byte[] download(String id,String start,String end){
        Selection selection=select(id,start,end);Preview p=selection.preview();
        check(!p.days().isEmpty(),"EXPORT_NO_DATA");
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("projectId",p.projectId());payload.put("projectName",p.projectName());payload.put("accountId",p.accountId());
        payload.put("startDate",p.startDate());payload.put("endDate",p.endDate());payload.put("days",p.days());
        payload.put("excludedDates",p.excludedDates());payload.put("rows",selection.source().rows());
        payload.put("sourceType",p.sourceType());
        return workbook.build(payload);
    }
    public static Workbook nodeWorkbook(Path workspace,String node){
        return processWorkbook(workspace,node,"tools/manual_history/build_collected_export.mjs");
    }
    public static Workbook pythonWorkbook(Path workspace,String python){
        return processWorkbook(workspace,python,"tools/manual_history/export_saved_data.py");
    }
    private static Workbook processWorkbook(Path workspace,String executable,String script){return payload->{
        Path directory=null;Process process=null;
        try{
            Path root=workspace.toRealPath().resolve(".local/collected-exports");Files.createDirectories(root);
            check(!Files.isSymbolicLink(root)&&root.toRealPath().startsWith(workspace.toRealPath()),"EXPORT_STORAGE_INVALID");
            directory=Files.createTempDirectory(root,"export-");Path input=directory.resolve("input.json"),output=directory.resolve("output.xlsx");
            Files.writeString(input,JsonMapper.builder().build().writeValueAsString(payload),StandardOpenOption.CREATE_NEW);
            process=new ProcessBuilder(executable,workspace.resolve(script).toString(),input.toString(),output.toString())
                    .directory(workspace.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(!process.waitFor(120,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);throw new Failure("EXPORT_TIMEOUT");}
            check(process.exitValue()==0&&Files.isRegularFile(output)&&!Files.isSymbolicLink(output),"EXPORT_BUILD_FAILED");
            check(Files.size(output)>0&&Files.size(output)<=128L*1024*1024,"EXPORT_SIZE_INVALID");
            byte[] bytes=Files.readAllBytes(output);check(bytes.length>=4&&bytes[0]=='P'&&bytes[1]=='K',"EXPORT_BUILD_FAILED");return bytes;
        }catch(Failure e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new Failure("EXPORT_INTERRUPTED");}
        catch(Exception e){throw new Failure("EXPORT_BUILD_FAILED");}
        finally{
            if(process!=null&&process.isAlive())process.destroyForcibly();
            if(directory!=null){try{Files.deleteIfExists(directory.resolve("input.json"));Files.deleteIfExists(directory.resolve("output.xlsx"));Files.deleteIfExists(directory.resolve("output.xlsx.inspect.ndjson"));Files.deleteIfExists(directory);}catch(Exception ignored){throw new Failure("EXPORT_CLEANUP_FAILED");}}
        }
    };}
    public static void check(boolean ok,String code){if(!ok)throw new Failure(code);}
    public static final class Failure extends RuntimeException {public final String code;public Failure(String code){super(code);this.code=code;}}
}
