# BarkA

BarkA 是一款极简的 Android 推送客户端，用法与 [Bark](https://github.com/Finb/Bark) 一致：填入自架服务器地址、注册设备，然后通过 HTTP 请求把脚本、监控告警和各类通知推送到手机。

官方 Bark 服务端只支持 APNs，无法给 Android 推送，因此 BarkA 需要配合自建的 bark-server 分支使用（服务端增加了 Android 设备注册、WebSocket 推送和离线消息队列）。

- 黑白极简界面，对齐 iOS 分组列表风格，支持深色模式
- WebSocket 实时推送，断线时自动切换 HTTP 长轮询兜底
- 消息保存在本地 Room 数据库，支持搜索、分组、按时间筛选和按时间清空
- 支持 Markdown、链接、图片、消息更新与删除
- 可选 LSPosed 模块钩住 system_server 实现进程保活，无需前台常驻通知

## 工作原理

```
发送方 ──HTTP──▶ bark-server（自建）
                    │
          ┌─────────┴─────────┐
          ▼                   ▼
      WebSocket 在线      离线队列（queue.db，TTL 7 天）
          │                   │
          └─────────┬─────────┘
                    ▼
              BarkA（Android）
```

1. App 启动后向服务器 `POST /register`（`device_type=android`）注册，获得设备 Key。
2. 在线时通过 `wss://服务器/ws?device_key=<key>` 接收推送。
3. WebSocket 不可用时，App 通过 `GET /fetch?device_key=<key>` 长轮询拉取离线消息，处理后 `POST /ack` 确认。

## 服务端部署

服务端是 bark-server 的分支，不在本仓库中，可以去[仓库](https://github.com/dellevin/bark-server)查看，部署方式与上游一致：

- 使用项目提供的 Dockerfile 构建镜像（需要 Go 1.27+）
- 数据存储在 bbolt 数据库文件中，离线队列单独存放在 `data/queue.db`
- 服务端默认开启 35s 写超时以支持长轮询，反向代理（Nginx/Caddy）需要放行并调大 WebSocket 与长连接超时

## 安装与注册

支持 Android 8.0（API 26）及以上。从 [发布页面 ](https://github.com/dellevin/BarkA/releases)下载 APK 安装，或自行构建。

1. 打开 BarkA，在首页输入服务器地址（必须以 `http://` 或 `https://` 开头），点击「注册设备」。
2. 注册成功后首页显示服务器地址、设备 Key 和连接状态；服务器地址可随时点击修改，修改后会向新服务器重新注册。
3. 首页「使用示例」给出完整推送链接，可一键复制，或点「发送测试」直接发一条测试推送。
4. 首次使用请允许通知权限，否则系统通知无法弹出（消息仍会进入历史）。

## 发送消息

把 `https://你的服务器/<设备Key>` 作为推送地址，设备 Key 在首页查看，请妥善保管。

### GET 链接

最简单的形式，中文和特殊字符需要 URL 编码：

```text
https://你的服务器/<设备Key>/测试标题/测试内容?group=BarkA
```

在浏览器直接打开，或用 curl：

```bash
curl 'https://你的服务器/<设备Key>/测试标题/测试内容'
```

### POST JSON

```bash
curl -X POST 'https://你的服务器/push' \
  -H 'Content-Type: application/json' \
  -d '{
    "device_key": "<设备Key>",
    "title": "备份任务",
    "body": "备份已完成",
    "group": "work"
  }'
```

### 常用参数

| 参数                 | 说明                                                      |
| -------------------- | --------------------------------------------------------- |
| `title` / `body` | 标题和正文，标题可省略；正文中`\n` 为换行               |
| `markdown`         | Markdown 正文，存在时优先于`body`，详情页渲染           |
| `group`            | 消息分组，历史页可按分组筛选                              |
| `url`              | 点击通知打开的链接                                        |
| `image`            | 图片 URL 或`data:image/...;base64,...`，单张不超过 8 MB |
| `icon`             | 通知小图标地址                                            |
| `id`               | 自定义消息标识，相同`id` 为更新而非新增，最长 256 字节  |
| `delete`           | 设为`"1"` 时删除对应 `id` 的消息和系统通知            |
| `isArchive`        | 默认入库；设为`"0"` 只弹通知、不进历史                  |

参数同时支持 JSON body、表单和 URL 查询参数；`sound`、`badge` 为兼容 Bark 保留，Android 端不生效，提示音由系统通知渠道决定。

### 更新与删除消息

```json
{"device_key":"<Key>","id":"backup","title":"备份任务","body":"正在备份"}
{"device_key":"<Key>","id":"backup","title":"备份任务","body":"备份已完成"}
{"device_key":"<Key>","id":"backup","delete":"1"}
```

## 消息管理

底部「消息」页保存全部历史：

- 关键字搜索标题和正文，支持按分组筛选
- 时间筛选：今天 / 最近 7 天 / 最近 30 天 / 自定义日期区间
- 清空按钮支持：清空全部、删除 7 天前、删除 30 天前、自定义日期之前
- 单条消息仅支持左滑，露出删除按钮后点击才会删除；点开可复制正文、打开链接、保存或分享图片
- 历史只存在本机，卸载或清除数据会一并删除

## LSPosed 保活（可选）

BarkA 同时是一个 Xposed 模块。在 LSPosed 中启用模块并勾选「系统框架」作用域后，模块会钩住 system_server 并通过 Binder 桥接维持 App 进程存活，从而不需要前台常驻通知。

- 不装 LSPosed 也能正常使用，只是普通后台 Service 在部分国产 ROM 上可能被系统回收
- 首页状态行显示 `system_server：已绑定/未绑定`，用于确认模块是否生效
- 开机自启由 `RECEIVE_BOOT_COMPLETED` 广播触发，已注册设备开机后会自动重连

## 权限说明

| 权限                                    | 用途                                   |
| --------------------------------------- | -------------------------------------- |
| `INTERNET` / `ACCESS_NETWORK_STATE` | 连接服务器、WebSocket 与长轮询         |
| `POST_NOTIFICATIONS`                  | Android 13+ 弹出系统通知               |
| `RECEIVE_BOOT_COMPLETED`              | 开机后自动恢复推送连接                 |
| `WAKE_LOCK`                           | 收到消息处理期间短暂唤醒，避免休眠中断 |

## 从源码构建

需要 JDK 17、Gradle 8.13（或使用 IDE 自带 wrapper）：

```bash
gradle :app:assembleDebug
```

版本号由根目录 `VERSION` 文件（`major.minor.patch`）统一管理，构建时自动换算为 versionCode。Release 构建通过 `local.properties` 或环境变量传入签名信息：`RELEASE_STORE_FILE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`。

## 收不到消息时

1. 确认首页状态为「WebSocket：已连接」；未连接时检查服务器地址和网络
2. 点首页「发送测试」验证服务器到设备的整条链路
3. 检查系统通知权限、勿扰模式和电池优化是否限制了后台网络
4. 依赖后台保活的设备建议安装 LSPosed 模块；离线消息在服务端队列保留 7 天，重新上线后自动补发

## 反馈

遇到问题可提交 [Issue](https://github.com/dellevin/BarkA/issues)，附上设备型号、Android 版本、App 版本和复现步骤。截图与日志中请隐藏设备 Key 和推送链接。
