# AI Mental Health Assistant (AMHA)

AI 心理健康助手 — 基于 Vue 3 的心理健康服务平台，提供 AI 心理咨询、情绪日记、心理健康知识库等功能，并配备后台数据管理面板。

## 功能概览

### 用户端

- **AI 心理咨询** — 与 AI 心理助手实时对话，获得情绪支持与建议
- **情绪日记** — 记录每日情绪状态，追踪心情变化趋势
- **心理健康知识库** — 浏览、搜索心理健康科普文章
- **用户认证** — 注册、登录与个人信息管理

### 管理端

- **数据仪表盘** — 用户活跃度、情绪趋势、咨询统计等可视化分析
- **知识文章管理** — 文章的增删改查、分类管理、状态控制
- **咨询记录管理** — 查看所有用户咨询会话与消息详情
- **情感分析管理** — 查看用户情绪日记与 AI 分析结果

## 技术栈

| 类别       | 技术                                                                 |
| ---------- | -------------------------------------------------------------------- |
| 框架       | Vue 3 + TypeScript                                                   |
| 构建工具   | Vite 8                                                              |
| UI 组件库  | Element Plus                                                        |
| 状态管理   | Pinia                                                               |
| 路由       | Vue Router 4                                                        |
| 图表       | ECharts 6                                                           |
| HTTP 请求  | Axios                                                               |
| 富文本编辑 | WangEditor 5                                                        |
| 样式       | SCSS                                                                |

## 项目结构

```
src/
├── api/          # API 接口层
│   ├── admin.ts  # 管理端接口
│   └── user.ts   # 用户端接口
├── assets/       # 静态资源（图片、图标）
├── components/   # 公共组件
│   ├── ArticleDialog.vue
│   ├── MarkdownRenderer.vue
│   ├── PageHead.vue
│   ├── RichTextEditor.vue
│   └── tableSearch.vue
├── config/       # 配置文件
├── layouts/      # 布局组件
│   ├── admin/    # 管理端布局
│   ├── user/     # 用户端布局
│   └── AuthLayout.vue
├── router/       # 路由配置
├── stores/       # Pinia 状态管理
├── styles/       # 全局样式
├── types/        # TypeScript 类型定义
├── utils/        # 工具函数（Axios 封装）
├── views/        # 页面视图
│   ├── admin/    # 管理端页面
│   └── user/     # 用户端页面
├── App.vue
└── main.ts
```

## 环境要求

- Node.js >= 18（推荐 v24）
- pnpm（推荐）或 npm

## 快速开始

```bash
# 安装依赖
pnpm install

# 启动开发服务器
pnpm dev

# 构建生产版本
pnpm build

# 预览构建结果
pnpm preview
```

开发服务器默认运行在 `http://localhost:5173`，API 请求通过 Vite 代理转发至后端服务。

## 后端接口

后端 API 文档：https://xsl1e23zpk.apifox.cn/

文件服务器地址：`http://159.75.169.224:1235`

## 角色说明

| 角色     | userType | 权限                           |
| -------- | -------- | ------------------------------ |
| 普通用户 | 1        | 访问用户端功能                 |
| 管理员   | 2        | 访问管理端面板，管理内容与数据 |
