## Mercury

A Fabric 1.20.1 memory optimization mod.

[中文 README](README_zh.md)

### Features
- **Smart trimming**: Trims caches and pooled buffers at safe points (server start/stop, data reload, player disconnect, periodic ticks).
- **Idle-aware**: When the server is idle for a while, performs a light trim.
- **Netty tuning**: Applies conservative Netty allocator settings to lower off-heap usage.
- **Optional explicit GC**: Can trigger GC with safeguards and minimum intervals (off by default).
- **Adaptive guard**: Avoids aggressive trimming when memory usage is trending upward.
- **Commands + config**: In-game commands to inspect/tune, with a persistent config file.

### Requirements
- **Minecraft**: 1.20.1
- **Fabric Loader**: >= 0.16.14
- **Fabric API**: 0.92.6+1.20.1
- **Java**: 17+

### Installation
- Place the mod JAR into your `mods` folder on the client and/or dedicated server.
- Ensure Fabric Loader and Fabric API matching the versions above are installed.
- Download from the Releases page or build from source (below).

### Usage (Commands)
Requires permission level 2.

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

### Configuration
A properties file is created/read at `config/mercury.properties`. You can also pass JVM system properties (e.g., `-Dmercury.lowMemoryRatio=0.85`).

Defaults:
- **mercury.lowMemoryRatio**: 0.82
- **mercury.minTrimIntervalMillis**: 5000
- **mercury.minGcIntervalMillis**: 30000
- **mercury.enableExplicitGc**: false
- **mercury.debugLogs**: false
- **mercury.trimOnIdleSeconds**: 30
- **mercury.enableAdaptiveTrendGuard**: true
- **mercury.trendGuardMinIncreaseMb**: 16
- **mercury.nettyTrimAllEventLoops**: true

Example `mercury.properties`:

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

### Build from source
- Install JDK 17+.
- Build:
```bash
./gradlew build
# Windows:
./gradlew.bat build
```
- The mod JAR will be in `build/libs`.

### License
Released under the MIT License. See `LICENSE` for details.
