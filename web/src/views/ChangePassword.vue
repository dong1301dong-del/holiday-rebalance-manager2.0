<script setup>
/**
 * 修改密码页（路由 /change-password）
 * 触发场景：首次登录、或管理员重置密码后登录，后端返回 mustChangePwd=true 时会强制跳转到这里。
 * 改密成功后后端会 bump tokenVersion，旧 token 立即失效，因此必须退出并重新登录。
 *
 * 密码策略（与后端 PasswordUtil 同口径，三级都放行，仅提示强弱）：
 *  - 一级 低 / 红：8-20 位，字母（大写或小写皆可）+ 数字
 *  - 二级 中 / 黄：大写字母 + 小写字母 + 数字
 *  - 三级 高 / 绿：大写字母 + 小写字母 + 数字 + 特殊字符
 */
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { authApi } from '../api';
import { logout } from '../store';
import { ok, err } from '../toast';

const router = useRouter();
const oldPassword = ref('');
const newPassword = ref('');
const confirm = ref('');
const loading = ref(false);

// 三个密码框各自的明文开关（小眼睛）
const showOld = ref(false);
const showNew = ref(false);
const showConfirm = ref(false);

// 特殊字符集合，与后端 PasswordUtil.SPECIAL 保持一致
const SPECIAL = /[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>/?`~]/;
const LEVEL1 = /^(?=.*[A-Za-z])(?=.*\d).{8,20}$/;                     // 字母 + 数字
const LEVEL2 = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,20}$/;             // 大小写 + 数字

/**
 * 计算新密码强度：0=不达标，1=低，2=中，3=高
 * 与后端 PasswordUtil.strength() 逻辑一一对应，避免前后端提示不一致。
 */
const level = computed(() => {
  const v = newPassword.value;
  if (!v) return 0;
  if (LEVEL2.test(v) && SPECIAL.test(v)) return 3;
  if (LEVEL2.test(v)) return 2;
  if (LEVEL1.test(v)) return 1;
  return 0;
});

// 强度条文案与配色：红 / 黄 / 绿
const LEVEL_META = {
  0: { text: '不符合要求', color: '#c0c4cc' },
  1: { text: '低', color: '#f56c6c' },
  2: { text: '中', color: '#e6a23c' },
  3: { text: '高', color: '#67c23a' },
};
const levelMeta = computed(() => LEVEL_META[level.value]);

/**
 * 提交改密：点击「确认修改」时触发
 * 校验顺序：必填 → 新旧不同 → 达到一级门槛 → 两次一致，全部通过才请求 POST /api/auth/change-password
 */
async function submit() {
  if (!oldPassword.value || !newPassword.value) return err('请填写完整');
  if (oldPassword.value === newPassword.value) return err('新密码不能与原密码相同');
  if (level.value === 0) return err('新密码需 8-20 位，且至少包含字母与数字');
  if (newPassword.value !== confirm.value) return err('两次输入的新密码不一致');
  loading.value = true;
  try {
    await authApi.changePassword(oldPassword.value, newPassword.value);
    ok('密码修改成功，请重新登录');
    // 改密使当前 token 失效，必须清掉登录态再回登录页
    logout();
    router.replace('/login');
  } catch (e) {
    err(e.message);
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="page" style="max-width:480px">
    <div class="card">
      <div class="card-title">修改密码</div>
      <p style="color:var(--text-sub);font-size:13px;margin-bottom:16px">
        出于安全考虑，首次登录或管理员重置后需修改密码。密码 8-20 位，至少包含字母与数字；
        同时含大小写字母为中强度，再加特殊字符为高强度，三种强度均可提交。
      </p>

      <div class="form-item">
        <label class="form-label">原密码</label>
        <div class="pwd-wrap">
          <input
            v-model="oldPassword"
            :type="showOld ? 'text' : 'password'"
            class="input"
            placeholder="请输入当前密码"
          />
          <span class="eye" :title="showOld ? '隐藏密码' : '显示密码'" @click="showOld = !showOld">
            {{ showOld ? '🙈' : '👁' }}
          </span>
        </div>
      </div>

      <div class="form-item">
        <label class="form-label">新密码</label>
        <div class="pwd-wrap">
          <input
            v-model="newPassword"
            :type="showNew ? 'text' : 'password'"
            class="input"
            placeholder="8-20 位，至少含字母与数字"
          />
          <span class="eye" :title="showNew ? '隐藏密码' : '显示密码'" @click="showNew = !showNew">
            {{ showNew ? '🙈' : '👁' }}
          </span>
        </div>
        <!-- 三级强度指示灯：低红 / 中黄 / 高绿 -->
        <div v-if="newPassword" class="strength">
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

      <div class="form-item">
        <label class="form-label">确认新密码</label>
        <div class="pwd-wrap">
          <input
            v-model="confirm"
            :type="showConfirm ? 'text' : 'password'"
            class="input"
            placeholder="再次输入新密码"
          />
          <span class="eye" :title="showConfirm ? '隐藏密码' : '显示密码'" @click="showConfirm = !showConfirm">
            {{ showConfirm ? '🙈' : '👁' }}
          </span>
        </div>
      </div>

      <div class="modal-footer" style="padding:0">
        <button class="btn" @click="router.push('/dashboard')">取消</button>
        <button class="btn btn-primary" :disabled="loading" @click="submit">{{ loading ? '提交中...' : '确认修改' }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
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
.strength .lamp {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  margin-left: 6px;
  transition: background 0.2s;
}
.strength-text { font-size: 12px; font-weight: 600; }
</style>
