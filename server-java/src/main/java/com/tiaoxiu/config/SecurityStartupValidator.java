package com.tiaoxiu.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 启动期安全基线校验：应用真正开始处理请求前，先 fail-fast 校验关键安全配置。
 *
 * <p>作为 {@link BeanFactoryPostProcessor} 在容器早期执行，一旦校验不通过直接抛异常使应用启动失败，
 * 避免带着弱密钥 / 默认凭据上线。dev 环境（H2 内存库）跳过校验，便于本地开发。
 *
 * <p>校验项：
 * <ul>
 *   <li>必需环境变量（DB_USERNAME / DB_PASSWORD / JWT_SECRET）是否已配置；</li>
 *   <li>JWT_SECRET 是否为仓库里的默认占位密钥（必须换成随机值）；</li>
 *   <li>JWT_SECRET 长度是否达到 32 字符；</li>
 *   <li>数据库口令是否为常见弱口令（仅告警，不阻断启动）。</li>
 * </ul>
 */
@Component
public class SecurityStartupValidator
        implements BeanFactoryPostProcessor, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(SecurityStartupValidator.class);

    /** 仓库默认占位密钥，若 jwt.secret 与之相同说明运维未替换 */
    private static final String PLACEHOLDER_SECRET =
            "tiaoxiu-default-secret-key-please-change-in-prod-2026-08-28-abcdefghijklmnop";

    /** JWT_SECRET 最小安全长度 */
    private static final int MIN_SECRET_LENGTH = 32;

    /** 缺少任何一个都直接启动失败 */
    private static final List<String> REQUIRED_ENV_KEYS = List.of("DB_USERNAME", "DB_PASSWORD", "JWT_SECRET");

    /** 常见弱口令集合（大小写不敏感匹配），命中只告警不阻断 */
    private static final Set<String> WEAK_DB_PASSWORDS =
            new LinkedHashSet<>(Arrays.asList("root", "123456", "password", "admin", "sa", ""));

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (isDevProfile()) {
            log.info("当前为 dev 环境（H2 内存库），跳过启动期安全基线校验");
            return;
        }
        List<String> missing = REQUIRED_ENV_KEYS.stream()
                .filter(key -> !StringUtils.hasText(environment.getProperty(key)))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(buildMissingMessage(missing));
        }
        String secret = environment.getProperty("jwt.secret", "");
        if (PLACEHOLDER_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "启动失败：jwt.secret 仍是仓库里的默认占位密钥，请通过环境变量 JWT_SECRET 配置一个全新的随机密钥。");
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "启动失败：JWT_SECRET 长度不足 32 字符，当前 " + secret.length() + " 字符。");
        }
        String dbPassword = environment.getProperty("DB_PASSWORD", "");
        if (WEAK_DB_PASSWORDS.contains(dbPassword.toLowerCase(Locale.ROOT))) {
            log.warn("安全告警：数据库口令为常见弱口令，建议尽快更换为强口令（本次不阻断启动）。");
        }
        log.info("启动期安全基线校验通过：凭据均来自环境变量，jwt.secret 长度 {} 字符。", secret.length());
    }

    private boolean isDevProfile() {
        return Arrays.asList(environment.getActiveProfiles()).contains("dev");
    }

    private String buildMissingMessage(List<String> missing) {
        return "启动失败：以下必需的环境变量未配置 → " + String.join("、", missing)
                + "。请在启动前设置这些环境变量（或在 start.bat 同目录维护 env.local.bat），不要使用仓库中的默认凭据。";
    }
}
