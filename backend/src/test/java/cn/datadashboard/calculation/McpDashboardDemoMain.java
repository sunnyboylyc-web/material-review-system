package cn.datadashboard.calculation;

import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import static cn.datadashboard.calculation.DashboardModels.*;

/** Pure in-memory MCP integration source: real calculator, fictional facts, no Spring/SQL/config. */
public final class McpDashboardDemoMain {
    public static void main(String[] args) throws Exception {
        int port=args.length==0?24571:Integer.parseInt(args[0]);
        var prepared=DashboardCalculator.prepare(MaterialTypePreviewMain.filterFixture());
        var calculator=new DashboardCalculator();var mapper=JsonMapper.builder().build();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
        server.createContext("/api/",exchange->{
            int status=200;Object result;
            try {
                if(!"GET".equals(exchange.getRequestMethod()))throw new IllegalArgumentException();
                Map<String,String> p=new HashMap<>();String raw=exchange.getRequestURI().getRawQuery();
                if(raw!=null)for(String pair:raw.split("&")){String[] kv=pair.split("=",2);if(kv.length==2)p.put(kv[0],URLDecoder.decode(kv[1],StandardCharsets.UTF_8));}
                String path=exchange.getRequestURI().getPath();
                if("/api/projects".equals(path))result=List.of(Map.of("id","demo-project","name","纯虚构MCP联调项目","enabled",true,"category","OTHER"));
                else if("/api/projects/demo-project/freshness".equals(path))result=Map.of("schemaVersion","freshness-1.0.0","projectId","demo-project","latestMcpStatDate","2026-01-15");
                else if("/api/projects/demo-project/top".equals(path))result=calculator.topPrepared(prepared,"demo-project",LocalDate.parse(p.get("date")),Integer.parseInt(p.getOrDefault("limit","10")));
                else {
                    var current=new DateRange(LocalDate.parse(p.get("currentStart")),LocalDate.parse(p.get("currentEnd")));
                    var previous=new DateRange(LocalDate.parse(p.get("previousStart")),LocalDate.parse(p.get("previousEnd")));
                    result=switch(path){
                        case "/api/projects/demo-project/review"->calculator.reviewWithEnhancedMaterialTypesPrepared(prepared,"demo-project",current,previous,Integer.parseInt(p.getOrDefault("limit","10")));
                        case "/api/projects/demo-project/review-materials"->calculator.reviewMaterialsPrepared(prepared,"demo-project",current,previous);
                        case "/api/projects/demo-project/material-types"->new MaterialTypeAnalysis("material-type-analysis-1.0.0","demo-project","SYNTHETIC",java.time.Instant.now().toString(),prepared.fingerprint(),calculator.materialTypesPrepared(prepared,"demo-project",current,new DateRange(LocalDate.parse(p.get("currentTestStart")),LocalDate.parse(p.get("currentTestEnd"))),previous,new DateRange(LocalDate.parse(p.get("previousTestStart")),LocalDate.parse(p.get("previousTestEnd")))));
                        default->throw new IllegalArgumentException();
                    };
                }
            }catch(Exception e){status=400;result=Map.of("code","INVALID_SYNTHETIC_QUERY");}
            byte[] body=mapper.writeValueAsString(result).replace("\"evidenceKind\":\"CURRENT_FACTS\"","\"evidenceKind\":\"SYNTHETIC\"").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control","no-store");
            exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();System.err.println("MCP synthetic source ready on loopback; no database or credentials.");
    }
}
