# SnapshotSync

服务端把模组/配置打包发布，客户端只下载真正改动过的文件。

NeoForge 1.21.1 · Minecraft 1.21.1

## 功能

- **只下变化** — 按 SHA-256 比对，内容没变的文件不重复传输
- **并行下载** — 大文件分块多连接，小文件之间也并行
- **两种打包模式** — 空闲时打包 / 固定间隔打包，改配置切换
- **任意目录** — 可打包游戏根目录下任何文件夹（`mods`、`config`、`kubejs`、`patchouli_books`、`shaderpacks` 等）
- **无变化不重发** — 内容一致时完全不发布，客户端零流量
- **失败可回滚** — 替换前备份，失败自动还原
- **中英双语**

## 使用

服务端和客户端都把 jar 放进 `mods/`。

玩家在多人游戏界面点「更新」按钮，检查并下载，完成后重启客户端即可。

```
/snapsync status     查看状态
/snapsync rebuild    立即重新打包
/snapsync port       查看下载端口
/snapsync pull       客户端开始同步
```

## 配置

`config/snapsync-common.toml`：

```toml
# 打包模式：IDLE（空闲时）| INTERVAL（固定间隔）
packMode = "IDLE"

# INTERVAL 模式
intervalMinutes = 30        # 间隔分钟数
buildOnStart    = true      # 启动时先打一次

# IDLE 模式
idleDelaySeconds        = 60     # 空置多久开始打包
minTpsForSnapshot       = 19.0   # TPS 低于此值推迟
snapshotCooldownSeconds = 300    # 两次打包最小间隔

# 打包范围
includeFolders = ["mods", "config", "kubejs", "resourcepacks"]
excludeMods    = []         # 不下发给客户端的 jar 文件名

# 下载
downloadPortOffset     = 1      # 下载端口 = 游戏端口 + 此值
fixedDownloadPort      = 0      # >0 则用绝对端口
maxParallelConnections = 4      # 并行连接数
chunkSizeKb            = 512    # 分块大小

# 其他
rehashEveryBuild = false    # true = 禁用哈希缓存
autoApplyOnJoin  = false    # true = 进服自动同步
```

## 注意

- **首次部署仍需手动装完整客户端包。** 缺少会阻断连接的模组时，客户端会被拦在协商阶段，走不到下载那一步。
- **更新 mod 后必须重启游戏**，jar 无法在运行中替换。
- Windows 已验证；Linux 代码已适配但未实机验证；Android 不支持自动安装。

## 构建

需要 JDK 21。

```bash
./gradlew build
```

产物：`build/libs/snapsync-2.0.0.jar`

## 许可证

MIT
