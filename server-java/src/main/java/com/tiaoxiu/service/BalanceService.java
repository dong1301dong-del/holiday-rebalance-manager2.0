package com.tiaoxiu.service;

import com.tiaoxiu.common.BizException;
import com.tiaoxiu.entity.BalanceLog;
import com.tiaoxiu.repository.BalanceLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 调休余额引擎：余额实时派生（sum 计算），每次变动写一条 BalanceLog。
 * 类型约定（与 BalanceLogRepository.sumBalance 一致）：
 *   EARN / ADJUST / INIT 为 +hours；SPEND 为 -hours（hours 字段存绝对值）。
 */
@Service
public class BalanceService {

    private final BalanceLogRepository balanceLogRepository;

    /**
     * 构造器注入。
     *
     * @param balanceLogRepository 余额流水仓储
     */
    public BalanceService(BalanceLogRepository balanceLogRepository) {
        this.balanceLogRepository = balanceLogRepository;
    }

    /**
     * 查询用户当前调休余额（由流水实时汇总得出，不做缓存以保证一致性）。
     *
     * @param userId 用户 ID
     * @return 当前余额（小时）；无流水时返回 0；透支时返回负数
     */
    public BigDecimal currentBalance(Long userId) {
        BigDecimal sum = balanceLogRepository.sumBalance(userId);
        // sumBalance 内部已用 coalesce 兜底，这里再判一次空是为了防御自定义实现的差异
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /**
     * 查询用户的余额变动流水（按时间正序，最早在前，便于逐笔核对累加）。
     *
     * @param userId 用户 ID
     * @return 该用户的余额流水列表
     */
    public List<BalanceLog> history(Long userId) {
        return balanceLogRepository.findByUserIdOrderByCreatedAtAsc(userId);
    }

    /**
     * 写入一条余额变动日志，并维护 running balance 与 overdraft 标记。
     *
     * <p>统一在这里做「金额取绝对值 + 方向由 sign 决定 + 保留两位小数」的规范化，
     * 保证 hours 字段始终存储正数、方向由 type 表达，避免上层各处自行取负导致口径不一致。
     *
     * @param userId  用户 ID
     * @param type    变动类型，见 BalanceLog.TYPE_*
     * @param hours   变动小时数（传负数也会按绝对值处理，方向由 sign 决定）
     * @param refType 关联业务类型，用于幂等查询
     * @param refId   关联业务记录 ID
     * @param note    变动备注
     * @param sign    方向：正数=增加额度，负数=扣减额度
     * @return 已保存的余额流水（含记账后的余额快照）
     */
    @Transactional
    public BalanceLog addLog(Long userId, String type, BigDecimal hours, String refType, Long refId, String note, int sign) {
        BigDecimal delta = hours.abs();
        if (sign < 0) delta = delta.negate();
        BigDecimal before = currentBalance(userId);
        BigDecimal after = before.add(delta).setScale(2, BigDecimal.ROUND_HALF_UP);
        BalanceLog log = new BalanceLog();
        log.setUserId(userId);
        log.setType(type);
        log.setHours(hours.abs().setScale(2, BigDecimal.ROUND_HALF_UP));
        log.setRefType(refType);
        log.setRefId(refId);
        log.setBalance(after);
        log.setOverdraft(after.compareTo(BigDecimal.ZERO) < 0);
        log.setNote(note);
        return balanceLogRepository.save(log);
    }

    /**
     * 增加额度（加班折算入账 / 撤销扣减退款）。
     *
     * @param userId  用户 ID
     * @param hours   增加的小时数
     * @param refType 关联业务类型
     * @param refId   关联业务记录 ID
     * @param note    备注
     * @return 已保存的余额流水
     */
    public BalanceLog earn(Long userId, BigDecimal hours, String refType, Long refId, String note) {
        return addLog(userId, BalanceLog.TYPE_EARN, hours, refType, refId, note, 1);
    }

    /**
     * 写入初始额度（期初导入 / 历史结转）。
     *
     * @param userId 用户 ID
     * @param hours  初始小时数
     * @param note   备注
     * @return 已保存的余额流水
     */
    public BalanceLog init(Long userId, BigDecimal hours, String note) {
        return addLog(userId, BalanceLog.TYPE_INIT, hours, "INIT", null, note, 1);
    }

    /**
     * 其他调增（补差、退款等不属于加班收入的入账）。
     *
     * @param userId  用户 ID
     * @param hours   增加的小时数
     * @param refType 关联业务类型
     * @param refId   关联业务记录 ID
     * @param note    备注
     * @return 已保存的余额流水
     */
    public BalanceLog adjustUp(Long userId, BigDecimal hours, String refType, Long refId, String note) {
        return addLog(userId, BalanceLog.TYPE_ADJUST, hours, refType, refId, note, 1);
    }

    /**
     * 扣减额度（调休使用 / 撤销加班回冲）。
     *
     * <p>系统允许透支：扣减后余额为负时不阻断，只在备注上追加「透支调休」标记，
     * 由 {@link UserMessage} 提醒机制通知管理员，符合「先使用后补加班」的实际场景。
     *
     * @param userId  用户 ID
     * @param hours   扣减的小时数（正数）
     * @param refType 关联业务类型
     * @param refId   关联业务记录 ID
     * @param note    备注
     * @return 已保存的余额流水
     */
    public BalanceLog spend(Long userId, BigDecimal hours, String refType, Long refId, String note) {
        // 预判扣减后是否为负，仅用于打标留痕，不阻断业务
        if (currentBalance(userId).add(hours.negate()).compareTo(BigDecimal.ZERO) < 0) {
            note = (note == null ? "" : note + "；") + "透支调休";
        }
        return addLog(userId, BalanceLog.TYPE_SPEND, hours, refType, refId, note, -1);
    }
}
