package com.tiaoxiu.interceptor;

import com.tiaoxiu.common.WriteGate;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * 写操作拦截器：对所有写接口（POST / PUT / DELETE）施加「全局串行 + 冷却」约束。
 *
 * <p>工作流程（与 {@link WriteGate} 配合）：
 * <ol>
 *   <li>{@code preHandle}：判定为写请求则调用 {@code writeGate.acquire()} 获取锁并等待冷却，
 *       成功后把本次请求标记为「已加锁」，放行进入 Controller；</li>
 *   <li>{@code afterCompletion}：无论业务成功或抛异常，只要本请求加过锁就调用
 *       {@code writeGate.release()} 记录完成时间并释放锁，避免锁泄漏。</li>
 * </ol>
 *
 * <p>排除项：登录接口 {@code /api/auth/login} 不计入写锁，避免登录被 3 秒冷却误伤。
 * 读接口（GET / OPTIONS）天然不在写方法之列，不受影响。
 */
@Component
public class WriteGuardInterceptor implements HandlerInterceptor {

    private static final List<String> WRITE_METHODS = List.of("POST", "PUT", "DELETE");
    private static final String ATTR_LOCKED = "writeGateLocked";

    private final WriteGate writeGate;

    public WriteGuardInterceptor(WriteGate writeGate) {
        this.writeGate = writeGate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!isWriteRequest(request)) return true;
        writeGate.acquire();
        request.setAttribute(ATTR_LOCKED, Boolean.TRUE);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (request.getAttribute(ATTR_LOCKED) != null) {
            writeGate.release();
            request.removeAttribute(ATTR_LOCKED);
        }
    }

    /** 是否需要对本请求加写锁：写方法且非登录接口 */
    private boolean isWriteRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/auth/login")) return false;
        return WRITE_METHODS.contains(request.getMethod());
    }
}
