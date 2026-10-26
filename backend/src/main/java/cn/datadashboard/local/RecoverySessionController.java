package cn.datadashboard.local;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reuses the established form, without promising remember-me in the recovery instance. */
@RestController
public final class RecoverySessionController {
    private final cn.datadashboard.security.TrialSessionController delegate = new cn.datadashboard.security.TrialSessionController();
    @GetMapping(value="/login", produces="text/html;charset=UTF-8")
    public String login(HttpServletRequest request) {
        return delegate.login(request)
                .replace("<h1>素材数据</h1>", "<h1>管理员只读恢复版</h1><p>使用隔离验证时设置的 admin 密码。仅查询恢复库；未恢复正式运行。</p>")
                .replaceAll("<label style=\"[^\"]*\"><input[^>]*name=\"remember-me\"[^>]*>[^<]*</label>", "")
                .replace("<p>共享电脑请勿勾选。主动退出会撤销此账号已保存的登录凭证。</p>", "<p>恢复期间不提供保持登录。普通与运维账号暂不可用。</p>");
    }
    @GetMapping("/api/auth/session")
    public Map<String, Object> session(Authentication identity, HttpServletRequest request) {
        var value = new LinkedHashMap<>(delegate.session(identity, request));
        value.put("operations", true);
        value.put("recoveryReadOnly", true);
        return value;
    }
}
