package cn.datadashboard.local;

import cn.datadashboard.api.DashboardController;
import cn.datadashboard.api.ProjectDirectory;
import cn.datadashboard.configuration.ProjectConfigurationStore;
import cn.datadashboard.query.JdbcDashboardSnapshotReader;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/** Temporary, TLS loopback, admin-only query probe. No component scan or write controllers. */
public final class RecoveryDashboardMain {
    public static final String SCHEMA = "SCSJ_RESCUE_VERIFY_20261016";
    public static final String DB_USER = "scsj_recovery_read";
    private RecoveryDashboardMain() { }

    static void validateHash(String hash) {
        if (hash == null || !hash.matches("\\$2[aby]\\$10\\$[./A-Za-z0-9]{53}"))
            throw new IllegalArgumentException("RECOVERY_HASH_INVALID");
    }

    public static void main(String[] args) {
        try {
            boolean web = args.length == 1 && "--web".equals(args[0]);
            if (args.length != 0 && !web) throw new IllegalArgumentException();
            Path root = Path.of("").toRealPath();
            if (!root.equals(Path.of("/opt/scsj-recovery-d143"))) throw new IllegalArgumentException();
            Path runtime = root.resolve(".local/recovery-runtime").toRealPath();
            if (!runtime.startsWith(root)) throw new IllegalArgumentException();
            var json = new JsonMapper().readTree(runtime.resolve("runtime.private.json").toFile());
            String hash = json.get("adminHash").asString();
            validateHash(hash);
            String password = json.get("dbPassword").asString();
            if (!password.matches("[a-f0-9]{48}")) throw new IllegalArgumentException();
            Path configuration = root.resolve(json.get("configuration").asString()).toRealPath();
            if (!configuration.startsWith(root.resolve(".local"))) throw new IllegalArgumentException();
            var store = new ProjectConfigurationStore(root, configuration);
            var snapshot = store.snapshot();
            var directory = new ProjectDirectory(() -> snapshot.document().projects().stream()
                    .map(p -> new ProjectDirectory.Binding(p.id(), p.name(), p.advertiserId(),
                            p.enabled() && !p.deleted(), p.category())).toList());
            var source = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/" + SCHEMA
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC", DB_USER, password);
            // Only the already restored verification database; no Flyway/import paths exist here.
            try (var connection = source.getConnection()) {
                connection.setReadOnly(true);
                try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT DATABASE()")) {
                    if (!result.next() || !SCHEMA.equals(result.getString(1))) throw new IllegalArgumentException();
                }
            }
            var app = new SpringApplication(web ? RecoveryWebConfiguration.class : RecoveryConfiguration.class);
            app.setLogStartupInfo(false);
            app.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
            app.setDefaultProperties(Map.of("logging.level.root", "OFF"));
            app.addInitializers(context -> {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("recovery-fixed", Map.ofEntries(
                        Map.entry("server.address", "127.0.0.1"), Map.entry("server.port", "18443"),
                        Map.entry("server.ssl.enabled", "true"), Map.entry("server.ssl.key-store-type", "PKCS12"),
                        Map.entry("server.ssl.key-store", runtime.resolve("tls.p12").toUri().toString()),
                        Map.entry("server.ssl.key-store-password", json.get("tlsPassword").asString()),
                        Map.entry("spring.flyway.enabled", "false"), Map.entry("spring.sql.init.mode", "never"),
                        Map.entry("spring.web.resources.add-mappings", Boolean.toString(web)),
                        Map.entry("spring.web.resources.static-locations", root.resolve("frontend/recovery-d144").toUri().toString()),
                        Map.entry("server.servlet.session.cookie.name", "SCSJ_RECOVERY_SESSION"),
                        Map.entry("server.servlet.session.cookie.secure", "true"),
                        Map.entry("server.servlet.session.cookie.http-only", "true"),
                        Map.entry("server.servlet.session.cookie.same-site", "lax"),
                        Map.entry("server.servlet.session.timeout", "30m"),
                        Map.entry("server.error.include-message", "never"), Map.entry("server.error.include-stacktrace", "never"),
                        Map.entry("logging.level.root", "OFF"))));
                context.getBeanFactory().registerSingleton("dataSource", source);
                context.getBeanFactory().registerSingleton("recoveryUsers", users(hash));
                context.getBeanFactory().registerSingleton("dashboardController",
                        new DashboardController(directory, new JdbcDashboardSnapshotReader(source)::read));
                if (web) {
                    context.getBeanFactory().registerSingleton("configurationController",
                            new cn.datadashboard.api.ConfigurationController(store, false,
                                    new cn.datadashboard.configuration.AnchorResolver((a,p,n) -> { throw new IllegalStateException("RECOVERY_READ_ONLY"); }),
                                    new cn.datadashboard.security.TrialRequestGuard("https://dashboard.example.com")));
                }
            });
            app.run();
            System.out.println("RECOVERY_READ_ONLY_API_READY_CONFIGURATION_UNCONFIRMED");
        } catch (Exception failure) {
            System.err.println("RECOVERY_START_REJECTED");
            System.exit(2);
        }
    }

    static UserDetailsService users(String hash) {
        validateHash(hash);
        // Existing configuration guard requires OPERATIONS even for the top-level administrator.
        // Recovery security still denies all business writes, independently of these roles.
        return new InMemoryUserDetailsManager(User.withUsername("admin").password(hash).roles("ADMIN", "OPERATIONS", "VIEWER").build());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class RecoveryConfiguration {
        @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
        @Bean SecurityFilterChain recoverySecurity(HttpSecurity http) throws Exception {
            http.authorizeHttpRequests(a -> a
                    .requestMatchers(HttpMethod.GET, "/api/projects", "/api/projects/*/freshness",
                            "/api/projects/*/top", "/api/projects/*/review").hasRole("ADMIN")
                    .anyRequest().denyAll());
            http.httpBasic(org.springframework.security.config.Customizer.withDefaults());
            http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
            http.requestCache(c -> c.disable());
            return http.build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @org.springframework.context.annotation.Import(RecoverySessionController.class)
    static class RecoveryWebConfiguration {
        @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
        @Bean SecurityFilterChain recoveryWebSecurity(HttpSecurity http) throws Exception {
            http.authorizeHttpRequests(a -> a
                    .requestMatchers("/login", "/error").permitAll()
                    .requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**", "/api/auth/session",
                            "/api/operations/configuration", "/api/projects", "/api/projects/*/freshness",
                            "/api/projects/*/top", "/api/projects/*/review").hasRole("ADMIN")
                    .anyRequest().denyAll());
            http.formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/", true).permitAll());
            http.logout(l -> l.logoutSuccessUrl("/login?logout").permitAll());
            http.requestCache(c -> c.disable());
            http.exceptionHandling(e -> e.authenticationEntryPoint((request, response, error) -> {
                if (request.getRequestURI().startsWith("/api/")) response.setStatus(401);
                else { response.setStatus(302); response.setHeader("Location", "/login"); }
            }));
            return http.build();
        }
    }
}
