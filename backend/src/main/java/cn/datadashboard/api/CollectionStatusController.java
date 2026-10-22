package cn.datadashboard.api;

import cn.datadashboard.operations.CollectionStatusReader;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Protected by the same operations/admin route rule as all maintenance status endpoints. */
@RestController
public final class CollectionStatusController {
    private final CollectionStatusReader reader;
    public CollectionStatusController(CollectionStatusReader reader) { this.reader = reader; }
    @GetMapping("/api/operations/collection-status")
    public Map<String, Object> read() { return reader.read(); }
}
