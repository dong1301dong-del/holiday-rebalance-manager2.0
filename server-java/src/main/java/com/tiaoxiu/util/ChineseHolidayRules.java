package com.tiaoxiu.util;

import com.tiaoxiu.entity.Holiday;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置的中国法定节假日规则。
 *
 * <p>规则产出一年中「每一天」的官方类型：
 * <ol>
 *   <li>法定节假日区间（LEGAL）；</li>
 *   <li>调休补班日（WORKDAY，名称标注「调休补班」）；</li>
 *   <li>其余周六周日为休息日（RESTDAY）；</li>
 *   <li>其余为法定工作日（WORKDAY）。</li>
 * </ol>
 *
 * <p><b>数据说明</b>：2026 年为内置精确数据（法定节假日区间与系统初始化数据一致，
 * 补班日依据国务院「前借后挪」的常规调休惯例推演）。2027~2030 年按同样的月份规律推演生成，
 * 属于<b>内置规则推演，以界面手工修正为准</b>——管理员可在节假日日历界面直接调整任意一天，
 * 人工维护的记录优先级高于本规则。
 */
public final class ChineseHolidayRules {

    private ChineseHolidayRules() {
    }

    /** 官方日规则：类型 + 名称 */
    public record DayRule(String type, String name) {
    }

    /** 一个节假日区间（月 + 起止日 + 名称 + 调休补班日的 MM-DD） */
    private record Festival(int month, int startDay, int endDay, String name, List<String> makeup) {
    }

    /**
     * 2026 年内节假日：法定区间与系统初始化数据（DataInitializer.seed2026Holidays）保持一致。
     * 补班日为依据调休惯例推演，管理员可在界面手工修正。
     */
    private static final Map<Integer, List<Festival>> EXPLICIT = new HashMap<>();

    static {
        EXPLICIT.put(2026, List.of(
                // 元旦：2026-01-01（周四）放假 1 天，节后周日 01-04 补班
                new Festival(1, 1, 1, "元旦", List.of("01-04")),
                // 春节：2026-02-17（周二）~02-23（周一），节前周日 02-15、节后周六 02-28 补班
                new Festival(2, 17, 23, "春节", List.of("02-15", "02-28")),
                // 清明：2026-04-04（周六）~04-06（周一），与周末自然连休，无需补班
                new Festival(4, 4, 6, "清明节", List.of()),
                // 劳动节：2026-05-01（周五）~05-05（周二），节前周日 04-26、节后周六 05-09 补班
                new Festival(5, 1, 5, "劳动节", List.of("04-26", "05-09")),
                // 端午：2026-06-19（周五）~06-21（周日），与周末自然连休，无需补班
                new Festival(6, 19, 21, "端午节", List.of()),
                // 中秋：2026-09-25（周五）~09-27（周日），与周末自然连休，无需补班
                new Festival(9, 25, 27, "中秋节", List.of()),
                // 国庆：2026-10-01（周四）~10-07（周三），节后周六 10-10 补班
                new Festival(10, 1, 7, "国庆节", List.of("10-10"))
        ));
    }

    /**
     * 2027~2030 年规律模板：与 2026 年同月同日的法定区间，
     * 补班日按「长假（≥5 天）前后各借一个周末」的惯例自动推演。
     * 注意：属于内置规则推演，以界面手工修正为准。
     */
    private static final List<Festival> TEMPLATE = List.of(
            new Festival(1, 1, 1, "元旦", List.of()),
            new Festival(2, 17, 23, "春节", List.of()),
            new Festival(4, 4, 6, "清明节", List.of()),
            new Festival(5, 1, 5, "劳动节", List.of()),
            new Festival(6, 19, 21, "端午节", List.of()),
            new Festival(9, 25, 27, "中秋节", List.of()),
            new Festival(10, 1, 7, "国庆节", List.of())
    );

    /** 支持推演的年份范围 */
    public static final int MIN_YEAR = 2026;
    public static final int MAX_YEAR = 2030;

    /**
     * 计算某一年的官方日历（覆盖该年 1 月 1 日到 12 月 31 日的每一天）。
     *
     * <p>计算分三步：先展开法定节假日区间 → 再确定调休补班日 → 最后逐日按优先级判定类型。
     * 2026 年使用内置精确数据，其余年份使用月份模板推演。
     *
     * @param year 年份，建议在 {@link #MIN_YEAR} ~ {@link #MAX_YEAR} 范围内
     * @return 「日期 → 当日官方规则」的映射，按日期升序（LinkedHashMap 保证顺序）
     */
    public static Map<LocalDate, DayRule> officialOfYear(int year) {
        Map<LocalDate, DayRule> result = new LinkedHashMap<>();
        List<Festival> festivals = EXPLICIT.getOrDefault(year, TEMPLATE);

        // 1. 法定节假日
        Map<LocalDate, String> legal = new LinkedHashMap<>();
        for (Festival f : festivals) {
            LocalDate start = LocalDate.of(year, f.month(), f.startDay());
            LocalDate end = LocalDate.of(year, f.month(), f.endDay());
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                legal.put(d, f.name());
            }
        }

        // 2. 调休补班日（显式配置优先，否则按惯例推演；法定节假日优先于补班日）
        Map<LocalDate, String> makeup = new LinkedHashMap<>();
        boolean explicitYear = EXPLICIT.containsKey(year);
        if (explicitYear) {
            for (Festival f : festivals) {
                for (String md : f.makeup()) {
                    String[] parts = md.split("-");
                    LocalDate d = LocalDate.of(year, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                    if (!legal.containsKey(d)) makeup.put(d, Holiday.MAKEUP_NAME);
                }
            }
        } else {
            for (Festival f : festivals) {
                LocalDate start = LocalDate.of(year, f.month(), f.startDay());
                LocalDate end = LocalDate.of(year, f.month(), f.endDay());
                // 仅长假（≥5 天）按惯例前后各借一个周末用于调休补班
                if (end.getDayOfYear() - start.getDayOfYear() + 1 < 5) continue;
                LocalDate before = nearestWeekend(start.minusDays(1), false, year, legal);
                if (before != null) makeup.putIfAbsent(before, Holiday.MAKEUP_NAME);
                LocalDate after = nearestWeekend(end.plusDays(1), true, year, legal);
                if (after != null) makeup.putIfAbsent(after, Holiday.MAKEUP_NAME);
            }
        }

        // 3. 逐日判定
        LocalDate cursor = LocalDate.of(year, 1, 1);
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        while (!cursor.isAfter(yearEnd)) {
            if (legal.containsKey(cursor)) {
                result.put(cursor, new DayRule(Holiday.TYPE_LEGAL, legal.get(cursor)));
            } else if (makeup.containsKey(cursor)) {
                result.put(cursor, new DayRule(Holiday.TYPE_WORKDAY, makeup.get(cursor)));
            } else if (isWeekend(cursor)) {
                result.put(cursor, new DayRule(Holiday.TYPE_RESTDAY, null));
            } else {
                result.put(cursor, new DayRule(Holiday.TYPE_WORKDAY, null));
            }
            cursor = cursor.plusDays(1);
        }
        return result;
    }

    /**
     * 查询单日的官方规则。
     *
     * <p>实现上直接复用整年计算再取值，单日调用会有冗余计算，
     * 批量场景请优先使用 {@link #officialOfYear(int)} 避免重复开销。
     *
     * @param date 目标日期
     * @return 当日官方规则；超出支持年份时同样按模板推演，不会返回 null
     */
    public static DayRule officialOf(LocalDate date) {
        return officialOfYear(date.getYear()).get(date);
    }

    /**
     * 判断是否为周末（周六或周日）。
     *
     * @param date 目标日期
     * @return 周六或周日返回 true
     */
    public static boolean isWeekend(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    /**
     * 兜底规则：不依赖任何内置数据，周末即休息日、其余即工作日。
     *
     * <p>用于节假日表尚未初始化某一天时给出保守判定。
     *
     * @param date 目标日期
     * @return 当日的默认日规则
     */
    public static DayRule defaultOf(LocalDate date) {
        return isWeekend(date)
                ? new DayRule(Holiday.TYPE_RESTDAY, null)
                : new DayRule(Holiday.TYPE_WORKDAY, null);
    }

    /**
     * 把日类型编码转换成中文名称，用于界面展示。
     *
     * @param type 日类型，见 Holiday.TYPE_*
     * @return 中文名称；type 为 null 时返回「未知」，未知类型原样返回
     */
    public static String typeLabel(String type) {
        if (type == null) return "未知";
        return switch (type) {
            case Holiday.TYPE_LEGAL -> "法定节假日";
            case Holiday.TYPE_WORKDAY -> "法定工作日";
            case Holiday.TYPE_RESTDAY -> "休息日";
            default -> type;
        };
    }

    /**
     * 根据日类型返回加班折算比例：法定工作日 0.5，休息日与法定节假日均为 1。
     *
     * <p>业务规则：工作日加班只按一半折算调休，休息日与法定节假日加班全额折算。
     *
     * @param type 日类型，见 Holiday.TYPE_*
     * @return 折算比例
     */
    public static java.math.BigDecimal ratioOf(String type) {
        if (Holiday.TYPE_WORKDAY.equals(type)) return new java.math.BigDecimal("0.5");
        return java.math.BigDecimal.ONE;
    }

    /**
     * 从指定日期起，向前或向后 7 天内寻找最近的可用于补班的周末。
     *
     * <p>筛选条件：必须是周末、不能已经是法定节假日（否则无补班意义）、且仍在同一年内
     * （跨年补班会让「某年日历」里出现上下年的日期，破坏按年计算的前提）。
     *
     * @param from    搜索起点（法定区间的前一天或后一天）
     * @param forward true 向后（节后补班），false 向前（节前补班）
     * @param year    目标年份，用于限制搜索不得跨年
     * @param legal   该年法定节假日映射，用于排除已放假的周末
     * @return 找到的可补班周末；7 天内无满足条件的日期或跨年时返回 null
     */
    private static LocalDate nearestWeekend(LocalDate from, boolean forward, int year, Map<LocalDate, String> legal) {
        for (int i = 0; i < 7; i++) {
            LocalDate d = forward ? from.plusDays(i) : from.minusDays(i);
            if (d.getYear() != year) return null;
            if (isWeekend(d) && !legal.containsKey(d)) return d;
        }
        return null;
    }
}
