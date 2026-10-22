package cn.datadashboard.security;

import cn.datadashboard.api.ProjectDirectory;
import java.nio.file.*;
import java.time.LocalDate;
import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Account-owned UI preferences only; never stores query results or modifies business facts. */
@RestController
public final class PeriodPreferenceController {
    public record Period(String currentStart, String currentEnd, String previousStart, String previousEnd) { }
    private final Path root;
    private final ProjectDirectory projects;
    private final tools.jackson.databind.json.JsonMapper json = new tools.jackson.databind.json.JsonMapper();
    public PeriodPreferenceController(Path root, ProjectDirectory projects) { this.root = root; this.projects = projects; }
    private Path path(Principal principal, String project) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        try { projects.account(project); } catch (ProjectDirectory.ProjectNotEnabled e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
        return root.resolve(PrivateUserFiles.id(principal.getName()) + "-" + PrivateUserFiles.id(project) + ".json");
    }
    @GetMapping("/api/preferences/projects/{projectId}/period")
    public synchronized ResponseEntity<Period> get(Principal principal, @PathVariable String projectId) {
        Path file = path(principal, projectId);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return ResponseEntity.noContent().build();
        try { Period period = json.readValue(PrivateUserFiles.read(file), Period.class); validate(period); return ResponseEntity.ok(period); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "PREFERENCES_UNAVAILABLE"); }
    }
    @PutMapping("/api/preferences/projects/{projectId}/period")
    public synchronized Period put(Principal principal, @PathVariable String projectId, @RequestBody Period period) {
        Path file = path(principal, projectId);
        validate(period);
        try { PrivateUserFiles.write(file, json.writeValueAsString(period)); return period; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "PREFERENCES_UNAVAILABLE"); }
    }
    private static void validate(Period p) {
        try {
            if (p == null) throw new IllegalArgumentException();
            LocalDate a = date(p.currentStart()), b = date(p.currentEnd()), c = date(p.previousStart()), d = date(p.previousEnd());
            if (a.isAfter(b) || c.isAfter(d)) throw new IllegalArgumentException();
        } catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PREFERENCE_DATES_INVALID"); }
    }
    private static LocalDate date(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
        return LocalDate.parse(value);
    }
}
