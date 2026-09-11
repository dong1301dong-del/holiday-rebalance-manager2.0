/**
 * 统一 API 请求封装（对接 Java Spring Boot 后端）
 *
 * 后端统一返回结构（common/Result.java、common/PageResult.java）：
 *   { code: 0, message: 'ok', data: <T> }          // 单对象/数组接口
 *   { code: 0, message: 'ok', data: [...], total: N } // 分页接口（PageResult 额外带 total）
 * code !== 0 时本模块直接 throw Error(message)，调用方只需 try/catch。
 *
 * 鉴权说明：后端 JwtFilter 只校验「登录态 + 账号状态 + tokenVersion」，
 * 接口级的权限码（如 overtime:add）由前端 v-permission / 路由 meta.permission 控制显隐，
 * 少数高危接口在 Service 层额外做 ADMIN/CLERK 角色校验（见各方法注释）。
 *
 * 导出：authApi / userApi / roleApi / resourceApi / overtimeApi / leaveApi /
 *       holidayApi / systemLogApi / configApi / reportApi，以及底层 api 对象与 token 读写函数。
 * 使用方：views/ 下各页面、store.js（登录态）、MainLayout.vue（余额与预警消息）。
 */
const TOKEN_KEY = 'tx_token';

/**
 * 读取本地保存的 JWT
 * @returns {string} token，未登录时为空串
 */
export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || '';
}

/**
 * 写入/清除本地 JWT
 * @param {string} [t] token；传空值表示清除（退出登录或 token 失效）
 */
export function setToken(t) {
  if (t) localStorage.setItem(TOKEN_KEY, t);
  else localStorage.removeItem(TOKEN_KEY);
}

/**
 * 所有请求的底层出口：拼 Authorization、序列化 body、统一处理 401 与业务错误码
 * @param {'GET'|'POST'|'PUT'|'DELETE'} method HTTP 方法
 * @param {string} url 接口地址
 * @param {any} [body] 请求体；isForm 为 true 时传 FormData，否则序列化为 JSON
 * @param {boolean} [isForm=false] 是否 multipart 表单（文件上传走这个分支）
 * @returns {Promise<{code:number, message:string, data:any, total?:number}>} 后端 Result/PageResult
 * @throws 401 时清 token 并跳转 #/login；code !== 0 时抛出后端 message
 */
async function request(method, url, body, isForm = false) {
  const headers = {};
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  let payload;
  if (isForm) {
    // 上传场景不能手动设 Content-Type，需让浏览器自动补 boundary
    payload = body; // FormData
  } else {
    headers['Content-Type'] = 'application/json';
    payload = body !== undefined ? JSON.stringify(body) : undefined;
  }

  const res = await fetch(url, { method, headers, body: payload });
  if (res.status === 401) {
    setToken('');
    if (!location.hash.includes('/login')) location.hash = '#/login';
    throw new Error('登录已过期，请重新登录');
  }
  let data;
  try {
    data = await res.json();
  } catch {
    // 非 JSON 响应（如网关错误页）兜底成统一的 { code, message } 形态
    data = { code: res.status, message: res.statusText };
  }
  if (data.code !== 0) throw new Error(data.message || '请求失败');
  return data;
}

/** 底层请求动词封装；页面一般直接用下面按业务分组的 xxxApi，特殊场景（如需要透传额外参数）才用 api.xxx */
export const api = {
  /**
   * GET 请求，params 中 undefined/null/空串的键会被剔除，避免把空条件传给后端
   * @param {string} url
   * @param {Record<string, any>} [params]
   */
  get: (url, params) => {
    const qs = params
      ? '?' + new URLSearchParams(Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== '')).toString()
      : '';
    return request('GET', url + qs);
  },
  post: (url, body) => request('POST', url, body),
  put: (url, body) => request('PUT', url, body),
  del: (url) => request('DELETE', url),
  /** 文件上传：body 为 FormData，走 multipart 分支 */
  upload: (url, form) => request('POST', url, form, true),
  /**
   * 下载文件（Excel 导出/模板）。带 token 请求，转成 blob 后触发浏览器下载。
   * @returns {Promise<string>} 实际下载的文件名
   */
  download: async (url, params) => {
    const qs = params
      ? '?' + new URLSearchParams(Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== '')).toString()
      : '';
    const token = getToken();
    const res = await fetch(url + qs, {
      method: 'GET',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (res.status === 401) {
      setToken('');
      if (!location.hash.includes('/login')) location.hash = '#/login';
      throw new Error('登录已过期，请重新登录');
    }
    if (!res.ok) {
      let msg = '下载失败';
      try {
        const err = await res.json();
        msg = err.message || msg;
      } catch { /* 非 JSON 响应忽略 */ }
      throw new Error(msg);
    }
    // 从响应头解析文件名
    const disposition = res.headers.get('Content-Disposition') || '';
    let filename = '';
    const matchStar = disposition.match(/filename\*=UTF-8''([^;]+)/);
    if (matchStar) {
      filename = decodeURIComponent(matchStar[1]);
    } else {
      const match = disposition.match(/filename="?([^";]+)"?/);
      if (match) filename = decodeURIComponent(match[1]);
    }
    const blob = await res.blob();
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = filename || 'export.xlsx';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(link.href);
    return filename;
  },
};

/**
 * 认证相关接口（AuthController）
 * 使用方：Login.vue（登录）、ChangePassword.vue / Profile.vue（改密）、store.js（initAuth 拉取个人信息）
 */
export const authApi = {
  /**
   * 登录
   * @param {string} username 账号
   * @param {string} password 密码
   * @returns data: { token, user: { id, username, name, department, position, roles }, menus, permissions, mustChangePwd }
   * @permission 匿名可访问（后端 JwtFilter 白名单 /api/auth/login）
   */
  login: (username, password) => api.post('/api/auth/login', { username, password }),
  /** 当前登录者信息：GET /api/auth/me；返回 data 同 login 的 user/menus/permissions，用于刷新动态菜单与权限 */
  me: () => api.get('/api/auth/me'),
  /**
   * 退出当前设备登录：POST /api/auth/logout
   * 仅把「本机令牌」加入黑名单使其立即失效（其它设备上的令牌不受影响），需携带 Authorization。
   * 注意：调用方（MainLayout.vue 的 doLogout）必须在清空本地 token 之前发起本请求，否则拿不到 Authorization 头。
   * 后端 401/失败都不应阻断本地登出——本地清理必须成功，请求结果仅作 best-effort。
   * @returns data: null
   * @permission 匿名不可访问（需 Bearer token）
   */
  logout: () => api.post('/api/auth/logout'),
  /**
   * 修改本人密码：POST /api/auth/change-password
   * @returns data: null；成功后后端 bump tokenVersion，旧 token 立即失效，前端需重新登录
   */
  changePassword: (oldPassword, newPassword) => api.post('/api/auth/change-password', { oldPassword, newPassword }),
};

/**
 * 用户管理接口（UserController）
 * 使用方：Users.vue（增删改查/改密/冻结）、Leave.vue 与 Overtime.vue（员工下拉）、
 *        AllStaffLeave.vue 与 ChartStats.vue（由用户列表去重出部门选项）
 * @permission 前端按钮码：user:add / user:edit / user:resetpwd / user:freeze / user:delete
 */
export const userApi = {
  /** 用户列表：GET /api/users；data: [{ id, username, name, department, position, roles, status }] */
  list: () => api.get('/api/users'),
  /** 用户详情：GET /api/users/{id} */
  get: (id) => api.get(`/api/users/${id}`),
  /**
   * 新增用户：POST /api/users
   * @param {{username:string, name:string, department:string, position:string, roleCodes:string[], password?:string}} body
   *        password 留空则后端下发默认密码 Abc_123456 并要求首次登录改密
   */
  create: (body) => api.post('/api/users', body),
  /** 修改用户：PUT /api/users/{id}；body 同 create（不含 password） */
  update: (id, body) => api.put(`/api/users/${id}`, body),
  /** 删除用户：DELETE /api/users/{id}；不可恢复 */
  remove: (id) => api.del(`/api/users/${id}`),
  /** 分配角色：POST /api/users/{id}/roles，body { roleCodes: ['ADMIN','CLERK','EMPLOYEE'] } */
  assignRoles: (id, roleCodes) => api.post(`/api/users/${id}/roles`, { roleCodes }),
  /** 重置密码：POST /api/users/{id}/reset-password，body { newPassword }；对方下次登录需强制改密 */
  resetPassword: (id, newPassword) => api.post(`/api/users/${id}/reset-password`, { newPassword }),
  /** 冻结/解冻：POST /api/users/{id}/status?status=ACTIVE|FROZEN */
  setStatus: (id, status) => api.post(`/api/users/${id}/status?status=${status}`),
};

/**
 * 角色与权限接口（RoleController）
 * 使用方：Role.vue（角色增删改、分配资源）、Users.vue（角色下拉）
 * @permission 前端权限码 role:view（菜单）/ role:add / role:edit / role:assign
 */
export const roleApi = {
  /** 角色列表：GET /api/roles；data: [{ id, code, name, description, status, builtin }]，builtin 为内置角色不可删除 */
  list: () => api.get('/api/roles'),
  /** 新增角色：POST /api/roles，body { code, name, description, status } */
  create: (body) => api.post('/api/roles', body),
  /** 修改角色：PUT /api/roles/{id}（code 不可改） */
  update: (id, body) => api.put(`/api/roles/${id}`, body),
  /** 删除角色：DELETE /api/roles/{id}；内置角色后端拒绝 */
  remove: (id) => api.del(`/api/roles/${id}`),
  /** 保存角色资源：POST /api/roles/{id}/resources，body { resourceIds: number[] }（全量覆盖） */
  assignResources: (id, resourceIds) => api.post(`/api/roles/${id}/resources`, { resourceIds }),
  /** 角色已持有的资源 id 列表：GET /api/roles/{id}/resources；data: number[] */
  resources: (id) => api.get(`/api/roles/${id}/resources`),
};

/**
 * 资源（菜单/按钮/接口）接口（ResourceController）
 * 使用方：Resource.vue、Role.vue（权限树）
 * @permission 前端权限码 resource:view（菜单）/ resource:add / resource:edit
 */
export const resourceApi = {
  /** 资源列表：GET /api/resources；data: [{ id, parentId, type: 'MENU'|'BUTTON'|'API', code, name, path, component, icon, sort, permission, status }] */
  list: () => api.get('/api/resources'),
  /** 新增资源：POST /api/resources */
  create: (body) => api.post('/api/resources', body),
  /** 修改资源：PUT /api/resources/{id} */
  update: (id, body) => api.put(`/api/resources/${id}`, body),
};

// ============ 加班转调休录入管理 ============
/**
 * 加班转调休录入（OvertimeController）
 * 使用方：Overtime.vue（加班录入记录主页面）、Holiday.vue（月度日历）
 * @permission 前端权限码 overtime:view（菜单）/ overtime:add / overtime:edit / overtime:delete / overtime:import / overtime:export
 */
export const overtimeApi = {
  /**
   * 分页列表：GET /api/overtime
   * @param {{userId?:number, name?:string, department?:string, from?:string, to?:string, page?:number, size?:number}} params
   * @returns { data: OvertimeRow[], total }；行字段含 mode('OVERTIME'|'MANUAL')、date、dayType、ratio、hours、convertedHours、clockInTime
   */
  list: (params) => api.get('/api/overtime', params),
  /**
   * 批量录入（一次提交多行）：POST /api/overtime/batch
   * @param {Array} records 每行由 Overtime.vue 的 buildRecord 组装：
   *   加班转休 { mode:'OVERTIME', userId, date, startTime:'HH:mm:ss', endTime, clockInTime?, remark? }
   *   其他转休 { mode:'MANUAL', userId, date, convertedHours, remark(必填) }
   */
  createBatch: (records) => api.post('/api/overtime/batch', { records }),
  /** 单条录入：POST /api/overtime（页面统一走 createBatch，此接口保留给单条场景） */
  create: (body) => api.post('/api/overtime', body),
  /** 修改单条：PUT /api/overtime/{id}；后端按新值重算转休时长并调整余额 */
  update: (id, body) => api.put(`/api/overtime/${id}`, body),
  /** 删除单条：DELETE /api/overtime/{id}；已折算的调休一并冲减 */
  remove: (id) => api.del(`/api/overtime/${id}`),
  /**
   * 导入 Excel：POST /api/overtime/import（multipart，字段名 file）
   * @returns data: { success: number, failed: number, errors: string[] }
   */
  importFile: (file) => {
    const form = new FormData();
    form.append('file', file);
    return api.upload('/api/overtime/import', form);
  },
  /** 导出（不传查询条件=导出全部）：GET /api/overtime/export；无数据时后端返回 400，由页面提示 */
  exportFile: (params) => api.download('/api/overtime/export', params),
  /** 下载导入模板：GET /api/overtime/template */
  downloadTemplate: () => api.download('/api/overtime/template'),
  /**
   * 根据日期自动判定类型与系数：GET /api/holidays/resolve?date=YYYY-MM-DD
   * @returns data: { type, dayType, ratio, name, holidayName }（dayType/holidayName 为兼容别名，见 Overtime.vue resolveDay）
   */
  resolveDay: (date) => api.get('/api/holidays/resolve', { date }),
};

// ============ 调休使用记录 ============
/**
 * 调休使用记录（LeaveController）
 * 使用方：Leave.vue（调休使用记录主页面）、Holiday.vue（月度日历）、MainLayout.vue（预警消息）
 * @permission 前端权限码 leave:view（菜单）/ leave:add / leave:edit / leave:void / leave:import / leave:export
 */
export const leaveApi = {
  /**
   * 分页列表：GET /api/leave
   * @param {{userId?:number, name?:string, department?:string, from?:string, to?:string, includeVoid?:boolean, page?:number, size?:number}} params
   *        includeVoid=false 时后端过滤掉已作废（VOID）记录
   * @returns { data: LeaveRow[], total }；行字段含 userName、date、startTime、endTime、hours、status、remark
   */
  list: (params) => api.get('/api/leave', params),
  /**
   * 批量录入：POST /api/leave/batch
   * @param {Array<{userId:number, date:string, startTime:string, endTime:string, remark?:string}>} records
   * @param {boolean} [allowOverdraft=false] 余额不足时是否强制录入（由 Leave.vue 直接用 api.post 透传，见该文件注释）
   * @param {boolean} [allowRestDay=false] 所选日期属于休息日时是否放行；后端会提示「所选日期属于休息日…是否继续」，
   *        前端二次确认后带 true 重提即可（由 Leave.vue 直接用 api.post 透传，见该文件注释）
   * @returns data: { overdraftNames: string[] }，非空表示产生了透支并已生成预警消息
   */
  createBatch: (records) => api.post('/api/leave/batch', { records }),
  /** 单条录入：POST /api/leave */
  create: (body) => api.post('/api/leave', body),
  /** 修改单条：PUT /api/leave/{id}；后端按新旧时长差额自动调整余额 */
  update: (id, body) => api.put(`/api/leave/${id}`, body),
  /** 作废（二次确认后调用，恢复余额、物理数据保留）：POST /api/leave/{id}/void，body { reason } */
  void: (id, reason) => api.post(`/api/leave/${id}/void`, { reason }),
  /** 删除：DELETE /api/leave/{id} */
  remove: (id) => api.del(`/api/leave/${id}`),
  /** 导入 Excel：POST /api/leave/import（multipart，字段名 file）；返回 { success, failed, errors } */
  importFile: (file) => {
    const form = new FormData();
    form.append('file', file);
    return api.upload('/api/leave/import', form);
  },
  /** 导出：GET /api/leave/export；无数据时后端返回「当前查询结果无数据可导出」 */
  exportFile: (params) => api.download('/api/leave/export', params),
  /** 下载导入模板：GET /api/leave/template */
  downloadTemplate: () => api.download('/api/leave/template'),
  /**
   * 查询某人当前调休余额：GET /api/leave/balance?userId=
   * @param {number} [userId] 不传则查本人
   * @returns data: { balance: number, overdraft: boolean }
   */
  balance: (userId) => api.get('/api/leave/balance' + (userId ? `?userId=${userId}` : '')),
  /** 余额透支预警消息列表（管理员/录入员）：GET /api/leave/messages；data: [{ id, content, createdAt }] */
  messages: () => api.get('/api/leave/messages'),
  /** 标记消息已读：POST /api/leave/messages/{id}/read */
  readMessage: (id) => api.post(`/api/leave/messages/${id}/read`),
};

// ============ 节假日日历 ============
/**
 * 节假日日历（HolidayController）
 * 使用方：Holiday.vue（年度概览 + 月份维护）、Overtime.vue（按日期解析类型与系数）
 * @permission 前端权限码 holiday:view（菜单）/ holiday:manage（维护与刷新按钮）
 */
export const holidayApi = {
  /**
   * 年度概览：GET /api/holidays/year?year=YYYY
   * @returns data: 12 个月份卡片 [{ month:'YYYY-MM', label, workdayCount, restCount, manualCount }]
   */
  year: (year) => api.get('/api/holidays/year', { year }),
  /**
   * 某月详情：GET /api/holidays/month?month=YYYY-MM
   * @returns data: { month, label, days: [{ date, day, type, officialType, name, inMonth }] }
   *          days 含补齐整周用的非本月日期（inMonth=false，页面上不可编辑）
   */
  month: (month) => api.get('/api/holidays/month', { month }),
  /**
   * 保存某月变更（仅提交被修改的日期）：POST /api/holidays/month
   * @param {string} month 'YYYY-MM'
   * @param {Array<{date:string, type:'WORKDAY'|'RESTDAY'}>} changes
   * @returns data: { saved: number, warnings: string[] }；warnings 为高风险变更提示
   */
  saveMonth: (month, changes) => api.post('/api/holidays/month', { month, changes }),
  /**
   * 刷新官方数据：POST /api/holidays/refresh
   * @param {string} scope 'year' | 'month'
   * @param {string} value 年份或月份
   * @param {boolean} force 是否强制覆盖人工变更
   * @returns data: { refreshed: true, message } 或 { refreshed: false, conflicts: [{ date, currentType, currentTypeLabel }] }
   */
  refresh: (scope, value, force) => api.post('/api/holidays/refresh', { scope, value, force }),
  /**
   * 根据日期解析类型与系数：GET /api/holidays/resolve?date=YYYY-MM-DD
   * @returns data: { type, dayType, ratio, name, holidayName }；系数固定 法定工作日/补班日 0.5，法定休息日（含法定节假日）1
   */
  resolve: (date) => api.get('/api/holidays/resolve', { date }),
  /** 节假日原始条目列表：GET /api/holidays */
  list: () => api.get('/api/holidays'),
  /** 新增或更新某天：POST /api/holidays */
  upsert: (body) => api.post('/api/holidays', body),
  /** 删除某天：DELETE /api/holidays/{date} */
  remove: (date) => api.del(`/api/holidays/${date}`),
};

// ============ 系统日志 ============
/**
 * 系统日志（SystemLogController）
 * 使用方：SystemLog.vue
 * @permission 前端权限码 systemLog:view（菜单）
 */
export const systemLogApi = {
  /**
   * 分页查询：GET /api/system-logs
   * @param {{module?:string, action?:string, level?:'INFO'|'WARN'|'ERROR', keyword?:string, from?:string, to?:string, page?:number, size?:number}} params
   * @returns { data: LogRow[], total }；行字段含 createdAt、userRealName、username、module、action、target、detail、level、result、ip
   */
  list: (params) => api.get('/api/system-logs', params),
  /** 模块下拉选项：GET /api/system-logs/modules；data: string[] */
  modules: () => api.get('/api/system-logs/modules'),
};

/**
 * 系统配置（ConfigController）
 * 使用方：Config.vue
 * @permission 前端权限码 config:view（菜单）/ config:edit（编辑按钮）
 */
export const configApi = {
  /** 配置列表：GET /api/config；data: [{ id, key, value, description }]，如 overtime.dailyCap、leave.ratio.workday */
  list: () => api.get('/api/config'),
  /** 单个配置：GET /api/config/{key} */
  get: (key) => api.get(`/api/config/${key}`),
  /** 保存配置：POST /api/config，body { key, value, description }；key 存在则更新，修改后即时生效 */
  set: (body) => api.post('/api/config', body),
};

/**
 * 统计与看板数据（ReportController）
 * 使用方：Dashboard.vue（工作台）、Profile.vue（个人中心余额）、MainLayout.vue（顶栏余额/预警）、
 *        AllStaffLeave.vue（全员调休查看）、ChartStats.vue（统计图展示）
 * @permission 「统计报表」菜单已下线，本组接口不再绑定 report:view/report:export；
 *             后端对 dashboard 系列接口要求 ADMIN/CLERK 角色
 */
export const reportApi = {
  /**
   * 工作台/报表总览：GET /api/report/dashboard
   * @returns data: { userCount, totalBalance, overdraftCount, monthEarned, monthUsed, users: [{ id, name, username, department, balance, overdraft }] }
   *          仅管理员/录入员可调用；员工会收到「无权限」错误，Dashboard.vue 据此降级为 self 模式
   */
  dashboard: () => api.get('/api/report/dashboard'),
  /**
   * 个人余额：GET /api/report/balance?userId=
   * @param {number} [userId] 管理员/录入员可查他人；员工传了也会被后端改写为本人
   * @returns data: { balance: number, overdraft: boolean }
   */
  balance: (userId) => api.get('/api/report/balance' + (userId ? `?userId=${userId}` : '')),
  /** 报表导出地址常量（保留给「全员调休查看」等页面按需带 token 请求） */
  exportUrl: '/api/report/export',

  // ============ 全员调休查看 ============
  /**
   * 全员调休分页列表（name/department 模糊匹配，from/to 为空表示不限时间；已排除 ADMIN/CLERK）。
   * @param {{name?:string, department?:string, from?:string, to?:string, page?:number, size?:number}} params
   * @returns 行字段：{ userId, name, department, earnedHours, usedHours, balance,
   *                    trendMonths: [{ month: '2026-01', hours: 12.5 }] }（近 12 个月，空月补 0）
   */
  allStaffLeave: (params) => api.get('/api/report/all-staff-leave', params),
  /** 导出全员调休（条件全空=导出全部；无数据时后端返回 400，download 会抛出 message） */
  exportAllStaffLeave: (params) => api.download('/api/report/all-staff-leave/export', params),

  // ============ 趋势图 ============
  /**
   * 部门加班趋势：POST /api/report/trend/department
   * @param {{department?:string, fromMonth:string, toMonth:string}} body department 为空=全公司；跨度上限 24 个月（ChartStats.vue 会先校验）
   * @returns data: [{ month: 'YYYY-MM', hours: number }]
   */
  departmentTrend: (body) => api.post('/api/report/trend/department', body),
  /**
   * 员工加班趋势：POST /api/report/trend/employee
   * @param {{userId:number, from?:string, to?:string}} body 日期为 'YYYY-MM-DD'，from/to 不传默认最近 30 天；跨度上限 365 天
   * @returns data: [{ date: 'YYYY-MM-DD', hours: number }]
   */
  employeeTrend: (body) => api.post('/api/report/trend/employee', body),
  /**
   * 工作台图表：GET /api/report/dashboard/charts
   * @returns data: { positionPie: [{ name, value }], departmentBar: [{ name, value }], overdraftList: [{ id, name, department, balance, overdraft }] }
   *          接口失败时 Dashboard.vue 会用已加载的成员数据本地推导兜底
   */
  dashboardCharts: () => api.get('/api/report/dashboard/charts'),
};
