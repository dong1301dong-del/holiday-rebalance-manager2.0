<script setup>
/**
 * 个人中心（路由 /profile，权限码 dashboard:view）
 * 职责：展示当前登录者的账号信息（姓名/账号/角色/部门）与调休余额，并提供自助改密。
 * 与 ChangePassword.vue 的区别：这里是主动入口，改密后延迟 1.2s 再退出，让用户先看到成功提示。
 *
 * 余额可见性：系统管理员与录入员是特殊账号，不参与加班/调休录入，因此不展示「调休余额」，
 * 也不发起余额请求（后端 /api/report/balance 对他们无业务意义）。
 *
 * 内置管理员（auth.user.builtin=true）：密码由运维经环境变量管理，界面隐藏改密卡片仅显示说明，
 * 与后端 AuthService.changePassword 的拒绝逻辑保持一致。
 *
 * 密码策略（与后端 PasswordUtil 同口径，三级都放行，仅提示强弱）：
 *  - 一级 低 / 红：8-20 位，字母（大写或小写皆可）+ 数字
 *  - 二级 中 / 黄：大写字母 + 小写字母 + 数字
 *  - 三级 高 / 绿：大写字母 + 小写字母 + 数字 + 特殊字符
 */
import { ref, computed, onMounted } from 'vue';
import { authApi, reportApi } from '../api';
import { auth, logout } from '../store';
import { useRouter } from 'vue-router';
import { ok, err } from '../toast';

const router = useRouter();
const ROLE_LABEL = { ADMIN: '管理员', CLERK: '录入员', EMPLOYEE: '普通员工' };

const balance = ref(null); // { balance: number, overdraft: boolean }
const pwdForm = ref({ oldPassword: '', newPassword: '', confirm: '' });

// 三个密码框各自的明文开关（小眼睛）
const showOld = ref(false);
const showNew = ref(false);
const showConfirm = ref(false);

/** 管理员/录入员为系统账号，不展示个人调休余额 */
const isSystemAccount = computed(() =>
  (auth.user?.roles || []).some((r) => r === 'ADMIN' || r === 'CLERK')
);

/** 内置管理员：密码由运维经环境变量 ADMIN_DEFAULT_PASSWORD 统一管理，界面不提供改密入口 */
const isBuiltinAccount = computed(() => !!auth.user?.builtin);

// 特殊字符集合，与后端 PasswordUtil.SPECIAL 保持一致
const SPECIAL = /[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>/?`~]/;
const LEVEL1 = /^(?=.*[A-Za-z])(?=.*\d).{8,20}$/;         // 字母 + 数字
const LEVEL2 = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,20}$/; // 大小写 + 数字

/** 新密码强度：0=不达标，1=低，2=中，3=高 */
const level = computed(() => {
  const v = pwdForm.value.newPassword;
  if (!v) return 0;
  if (LEVEL2.test(v) && SPECIAL.test(v)) return 3;
  if (LEVEL2.test(v)) return 2;
  if (LEVEL1.test(v)) return 1;
  return 0;
});

const LEVEL_META = {
  0: { text: '不符合要求', color: '#c0c4cc' },
  1: { text: '低', color: '#f56c6c' },
  2: { text: '中', color: '#e6a23c' },
  3: { text: '高', color: '#67c23a' },
};
const levelMeta = computed(() => LEVEL_META[level.value]);

/** 加载本人余额：GET /api/report/balance；失败不影响页面其它信息展示 */
async function loadBalance() {
  if (isSystemAccount.value) return; // 系统账号跳过
  try { const r = await reportApi.balance(); balance.value = r.data; } catch { /* ignore */ }
}
onMounted(loadBalance);

/**
 * 提交改密：点击「确认修改」触发
 * 校验顺序：必填 → 新旧不同 → 达到一级门槛 → 两次一致
 * 成功后延迟 1.2 秒再退出登录，避免提示一闪而过
 */
async function submitPwd() {
  const f = pwdForm.value;
  if (!f.oldPassword || !f.newPassword) return err('请填写原密码和新密码');
  if (f.oldPassword === f.newPassword) return err('新密码不能与原密码相同');
  if (level.value === 0) return err('新密码需 8-20 位，且至少包含字母与数字');
  if (f.newPassword !== f.confirm) return err('两次输入的新密码不一致');
  try {
    await authApi.changePassword(f.oldPassword, f.newPassword);
    ok('密码已修改，请重新登录');
    pwdForm.value = { oldPassword: '', newPassword: '', confirm: '' };
    setTimeout(() => { logout(); router.push('/login'); }, 1200);
  } catch (e) { err(e.message); }
}
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <div class="page-title">个人中心</div>
        <div class="page-sub">
          {{ isBuiltinAccount ? '查看账号信息'
             : (isSystemAccount ? '查看账号信息，并管理登录密码' : '查看账号信息与调休余额，并管理登录密码') }}
        </div>
      </div>
    </div>

    <div class="profile-grid">
      <div class="card">
        <div class="card-title">账号信息</div>
        <div class="profile-info">
          <div class="big-avatar">{{ (auth.user?.name || '?').slice(0, 1) }}</div>
          <div class="info-row"><span>姓名</span><b>{{ auth.user?.name }}</b></div>
          <div class="info-row"><span>账号</span><b style="font-family:monospace">{{ auth.user?.username }}</b></div>
          <div class="info-row"><span>角色</span><b>
            <span v-for="r in (auth.user?.roles || [])" :key="r" class="tag tag-pink" style="margin-right:4px">{{ ROLE_LABEL[r] || r }}</span>
          </b></div>
          <div class="info-row"><span>部门</span><b>{{ auth.user?.department || '-' }}</b></div>
          <!-- 管理员/录入员不展示个人调休余额 -->
          <div v-if="!isSystemAccount" class="info-row"><span>调休余额</span><b style="color:var(--primary);font-size:18px">
            {{ balance ? balance.balance + ' h' : '—' }}
            <span v-if="balance && balance.overdraft" class="tag tag-red">透支</span>
          </b></div>
        </div>
      </div>

      <!-- 内置管理员：后端禁止界面改密（AuthService.changePassword），此处直接隐藏入口，避免点了才被拒 -->
      <div v-if="!isBuiltinAccount" class="card">
        <div class="card-title">修改密码</div>
        <div class="form-item"><label class="form-label">原密码</label>
          <div class="pwd-wrap">
            <input
              v-model="pwdForm.oldPassword"
              :type="showOld ? 'text' : 'password'"
              class="input"
              autocomplete="current-password"
            />
            <span class="eye" :title="showOld ? '隐藏密码' : '显示密码'" @click="showOld = !showOld">
              {{ showOld ? '🙈' : '👁' }}
            </span>
          </div>
        </div>
        <div class="form-item"><label class="form-label">新密码</label>
          <div class="pwd-wrap">
            <input
              v-model="pwdForm.newPassword"
              :type="showNew ? 'text' : 'password'"
              class="input"
              placeholder="8-20 位，至少含字母与数字"
              autocomplete="new-password"
            />
            <span class="eye" :title="showNew ? '隐藏密码' : '显示密码'" @click="showNew = !showNew">
              {{ showNew ? '🙈' : '👁' }}
            </span>
          </div>
          <!-- 三级强度指示灯：低红 / 中黄 / 高绿 -->
          <div v-if="pwdForm.newPassword" class="strength">
            <span
              v-for="i in 3"
              :key="i"
              class="bar"
              :style="{ background: i <= level ? levelMeta.color : '#ebeef5' }"
            ></span>
            <span class="lamp" :style="{ background: levelMeta.color }"></span>
            <span class="strength-text" :style="{ color: levelMeta.color }">{{ levelMeta.text }}</span>
          </div>
        </div>
        <div class="form-item" style="margin-bottom:0"><label class="form-label">确认新密码</label>
          <div class="pwd-wrap">
            <input
              v-model="pwdForm.confirm"
              :type="showConfirm ? 'text' : 'password'"
              class="input"
              autocomplete="new-password"
            />
            <span class="eye" :title="showConfirm ? '隐藏密码' : '显示密码'" @click="showConfirm = !showConfirm">
              {{ showConfirm ? '🙈' : '👁' }}
            </span>
          </div>
        </div>
        <button class="btn btn-primary" style="width:100%;margin-top:16px" @click="submitPwd">确认修改</button>
      </div>

      <!-- 内置管理员：密码由运维经环境变量统一管理，此处只给说明、不提供改密入口 -->
      <div v-else class="card">
        <div class="card-title">修改密码</div>
        <div class="builtin-tip">
          内置管理员账号的密码由运维通过环境变量统一管理，界面不提供修改入口。
          如需变更，请联系运维设置 <code>ADMIN_DEFAULT_PASSWORD</code> 后重启服务。
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.profile-grid { display: grid; grid-template-columns: 1.2fr 1fr; gap: 16px; }
@media (max-width: 900px) { .profile-grid { grid-template-columns: 1fr; } }
.builtin-tip {
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-secondary, #909399);
}
.builtin-tip code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--fill-light, #f4f4f5);
  font-family: monospace;
  font-size: 12px;
}
.big-avatar {
  width: 64px; height: 64px; border-radius: 50%;
  background: linear-gradient(135deg, var(--primary-light), var(--primary));
  color: #fff; font-size: 26px; font-weight: 700;
  display: flex; align-items: center; justify-content: center;
  margin: 0 auto 18px; box-shadow: 0 6px 16px rgba(255,111,165,0.3);
}
.info-row { display: flex; justify-content: space-between; align-items: center; padding: 10px 4px; border-bottom: 1px dashed #ffd9e8; font-size: 13.5px; }
.info-row span { color: var(--text-sub); }
.info-row:last-child { border-bottom: none; }

/* 输入框右侧留出小眼睛的位置 */
.pwd-wrap { position: relative; }
.pwd-wrap .input { width: 100%; padding-right: 38px; }
.eye {
  position: absolute;
  right: 10px;
  top: 50%;
  transform: translateY(-50%);
  cursor: pointer;
  font-size: 15px;
  line-height: 1;
  user-select: none;
  opacity: 0.75;
}
.eye:hover { opacity: 1; }

.strength { display: flex; align-items: center; gap: 4px; margin-top: 8px; }
.strength .bar { width: 34px; height: 5px; border-radius: 3px; transition: background 0.2s; }
.strength .lamp { width: 9px; height: 9px; border-radius: 50%; margin-left: 6px; transition: background 0.2s; }
.strength-text { font-size: 12px; font-weight: 600; }
</style>
