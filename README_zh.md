## Mercury

一个用于 Fabric 1.20.1 的内存优化 Mod。

[English README](README.md)

### 功能特性
- **智能修剪**：在安全时机（服务端启动/停止、数据重载、玩家离线、周期性 tick）修剪缓存与池化内存。
- **空闲感知**：服务器空闲一段时间后进行轻量修剪。
- **Netty 调优**：应用保守的 Netty 分配器设置，降低堆外内存占用。
- **可选显式 GC**：可在最小间隔与保护下触发 GC（默认关闭）。
- **趋势保护**：当内存使用量上升趋势明显时，避免过度修剪导致抖动。
- **命令与配置**：提供游戏内命令查看/调参，并支持持久化配置文件。

### 运行环境
- **Minecraft**：1.20.1
- **Fabric Loader**：>= 0.16.14
- **Fabric API**：0.92.6+1.20.1
- **Java**：17+

### 安装
- 将本 Mod 的 JAR 放入客户端与/或专用服务器的 `mods` 文件夹。
- 安装匹配版本的 Fabric Loader 与 Fabric API。
- 可从 Releases 下载，或自行构建（见下文）。

### 使用（命令）
需要权限等级 2。

```text
/mercury status
/mercury trim [withGc]
/mercury debug on|off
/mercury gc on|off
/mercury set ratio <0.5..0.99>
/mercury set trimIntervalMs <ms>
/mercury set gcIntervalMs <ms>
/mercury set idleSeconds <0..86400>
/mercury set adaptiveTrend on|off
/mercury set trendMinIncreaseMb <1..8192>
/mercury set nettyTrimAllEventLoops on|off
/mercury save
/mercury reload
```

### 配置
配置文件位于 `config/mercury.properties`。也可通过 JVM 启动参数指定（例如 `-Dmercury.lowMemoryRatio=0.85`）。

默认值：
- **mercury.lowMemoryRatio**：0.82
- **mercury.minTrimIntervalMillis**：5000
- **mercury.minGcIntervalMillis**：30000
- **mercury.enableExplicitGc**：false
- **mercury.debugLogs**：false
- **mercury.trimOnIdleSeconds**：30
- **mercury.enableAdaptiveTrendGuard**：true
- **mercury.trendGuardMinIncreaseMb**：16
- **mercury.nettyTrimAllEventLoops**：true

示例 `mercury.properties`：

```properties
# .minecraft/config/mercury.properties
mercury.lowMemoryRatio=0.82
mercury.minTrimIntervalMillis=5000
mercury.minGcIntervalMillis=30000
mercury.enableExplicitGc=false
mercury.debugLogs=false
mercury.trimOnIdleSeconds=30
mercury.enableAdaptiveTrendGuard=true
mercury.trendGuardMinIncreaseMb=16
mercury.nettyTrimAllEventLoops=true
```

### 源码构建
- 安装 JDK 17+。
- 执行：
```bash
./gradlew build
# Windows：
./gradlew.bat build
```
- 产物位于 `build/libs`。

### 许可协议
本项目以 MIT License 开源，详见 `LICENSE`。 