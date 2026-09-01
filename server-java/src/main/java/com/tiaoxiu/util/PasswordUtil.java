package com.tiaoxiu.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码工具：BCrypt 加密 + 强度校验。
 *
 * <p>强度分三级，三级均允许通过校验，仅在前端提示强弱：
 * <ul>
 *   <li>一级（低）：字母（大写或小写皆可）+ 数字</li>
 *   <li>二级（中）：大写字母 + 小写字母 + 数字</li>
 *   <li>三级（高）：大写字母 + 小写字母 + 数字 + 特殊字符</li>
 * </ul>
 * 因此后端准入门槛取一级：8-20 位且至少含 1 个字母与 1 个数字。
 */
public final class PasswordUtil {

    /** BCrypt 编码器本身线程安全，全局复用一个实例即可 */
    private static final BCryptPasswordEncoder ENC = new BCryptPasswordEncoder();

    // 特殊字符集合（与设计文档一致）。注意在正则字符组与 Java 字符串中都需要转义，故存在多层反斜杠
    private static final String SPECIAL = "!@#$%^&*()_+-=\\[\\]{};':\"\\\\|,.<>/?`~";

    /**
     * 准入门槛正则（对应一级/低强度）：8-20 位，且至少含 1 个字母（不区分大小写）与 1 个数字。
     * 用零宽先行断言而不是逐字符遍历，是为了一条正则同时覆盖「字符种类」与「长度」两类约束。
     */
    private static final String LEVEL1 = "^(?=.*[A-Za-z])(?=.*\\d).{8,20}$";

    /** 二级（中）：大小写字母 + 数字 */
    private static final String LEVEL2 = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,20}$";

    /** 三级（高）：大小写字母 + 数字 + 特殊字符 */
    private static final String LEVEL3 =
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[" + SPECIAL + "]).{8,20}$";

    /** 工具类禁止实例化 */
    private PasswordUtil() {}

    /**
     * 对明文密码做 BCrypt 哈希。
     *
     * <p>BCrypt 每次会生成随机盐并内嵌在结果中，因此相同明文两次加密结果不同，
     * 校验必须用 {@link #matches(CharSequence, String)} 而不能比对哈希字符串。
     *
     * @param raw 明文密码
     * @return 含盐的 BCrypt 哈希串（60 字符）
     */
    public static String encode(CharSequence raw) {
        return ENC.encode(raw);
    }

    /**
     * 校验明文密码是否与库中哈希匹配。
     *
     * @param raw     用户输入的明文密码
     * @param encoded 数据库中保存的 BCrypt 哈希
     * @return 匹配返回 true；哈希为 null（用户未设置密码）或不一致返回 false
     */
    public static boolean matches(CharSequence raw, String encoded) {
        if (encoded == null) return false;
        return ENC.matches(raw, encoded);
    }

    /**
     * 校验是否达到准入门槛（一级及以上）。
     *
     * <p>一级、二级、三级密码都会返回 true——强度只影响前端的红/黄/绿提示，不阻断改密。
     *
     * @param raw 待校验的明文密码，允许为 null
     * @return 满足「8-20 位且至少含字母与数字」返回 true
     */
    public static boolean isStrong(String raw) {
        return raw != null && raw.matches(LEVEL1);
    }

    /**
     * 计算密码强度等级，与前端红/黄/绿三档提示保持同一口径。
     *
     * @param raw 待评估的明文密码，允许为 null
     * @return 0=不达标，1=低，2=中，3=高
     */
    public static int strength(String raw) {
        if (raw == null) return 0;
        if (raw.matches(LEVEL3)) return 3;
        if (raw.matches(LEVEL2)) return 2;
        if (raw.matches(LEVEL1)) return 1;
        return 0;
    }

    /**
     * 获取密码规则的文案提示，用于前端或异常信息展示。
     *
     * @return 密码强度规则说明
     */
    public static String strongRuleTip() {
        return "密码需 8-20 位，且至少包含字母与数字（同时含大小写字母为中强度，再加特殊字符为高强度）";
    }
}
