package cn.datadashboard.calculation;

import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static cn.datadashboard.calculation.DashboardModels.*;

/** Local-only synthetic UI fixture. Never loads application configuration or connects to storage. */
public final class MaterialTypePreviewMain {
    public static Snapshot fixture() {
        List<Fact> facts = new ArrayList<>();
        for (int i = 1; i <= 14; i++) {
            LocalDate first = LocalDate.of(2026, 1, 1 + (i % 8));
            for (LocalDate d = first; !d.isAfter(LocalDate.of(2026, 1, 15)); d = d.plusDays(1)) {
                facts.add(row(d, "O" + i, "原创 · 场景演示 " + i, first, 25 + i * 3 + d.getDayOfMonth(), 2.6));
                facts.add(row(d, "M" + i, "混剪 · 产品展示 " + i, first, 16 + i * 2 + d.getDayOfMonth(), 2.1));
            }
        }
        for (int i = 1; i <= 4; i++) {
            LocalDate first = LocalDate.of(2026, 1, i == 4 ? 10 : i);
            for (LocalDate d = first; !d.isAfter(LocalDate.of(2026, 1, 15)); d = d.plusDays(1))
                facts.add(row(d, "X" + i, i == 1 ? "AI 产品演示" : "星图 · 实拍 " + i, first, 20 + i * 7, 1.8));
        }
        for (LocalDate d = LocalDate.of(2025, 12, 20); !d.isAfter(LocalDate.of(2026, 1, 15)); d = d.plusDays(1)) {
            facts.add(row(d, "OLD", "原创 · 周期前老素材（筛除）", LocalDate.of(2025, 12, 20), 400, 3));
            facts.add(row(d, "-2", "AIGC集合", null, 25, 1.5));
            if (!d.isBefore(LocalDate.of(2025, 12, 23))) {
                facts.add(row(d, "PO", "原创 · 上期素材", LocalDate.of(2025, 12, 23), 120, 2.3));
                facts.add(row(d, "PM", "混剪 · 上期素材", LocalDate.of(2025, 12, 23), 100, 2.0));
            }
        }
        List<DayEvidence> days = new ArrayList<>();
        for (LocalDate d = LocalDate.of(2025, 12, 20); !d.isAfter(LocalDate.of(2026, 1, 15)); d = d.plusDays(1))
            days.add(new DayEvidence(d, days.size() + 1, "synthetic-" + d, "SYNTHETIC"));
        return new Snapshot("synthetic-type-preview", List.copyOf(days), List.copyOf(facts));
    }
    private static Fact row(LocalDate d, String id, String name, LocalDate first, int spend, double roi) {
        BigDecimal money = BigDecimal.valueOf(spend);
        return new Fact(d,id,name,first,money,money.multiply(BigDecimal.valueOf(roi)),money.multiply(BigDecimal.valueOf(80)),money.multiply(BigDecimal.valueOf(2)),money.multiply(BigDecimal.valueOf(0.1)));
    }
    public static void main(String[] args) throws Exception {
        var calculator = new DashboardCalculator();
        var prepared = DashboardCalculator.prepare(fixture());
        var mapper = JsonMapper.builder().build();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",18082),0);
        server.createContext("/review", exchange -> {
            byte[] body; int status = 200;
            try {
                if (!"GET".equals(exchange.getRequestMethod()) || !"/review".equals(exchange.getRequestURI().getPath())) throw new IllegalArgumentException();
                Map<String,String> p = new HashMap<>();
                for (String pair : Objects.requireNonNull(exchange.getRequestURI().getRawQuery()).split("&")) {
                    String[] kv = pair.split("=",2);
                    p.put(kv[0],URLDecoder.decode(kv[1],StandardCharsets.UTF_8));
                }
                DateRange current = new DateRange(LocalDate.parse(p.get("currentStart")),LocalDate.parse(p.get("currentEnd")));
                DateRange previous = new DateRange(LocalDate.parse(p.get("previousStart")),LocalDate.parse(p.get("previousEnd")));
                if (java.time.temporal.ChronoUnit.DAYS.between(current.start(),current.end()) > 366 || java.time.temporal.ChronoUnit.DAYS.between(previous.start(),previous.end()) > 366) throw new IllegalArgumentException();
                int limit = Integer.parseInt(p.getOrDefault("limit","20"));
                Review r = calculator.reviewWithMaterialTypesPrepared(prepared,"synthetic-type-preview",current,previous,limit);
                body = mapper.writeValueAsBytes(new Review(r.schemaVersion(),r.projectId(),"SYNTHETIC",r.requestedAt(),r.topComparisonDisplayLimit(),r.periods(),r.metricsState(),r.comparisons(),r.newMaterials(),r.topMaterials(),r.snapshotFingerprint(),r.cohortMaterials(),r.materialTypes()));
            } catch (Exception e) {
                status = 400; body = "{\"error\":\"请检查周期日期，单个周期不得超过367天\"}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control","no-store");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin","http://127.0.0.1:5186");
            exchange.sendResponseHeaders(status,body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        System.out.println("Synthetic preview calculator listening on 127.0.0.1:18082");
    }
}
