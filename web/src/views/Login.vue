<script setup>
/**
 * 登录页（路由 /login，meta.public = true，无需登录即可访问）
 * 职责：收集账号密码 → 调用 POST /api/auth/login → 落地全局登录态 → 跳转目标页。
 * 特殊处理：后端返回 mustChangePwd（首次登录或管理员重置密码后）时强制跳 /change-password。
 */
import { ref } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { doLogin } from '../store';
import { ok, err } from '../toast';

const router = useRouter();
const route = useRoute();

const username = ref('');
const password = ref('');
const loading = ref(false);      // 登录中状态，防止重复提交
const showPassword = ref(false); // 小眼睛：是否以明文展示密码

/**
 * 提交登录：表单 submit 与密码框回车都会触发
 * @returns {Promise<void>} 失败时只提示不跳转
 */
async function submit() {
  if (!username.value || !password.value) return err('请输入账号和密码');
  loading.value = true;
  try {
    // 账号去首尾空格：用户常从 Excel 复制账号带入空白
    const data = await doLogin(username.value.trim(), password.value);
    ok(`欢迎回来，${data.user.name}`);
    if (data.mustChangePwd) {
      // 用 replace 而非 push，避免改密后点返回又回到登录页
      router.replace('/change-password');
      return;
    }
    // 被守卫拦截时会带 redirect 参数，登录后回到原目标页
    const redirect = route.query.redirect || '/dashboard';
    router.replace(redirect);
  } catch (e) {
    err(e.message);
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-bg"></div>
    <div class="login-card">
      <div class="brand">
        <div class="brand-icon">💖</div>
        <h1>调休管家</h1>
        <p>加班调休管理系统</p>
      </div>
      <form @submit.prevent="submit">
        <div class="form-item">
          <label class="form-label">账号</label>
          <input v-model="username" class="input" placeholder="请输入账号" autocomplete="username" />
        </div>
        <div class="form-item">
          <label class="form-label">密码</label>
          <div class="pwd-wrap">
            <input
              v-model="password"
              :type="showPassword ? 'text' : 'password'"
              class="input"
              placeholder="请输入密码"
              autocomplete="current-password"
              @keyup.enter="submit"
            />
            <span
              class="eye"
              :title="showPassword ? '隐藏密码' : '显示密码'"
              @click="showPassword = !showPassword"
            >{{ showPassword ? '🙈' : '👁' }}</span>
          </div>
        </div>
        <button type="submit" class="btn btn-primary login-btn" :disabled="loading">
          {{ loading ? '登录中...' : '登 录' }}
        </button>
      </form>
      <div class="tips">
        <div>默认管理员：<b>admin</b> / <b>Abc_123456</b></div>
        <div>员工初始密码由管理员设置，首次登录需修改</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  overflow: hidden;
  background: linear-gradient(135deg, #ffd9ec 0%, #ffc2d9 50%, #ffb3d1 100%);
}
.login-bg {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(circle at 18% 22%, rgba(255,255,255,0.5) 0%, transparent 42%),
    radial-gradient(circle at 82% 78%, rgba(255, 111, 165, 0.25) 0%, transparent 45%);
}
.login-card {
  position: relative;
  width: min(400px, 92vw);
  background: rgba(255,255,255,0.97);
  border-radius: 22px;
  box-shadow: 0 22px 60px rgba(240, 82, 143, 0.28);
  padding: 40px 36px 30px;
  backdrop-filter: blur(8px);
}
.brand { text-align: center; margin-bottom: 28px; }
.brand-icon {
  width: 66px; height: 66px;
  margin: 0 auto 14px;
  border-radius: 18px;
  background: linear-gradient(135deg, #ff9bc2, #ff6fa5);
  display: flex; align-items: center; justify-content: center;
  font-size: 34px;
  box-shadow: 0 8px 20px rgba(255, 111, 165, 0.4);
}
/* 密码框右侧留出小眼睛的位置 */
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
.brand h1 { font-size: 24px; color: #d6397f; letter-spacing: 3px; }
.brand p { font-size: 13px; color: #b3648a; margin-top: 6px; letter-spacing: 1px; }
.login-btn {
  width: 100%;
  padding: 11px;
  font-size: 15px;
  letter-spacing: 6px;
  margin-top: 6px;
}
.tips {
  margin-top: 22px;
  padding-top: 16px;
  border-top: 1px dashed var(--border);
  font-size: 12px;
  color: var(--text-sub);
  text-align: center;
  line-height: 2;
}
</style>
