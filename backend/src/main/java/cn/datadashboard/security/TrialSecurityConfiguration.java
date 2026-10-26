package cn.datadashboard.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.util.regex.Pattern;

/** Trial-cloud policy, explicitly imported by the authenticated runner. */
@Configuration(proxyBeanMethods = false)
public class TrialSecurityConfiguration {
    private static final Pattern BCRYPT = Pattern.compile("\\$2[aby]\\$[0-9]{2}\\$[./A-Za-z0-9]{53}");

    @Bean
    PasswordEncoder trialPasswordEncoder() {
        var encoder=new org.springframework.security.crypto.password.DelegatingPasswordEncoder("pbkdf2",
                java.util.Map.of("pbkdf2",org.springframework.security.crypto.password.Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
        encoder.setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder());
        return encoder;
    }

    @Bean
    @ConditionalOnProperty(name="scsj.auth.public-access", havingValue="false", matchIfMissing=true)
    PrivateAccountStore trialUsers(
            PasswordEncoder encoder,
            @Value("${scsj.auth.state-directory:.local/user-state}") String stateDirectory,
            @Value("${scsj.auth.bootstrap-admin-only:false}") boolean adminOnly,
            @Value("${scsj.auth.admin-password-hash:}") String adminHash,
            @Value("${scsj.auth.viewer-name:123456}") String viewer,
            @Value("${scsj.auth.viewer-password-hash:}") String viewerHash,
            @Value("${scsj.auth.operations-name:asdfgh}") String operations,
            @Value("${scsj.auth.operations-password-hash:}") String operationsHash) {
        if (adminOnly) {
            if (!BCRYPT.matcher(adminHash).matches()) throw new IllegalStateException("TRIAL_AUTH_CONFIGURATION_REQUIRED");
            return new PrivateAccountStore(java.nio.file.Path.of(stateDirectory),encoder,null,null,null,null,adminHash,true);
        }
        if (viewer == null || operations == null || !viewer.matches("[A-Za-z0-9_-]{1,40}")
                || !operations.matches("[A-Za-z0-9_-]{1,40}") || viewer.equals(operations)
                || !BCRYPT.matcher(viewerHash).matches() || !BCRYPT.matcher(operationsHash).matches()
                || viewerHash.equals(operationsHash))
            throw new IllegalStateException("TRIAL_AUTH_CONFIGURATION_REQUIRED");
        if("admin".equals(viewer)||"admin".equals(operations)||(!adminHash.isBlank()&&!BCRYPT.matcher(adminHash).matches()))
            throw new IllegalStateException("TRIAL_AUTH_CONFIGURATION_REQUIRED");
        return new PrivateAccountStore(java.nio.file.Path.of(stateDirectory),encoder,viewer,viewerHash,operations,operationsHash,adminHash);
    }

    @Bean
    @ConditionalOnProperty(name="scsj.auth.public-access", havingValue="false", matchIfMissing=true)
    AccountManagementController accountManagementController(PrivateAccountStore accounts) { return new AccountManagementController(accounts); }

    @Bean
    @ConditionalOnProperty(name="scsj.auth.public-access", havingValue="true")
    UserDetailsService publicModeHasNoLoginAccounts() {
        return username -> { throw new org.springframework.security.core.userdetails.UsernameNotFoundException("LOGIN_DISABLED"); };
    }

    @Bean
    @ConditionalOnProperty(name="scsj.auth.public-access", havingValue="false", matchIfMissing=true)
    DurableRememberMeServices trialRememberMe(UserDetailsService users,
            @Value("${scsj.auth.state-directory:.local/user-state}") String stateDirectory,
            @Value("${scsj.auth.remember-cookie-name:remember-me}") String cookieName) {
        var services = new DurableRememberMeServices(java.nio.file.Path.of(stateDirectory), users);
        services.setCookieName(cookieName);
        return services;
    }

    @Bean
    SecurityFilterChain trialSecurityFilterChain(HttpSecurity http,
            ObjectProvider<DurableRememberMeServices> rememberProvider, ObjectProvider<PrivateAccountStore> accountProvider,
            @Value("${scsj.auth.public-access:false}") boolean publicAccess) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/login", "/error", "/favicon.ico").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/operations/exports/**").hasRole("VIEWER")
                .requestMatchers("/api/operations/**").hasRole("OPERATIONS")
                .requestMatchers("/api/auth/session").authenticated()
                .requestMatchers("/api/preferences/projects/*/period").hasRole("VIEWER")
                .requestMatchers("/api/preferences/projects/*/review-snapshot", "/api/preferences/projects/*/review-snapshot/refresh").hasRole("VIEWER")
                .requestMatchers("/api/projects", "/api/projects/**").hasRole("VIEWER")
                .requestMatchers("/", "/index.html", "/assets/**").authenticated()
                .anyRequest().denyAll());
        if (publicAccess) {
            http.formLogin(form -> form.disable());
            http.httpBasic(basic -> basic.disable());
            http.logout(logout -> logout.disable());
            http.rememberMe(remember -> remember.disable());
            http.addFilterBefore(new PublicAccessFilter(), org.springframework.security.web.authentication.AnonymousAuthenticationFilter.class);
        } else {
        var rememberMe = rememberProvider.getObject();
        var accounts = accountProvider.getObject();
        http.formLogin(form -> form.loginPage("/login")
                .successHandler((request, response, authentication) -> redirect(request, response, "/"))
                .failureHandler((request, response, failure) -> redirect(request, response, "/login?error")).permitAll());
        http.logout(logout -> logout.logoutSuccessHandler((request, response, authentication) -> redirect(request, response, "/login?logout")).permitAll());
        http.rememberMe(remember -> remember.key(rememberMe.signingKey()).rememberMeServices(rememberMe));
        http.addFilterBefore(new ActiveAccountFilter(accounts),org.springframework.security.web.access.intercept.AuthorizationFilter.class);
        }
        http.requestCache(cache -> cache.disable());
        http.exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, failure) -> {
            if (publicAccess || request.getServletPath().startsWith("/api/")) {
                response.setStatus(publicAccess ? 403 : 401); response.setContentType("application/json");
                response.getWriter().write(publicAccess ? "{\"code\":\"ACCESS_DENIED\"}" : "{\"code\":\"LOGIN_REQUIRED\"}");
            } else redirect(request, response, "/login");
        }).accessDeniedHandler((request, response, failure) -> {
            response.setStatus(403); response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"ACCESS_DENIED\"}");
        }));
        // Keep Spring Security's session-bound CSRF protection for every state-changing request.
        return http.build();
    }
    private static void redirect(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, String path) {
        response.setStatus(302); response.setHeader("Location", request.getContextPath() + path);
    }
}
