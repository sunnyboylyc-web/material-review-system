package cn.datadashboard.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Chinese login page and same-origin identity/CSRF handshake. */
@RestController
public final class TrialSessionController {
    @org.springframework.beans.factory.annotation.Value("${scsj.auth.public-access:false}")
    private boolean publicAccess;
    @GetMapping(value = "/login", produces = "text/html;charset=UTF-8")
    public String login(HttpServletRequest request) {
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrf == null) throw new IllegalStateException("AUTH_SESSION_UNAVAILABLE");
        String message = request.getParameter("error") != null ? "账号或密码不正确，请重新输入。"
                : request.getParameter("logout") != null ? "已退出登录。" : "请使用分配给你的账号登录。";
        return """
                <!doctype html><html lang="zh-CN"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1"><title>登录 · 素材数据</title>
                <style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:#f2f6f8;color:#183e50;font:16px system-ui,sans-serif}
                main{box-sizing:border-box;width:min(400px,92vw);padding:32px;background:white;border:1px solid #dce7ec;border-radius:16px}
                h1{margin:0 0 16px}label{display:block;margin:20px 0 6px}input,button{box-sizing:border-box;width:100%;padding:12px;font:inherit;border:1px solid #aec9d4;border-radius:7px}
                button{margin-top:24px;background:#17647b;color:white;cursor:pointer}input:focus,button:focus{outline:3px solid #79cbd0;outline-offset:2px}p{font-size:14px;line-height:1.6}</style>
                </head><body><main><h1>素材数据</h1><p role="status">__MESSAGE__</p>
                <form action="__LOGIN__" method="post"><input type="hidden" name="_csrf" value="__CSRF__">
                <label for="username">账号</label><input id="username" name="username" autocomplete="username" required maxlength="40">
                <label for="password">密码</label><input id="password" name="password" type="password" autocomplete="current-password" required minlength="6" maxlength="60">
                <label style="display:flex;gap:8px;align-items:center;font-size:14px"><input style="width:auto" type="checkbox" name="remember-me" value="true">保持登录14天（仅限私人设备）</label>
                <p>共享电脑请勿勾选。主动退出会撤销此账号已保存的登录凭证。</p>
                <button type="submit">登录</button></form></main></body></html>
                """.replace("__LOGIN__", org.springframework.web.util.HtmlUtils.htmlEscape(request.getContextPath() + "/login")).replace("__MESSAGE__", message).replace("__CSRF__", org.springframework.web.util.HtmlUtils.htmlEscape(csrf.getToken()));
    }
    @GetMapping("/api/auth/session")
    public Map<String, Object> session(Authentication identity, HttpServletRequest request) {
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (identity == null || csrf == null) throw new IllegalStateException("AUTH_SESSION_UNAVAILABLE");
        return Map.of("username", publicAccess ? "访客" : identity.getName(), "publicAccess", publicAccess,
                "administrator", identity.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())),
                "operations", identity.getAuthorities().stream().anyMatch(a -> "ROLE_OPERATIONS".equals(a.getAuthority())),
                "csrfHeader", csrf.getHeaderName(), "csrfToken", csrf.getToken());
    }
}
