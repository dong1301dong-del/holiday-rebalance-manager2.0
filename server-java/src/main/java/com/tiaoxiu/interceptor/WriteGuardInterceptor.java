package com.tiaoxiu.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiaoxiu.common.Result;
import com.tiaoxiu.common.WriteGate;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 写操作拦截器：对所有写接口（POST / PUT / DELETE）施加「全局串行 + 冷却」约束。
 *
 * <p>工作流程（与 {@link WriteGate} 配合）：
 * <ol>
 *   <li>{@code preHandle}：判定为写请求则调用 {@code writeGate.tryAcquire()} 在限定时间内获取锁，
 *       成功后把本次请求标记为「已加锁」，放行进入 Controller；拿不到锁（超时或并发过满）则直接返回 429；</li>
 *   <li>{@code afterCompletion}：无论业务成功或抛异常，只要本请求加过锁就调用
 *       {@code writeGate.release()} 释放锁，避免锁泄漏。</li>
 * </ol>
 *
 * <p>排除项：登录接口 {@code /api/auth/login} 不计入写锁，避免登录被写锁误伤。
 * 读接口（GET / OPTIONS）天然不在写方法之列，不受影响。
 */
@Component
public class WriteGuardInterceptor implements HandlerInterceptor {

    private static final List<String> WRITE_METHODS = List.of("POST", "PUT", "DELETE");
    private static final String ATTR_LOCKED = "writeGateLocked";
    private static final int CODE_BUSY = 429;
    private static final String MSG_BUSY = "系统繁忙，请稍后重试";

    private final WriteGate writeGate;
    private final ObjectMapper objectMapper;

    public WriteGuardInterceptor(WriteGate writeGate, ObjectMapper objectMapper) {
        this.writeGate = writeGate;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!isWriteRequest(request)) return true;
        if (!writeGate.tryAcquire()) {
            writeBusy(response);
            return false;
        }
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

    /**
     * 写锁获取失败时直接返回 429，提示前端稍后重试。
     *
     * @param response HTTP 响应
     * @throws IOException 写响应体时可能抛出
     */
    private void writeBusy(HttpServletResponse response) throws IOException {
        response.setStatus(CODE_BUSY);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail(CODE_BUSY, MSG_BUSY)));
    }
}
