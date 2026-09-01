# 调休管家 (Holiday Rebalance Manager)

> 加班转调休一体化管理 Web 应用：把员工的加班时长按规则折算为可调休额度，覆盖加班录入、调休/请假审批、节假日历、全员额度看板与系统留痕。

## 功能概览

- **仪表盘 (Dashboard)**：个人/部门调休余额、加班分布、趋势概览。
- **节假日历 (Holiday Calendar)**：维护法定节假日与调休日，作为加班折算系数与出勤判定的依据。
- **加班 (Overtime)**：录入员工加班（起止时间、打卡时间），系统按当日系数自动折算调休时长；同一员工同一天相同起始时间防重复录入。
- **调休 / 请假 (Leave)**：发起调休或请假，扣减对应额度，支持审批流转。
- **统计报表 (Charts)**：按部门 / 按人员的加班与调休趋势、分布可视化。
- **全员调休查看 (All-Staff Leave)**：管理者视角的全员调休余额总览。
- **员工管理 (Employee)**：成员增删改、角色分配、状态冻结/解冻、密码重置。
- **系统日志 (System Log)**：关键操作留痕，便于审计追溯。
- **安全与并发**：管理员账号唯一且支持多设备同时登录；写操作全局串行并带冷却间隔，避免并发冲撞。

## 技术选型

| 层 | 技术 | 说明 |
|----|------|------|
| 后端 | Java 17 + Spring Boot | 主框架，提供 RESTful API 与事务管理 |
| 持久化 | MySQL | 业务数据主存储，JPA/Hibernate 管理表结构 |
| 前端 | Vue 3 + Element Plus | 组件化后台界面，响应式自适应布局 |
| 构建(前端) | Vite + npm | 开发热更新与生产打包 |
| 构建(后端) | Maven | 依赖管理与可执行 jar 打包 |
| 鉴权 | JWT | 无状态令牌，支持按角色授权 |

**选型理由**：Spring Boot + MySQL 组合成熟稳定、事务与 ORM 完备，适合带审批流与额度计算的业务流程；Vue 3 + Element Plus 上手快、组件丰富，能快速搭建规范的后台界面；JWT 无状态鉴权便于前后端分离部署。

## 本地运行

前置：JDK 17、Maven 3.9+、Node.js 22、MySQL 8.x。

```bash
# 1. 后端（默认 8600 端口）
cd server-java
mvn clean package -DskipTests
java -jar target/server-java-1.0.0.jar

# 2. 前端（开发模式，或构建后由后端静态托管）
cd web
npm install
npm run build        # 产物由后端 static 目录托管，无需单独起前端服务
# 或：npm run dev     # 独立开发服务器
```

访问 `http://localhost:8600`，默认管理员账号 `admin` / 初始密码 `Abc_123456`（首次登录需改密）。

> 配套脚本：`start.bat` 启动服务、`stop.bat` 停止服务（按实际环境调整路径）。

## 目录结构

```
holiday-rebalance-manager2.0/
├── server-java/   # Spring Boot 后端（API、服务、实体、仓储、拦截器）
├── web/           # Vue 3 前端源码与构建配置
├── start.bat      # 启动脚本
└── stop.bat       # 停止脚本
```

## 说明

- 数据库表结构由 JPA `ddl-auto: update` 自动维护，首次启动自动建表。
- 生产部署请修改 `application.yml` 中的 JWT 密钥、数据库连接与跨域来源等配置。
