# ModSync

服务端把指定模组下发给客户端；玩家在多人游戏界面点「下载服务端模组」，
一键补齐缺失的 jar，重启游戏即可进入。

- 目标环境：**Fabric + Minecraft 26.1.2 + Java 25**
- 依赖：fabric-loader / fabric-api

---

## 目录结构

```
mod更新同步/
├─ ModSync/                     源码
│  ├─ src/main/java/com/modsync/
│  │  ├─ ModSync.java           服务端入口
│  │  ├─ ModSyncConfig.java     配置读写
│  │  ├─ server/DownloadServer  下载服务（自定义 TCP 协议）
│  │  ├─ net/ModSyncProtocol    线上协议定义
│  │  ├─ net/DownloadInfoNet    配置阶段下发下载地址
│  │  └─ client/                客户端界面与下载器
│  ├─ src/main/resources/
│  │  ├─ fabric.mod.json
│  │  └─ assets/modsync/lang/   中英文
│  └─ build/libs/modsync-1.0.0.jar
│
├─ server1/                     测试服务器
│  ├─ start.bat
│  ├─ mods/
│  └─ config/modsync/           ★ 要下发的模组放这里
│
├─ 构建ModSync.bat
└─ .gradle-home-dists/          Gradle 9.8.2（离线可用）
```

---

## 端口

下载服务**独立占用一个端口，绝不碰游戏端口**：

| 用途 | 端口 | 说明 |
|---|---|---|
| 游戏 | 25565 | 由 zstdnet 或 MC 自己监听 |
| 下载 | **25580** | ModSync，与游戏隔离 |

`config/modsync.json`：

```json
{
  "multiplex": false,
  "listenPort": 25580,
  "gameTargetPort": 0,
  "publicHost": "",
  "publicPort": 50255,
  "clientAddress": "",
  "clientPort": 0
}
```

- `listenPort` 服务端本地监听端口
- `publicPort` 告诉客户端该连哪个**外部**端口（FRP 的远程端口）
- 客户端配置里用 `clientPort` 填外部端口

---

## 为什么用自定义协议而不是 HTTP

SakuraFrp 等隧道服务只转发裸 TCP，**会拦截 HTTP 请求**并直接返回它自己的 501 页面，
导致下载请求永远到不了服务器。因此改用 `MODSYNC1` 开头的自定义协议，不会被拦。

协议格式见 `ModSyncProtocol` 类注释。

---

## 配置阶段的坑（重要）

Fabric 的 `ConfigurationTask` **必须在 `start()` 里调用 `completeTask()`**，
否则配置阶段永不结束，玩家会卡在"加入世界中"。

这是本项目踩过的最隐蔽的坑：任务发了包但没标记完成，客户端一直等。

---

## 构建

双击 `构建ModSync.bat`，产物 `ModSync/build/libs/modsync-1.0.0.jar`。

## 部署

| 目标 | 路径 |
|---|---|
| 服务端 | `D:\Server\26.1.2\mods\modsync-1.0.0-fabric-26.1.2.jar` |
| 客户端 | `.minecraft\versions\26.1.2-Fabric 0.19.5\mods\` 同名 |

## 控制下发哪些模组

只增删 `config/modsync/` 里的 jar：

- 放进去 → 客户端能下到
- 删掉 → 不再下发

**注意**：只放客户端能独立运行的模组。服务端专用（`servux`、`minimotd`、`spark`）
和依赖缺失的（如 `syncmatica` 需要 `litematica`，若玩家没有会崩）不要放。

---

## 注意事项

- 26.1.2 文字颜色必须用 **8 位 ARGB**（`0xFFFFFFFF`），6 位会因 alpha=0 不显示
- `Screen.resize()` 只调 `repositionElements()`，自定义界面需 override 它重建控件
- 多行文本要按 `\n` 拆开逐行绘制，否则换行符会渲染成方块乱码
- 重启服务器前确认旧的 java 进程已退出，否则 `FAILED TO BIND TO PORT`
