package xiaobai.memory;

import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocatorMetric;
import io.netty.buffer.PoolArenaMetric;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.EventExecutor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerNetworkIo;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class MemoryOptimizer {
	private static final Logger LOGGER = LoggerFactory.getLogger("Mercury-Memory");
	private static final AtomicBoolean COMMON_INITIALIZED = new AtomicBoolean(false);
	private static final AtomicBoolean CLIENT_INITIALIZED = new AtomicBoolean(false);

	private static final AtomicLong LAST_TRIM_NANOS = new AtomicLong(0L);
	private static final AtomicLong LAST_GC_NANOS = new AtomicLong(0L);

	// Observability
	private static final AtomicLong TRIM_COUNT = new AtomicLong(0L);
	private static final AtomicLong GC_COUNT = new AtomicLong(0L);
	private static final AtomicLong LAST_TRIM_DURATION_NANOS = new AtomicLong(0L);
	private static volatile String LAST_TRIM_REASON = "";

	// Idle detection
	private static final AtomicLong LAST_SERVER_ACTIVE_NANOS = new AtomicLong(0L);
	private static final AtomicLong LAST_IDLE_TRIM_NANOS = new AtomicLong(0L);

	// Delayed trim
	private static final AtomicLong SCHEDULED_TRIM_AT_NANOS = new AtomicLong(0L);

	// Trend guard
	private static final AtomicLong LAST_TREND_SAMPLE_NANOS = new AtomicLong(0L);
	private static final AtomicLong LAST_TREND_SAMPLE_USED_BYTES = new AtomicLong(0L);

	// Netty event loops discovered
	private static final Set<EventExecutor> KNOWN_EVENT_EXECUTORS = Collections.newSetFromMap(new ConcurrentHashMap<>());

	// Defaults; can be overridden by system properties
	private static volatile double LOW_MEMORY_RATIO = 0.82; // used/max >= 82%
	private static volatile Duration MIN_TRIM_INTERVAL = Duration.ofSeconds(5);
	private static volatile Duration MIN_GC_INTERVAL = Duration.ofSeconds(30);
	private static volatile boolean ENABLE_EXPLICIT_GC = false; // default off to avoid heap expansion side-effects
	private static volatile boolean DEBUG_LOGS = false;
	private static volatile long TRIM_ON_IDLE_SECONDS = 30L; // idle time to trigger a trim
	private static volatile boolean ENABLE_ADAPTIVE_TREND_GUARD = true;
	private static volatile long TREND_GUARD_MIN_INCREASE_BYTES = 16L * 1024L * 1024L; // 16MB
	private static volatile boolean NETTY_TRIM_ALL_EVENT_LOOPS = true;
	private static volatile boolean INCLUDE_DIRECT_IN_RATIO = true;
	private static volatile double HYSTERESIS_MARGIN = 0.04;
	private static final AtomicBoolean HYSTERESIS_GATE_RAISED = new AtomicBoolean(false);

	// Auto-degrade settings
	private static volatile boolean AUTO_DEGRADE_ENABLED = false;
	private static volatile double AUTO_DEGRADE_HIGH_RATIO = 0.88;
	private static volatile double AUTO_DEGRADE_LOW_RATIO = 0.80;
	private static volatile long AUTO_DEGRADE_MIN_DURATION_SECONDS = 10L;
	private static volatile int AUTO_DEGRADE_VIEW_DISTANCE_DELTA = 1;
	private static volatile int AUTO_DEGRADE_SIM_DISTANCE_DELTA = 1;
	private static volatile boolean DEGRADE_ACTIVE = false;
	private static final AtomicLong LAST_HIGH_PRESSURE_NANOS = new AtomicLong(0L);
	private static final AtomicLong LAST_RELIEF_NANOS = new AtomicLong(0L);
	private static volatile int ORIG_VIEW_DISTANCE = -1;
	private static volatile int ORIG_SIM_DISTANCE = -1;

	private MemoryOptimizer() {}

	public static void initializeCommon() {
		if (!COMMON_INITIALIZED.compareAndSet(false, true)) return;

		configureFromSystemProperties();
		configureNettyAllocatorProperties();
		ShenandoahTuner.applyStartupTuningFromSystemProperties(DEBUG_LOGS);

		// Server lifecycle hooks for safe trim points
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			LAST_SERVER_ACTIVE_NANOS.set(System.nanoTime());
			tryCollectServerEventLoops(server);
			safeTrim("server_started");
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			KNOWN_EVENT_EXECUTORS.clear();
			safeTrim("server_stopping");
		});

		// Player disconnect → good moment to trim
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> safeTrim("player_disconnect"));

		// Server data reload → safe to trim after
		ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
			@Override
			public void reload(ResourceManager manager) {
				safeTrim("server_data_reloaded");
				scheduleDelayedTrim(Duration.ofMillis(200));
			}

			@Override
			public Identifier getFabricId() {
				return new Identifier("mercury", "memory_trim_server_data_common");
			}
		});

		// World unload → free old references and trim
		ServerWorldEvents.UNLOAD.register((server, world) -> {
			safeTrim("world_unload");
			scheduleDelayedTrim(Duration.ofMillis(200));
		});

		// Periodic server tick trimming under pressure + idle detection + delayed trims
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			maybeTrimMemory("server_tick");

			// Discover event loops if not collected yet (lazy)
			if (KNOWN_EVENT_EXECUTORS.isEmpty()) {
				tryCollectServerEventLoops(server);
			}

			// Idle detection
			try {
				boolean hasPlayers = !server.getPlayerManager().getPlayerList().isEmpty();
				long now = System.nanoTime();
				if (hasPlayers) {
					LAST_SERVER_ACTIVE_NANOS.set(now);
				} else {
					long idleNs = TRIM_ON_IDLE_SECONDS > 0 ? Duration.ofSeconds(TRIM_ON_IDLE_SECONDS).toNanos() : Long.MAX_VALUE;
					if (now - LAST_SERVER_ACTIVE_NANOS.get() >= idleNs) {
						long lastIdleTrim = LAST_IDLE_TRIM_NANOS.get();
						if (now - lastIdleTrim >= MIN_TRIM_INTERVAL.toNanos() && LAST_IDLE_TRIM_NANOS.compareAndSet(lastIdleTrim, now)) {
							safeTrim("idle_server");
						}
					}
				}
			} catch (Throwable ignored) {}

			// Auto-degrade is disabled in this build.
			DEGRADE_ACTIVE = false;
			LAST_HIGH_PRESSURE_NANOS.set(0L);
			LAST_RELIEF_NANOS.set(0L);

			// Delayed trim check
			long scheduledAt = SCHEDULED_TRIM_AT_NANOS.get();
			if (scheduledAt > 0L && System.nanoTime() >= scheduledAt && SCHEDULED_TRIM_AT_NANOS.compareAndSet(scheduledAt, 0L)) {
				safeTrim("delayed_trim");
			}
		});
	}

	public static void initializeClient() {
		if (!CLIENT_INITIALIZED.compareAndSet(false, true)) return;

		// Disable ImageIO disk cache to avoid extra memory mappings and buffers
		try {
			ImageIO.setUseCache(false);
		} catch (Throwable t) {
			if (DEBUG_LOGS) LOGGER.debug("ImageIO.setUseCache(false) failed: {}", t.toString());
		}

		// Client resources reload → trim immediately after to release old assets; also schedule slight delayed trim
		ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
			@Override
			public void reload(ResourceManager manager) {
				safeTrim("client_resources_reloaded");
				scheduleDelayedTrim(Duration.ofMillis(200));
			}

			@Override
			public Identifier getFabricId() {
				return new Identifier("mercury", "memory_trim_client");
			}
		});
	}

	// Exposed for client tick callback in client source set
	public static void clientTick() {
		maybeTrimMemory("client_tick");

		long scheduledAt = SCHEDULED_TRIM_AT_NANOS.get();
		if (scheduledAt > 0L && System.nanoTime() >= scheduledAt && SCHEDULED_TRIM_AT_NANOS.compareAndSet(scheduledAt, 0L)) {
			safeTrim("delayed_trim_client");
		}
	}

	private static void configureNettyAllocatorProperties() {
		// Apply conservative Netty allocator settings to reduce reserved memory without breaking gameplay
		setPropertyIfAbsent("io.netty.allocator.maxOrder", "8"); // smaller chunk size (~1MB)
		setPropertyIfAbsent("io.netty.allocator.useCacheForAllThreads", "false");
		setPropertyIfAbsent("io.netty.recycler.maxCapacityPerThread", "256");
		setPropertyIfAbsent("io.netty.recycler.linkCapacity", "16");

		// Additional conservative cache bounds and periodic trimming (where supported by Netty version)
		setPropertyIfAbsent("io.netty.allocator.smallCacheSize", "128");
		setPropertyIfAbsent("io.netty.allocator.normalCacheSize", "64");
		setPropertyIfAbsent("io.netty.allocator.maxCachedBufferCapacity", "131072"); // 128 KiB
		// Prefer millis property when available; harmless if ignored
		setPropertyIfAbsent("io.netty.allocator.cacheTrimIntervalMillis", "10000");
	}

	private static void configureFromSystemProperties() {
		LOW_MEMORY_RATIO = getDoubleProperty("mercury.lowMemoryRatio", LOW_MEMORY_RATIO, 0.5, 0.99);
		MIN_TRIM_INTERVAL = getDurationProperty("mercury.minTrimIntervalMillis", MIN_TRIM_INTERVAL);
		MIN_GC_INTERVAL = getDurationProperty("mercury.minGcIntervalMillis", MIN_GC_INTERVAL);
		ENABLE_EXPLICIT_GC = getBooleanProperty("mercury.enableExplicitGc", ENABLE_EXPLICIT_GC);
		DEBUG_LOGS = getBooleanProperty("mercury.debugLogs", DEBUG_LOGS);
		TRIM_ON_IDLE_SECONDS = getLongProperty("mercury.trimOnIdleSeconds", TRIM_ON_IDLE_SECONDS, 0, 86400);
		ENABLE_ADAPTIVE_TREND_GUARD = getBooleanProperty("mercury.enableAdaptiveTrendGuard", ENABLE_ADAPTIVE_TREND_GUARD);
		long trendMb = getLongProperty("mercury.trendGuardMinIncreaseMb", TREND_GUARD_MIN_INCREASE_BYTES / (1024L * 1024L), 1, 8192);
		TREND_GUARD_MIN_INCREASE_BYTES = trendMb * 1024L * 1024L;
		NETTY_TRIM_ALL_EVENT_LOOPS = getBooleanProperty("mercury.nettyTrimAllEventLoops", NETTY_TRIM_ALL_EVENT_LOOPS);
		INCLUDE_DIRECT_IN_RATIO = getBooleanProperty("mercury.includeDirectInRatio", INCLUDE_DIRECT_IN_RATIO);
		HYSTERESIS_MARGIN = getDoubleProperty("mercury.hysteresisMargin", HYSTERESIS_MARGIN, 0.0, 0.5);
		// Auto-degrade is disabled in this build; ignore related system properties.
	}

	private static boolean getBooleanProperty(String key, boolean def) {
		try {
			String val = System.getProperty(key);
			if (val == null) return def;
			return Boolean.parseBoolean(val);
		} catch (Throwable ignored) {
			return def;
		}
	}

	private static double getDoubleProperty(String key, double def, double min, double max) {
		try {
			String val = System.getProperty(key);
			if (val == null) return def;
			double parsed = Double.parseDouble(val);
			if (parsed < min || parsed > max) return def;
			return parsed;
		} catch (Throwable ignored) {
			return def;
		}
	}

	private static long getLongProperty(String key, long def, long min, long max) {
		try {
			String val = System.getProperty(key);
			if (val == null) return def;
			long parsed = Long.parseLong(val);
			if (parsed < min || parsed > max) return def;
			return parsed;
		} catch (Throwable ignored) {
			return def;
		}
	}

	private static Duration getDurationProperty(String key, Duration def) {
		try {
			String val = System.getProperty(key);
			if (val == null) return def;
			long millis = Long.parseLong(val);
			if (millis < 100) return def;
			return Duration.ofMillis(millis);
		} catch (Throwable ignored) {
			return def;
		}
	}

	private static void setPropertyIfAbsent(String key, String value) {
		try {
			if (System.getProperty(key) == null) {
				System.setProperty(key, value);
				if (DEBUG_LOGS) LOGGER.debug("Applied system property {}={} (absent)", key, value);
			}
		} catch (SecurityException ignored) {
		}
	}

	private static void maybeTrimMemory(String reason) {
		long now = System.nanoTime();
		long last = LAST_TRIM_NANOS.get();
		if (now - last < MIN_TRIM_INTERVAL.toNanos()) return;

		Runtime rt = Runtime.getRuntime();
		long heapUsed = rt.totalMemory() - rt.freeMemory();
		long heapMax = rt.maxMemory();

		long directUsed = 0L;
		long mappedUsed = 0L;
		try {
			long[] dm = getDirectAndMappedMemoryUsed();
			directUsed = dm[0];
			mappedUsed = dm[1];
		} catch (Throwable ignored) {}

		long used = heapUsed;
		long max = heapMax;

		if (INCLUDE_DIRECT_IN_RATIO) {
			used += directUsed;
			long directMax = estimateMaxDirectMemory();
			if (directMax > 0L) {
				max += directMax;
			}
		}

		if (max <= 0) return;

		double ratio = (double) used / (double) max;

		// Hysteresis gate reset when fully relieved
		if (ratio < LOW_MEMORY_RATIO - HYSTERESIS_MARGIN) {
			HYSTERESIS_GATE_RAISED.set(false);
		}

		// Adaptive trend guard: only trigger when memory is growing fast enough
		if (ENABLE_ADAPTIVE_TREND_GUARD) {
			long sampleNow = now;
			long sampleAt = LAST_TREND_SAMPLE_NANOS.get();
			long baseUsed = LAST_TREND_SAMPLE_USED_BYTES.get();
			if (sampleNow - sampleAt >= Math.min(Duration.ofSeconds(5).toNanos(), MIN_TRIM_INTERVAL.toNanos())) {
				LAST_TREND_SAMPLE_USED_BYTES.set(used);
				LAST_TREND_SAMPLE_NANOS.set(sampleNow);
				baseUsed = used; // refresh base instantly
			}
			long increase = used - baseUsed;
			if (ratio >= LOW_MEMORY_RATIO && increase >= TREND_GUARD_MIN_INCREASE_BYTES) {
				if (!HYSTERESIS_GATE_RAISED.compareAndSet(false, true)) return;
				if (!LAST_TRIM_NANOS.compareAndSet(last, now)) return;
				if (DEBUG_LOGS) LOGGER.debug("Mercury trim triggered (reason={}, used={}MB, max={}MB, ratio={}, increase={}MB, directUsed={}MB, mappedUsed={}MB)",
					reason, toMb(used), toMb(max), String.format("%.2f", ratio), toMb(increase), toMb(directUsed), toMb(mappedUsed));
				safeTrim(reason + "_low_memory:" + String.format("%.2f", ratio));
			}
			return;
		}

		if (ratio >= LOW_MEMORY_RATIO) {
			if (!HYSTERESIS_GATE_RAISED.compareAndSet(false, true)) return;
			if (!LAST_TRIM_NANOS.compareAndSet(last, now)) return;
			if (DEBUG_LOGS) LOGGER.debug("Mercury trim triggered (reason={}, used={}MB, max={}MB, ratio={}, directUsed={}MB, mappedUsed={}MB)",
				reason, toMb(used), toMb(max), String.format("%.2f", ratio), toMb(directUsed), toMb(mappedUsed));
			safeTrim(reason + "_low_memory:" + String.format("%.2f", ratio));
		}
	}

	private static void safeTrim(String reason) {
		long start = System.nanoTime();
		try {
			trimNettyCaches();
		} catch (Throwable t) {
			if (DEBUG_LOGS) LOGGER.debug("Netty cache trim failed ({}): {}", reason, t.toString());
		}

		LAST_TRIM_REASON = reason;
		TRIM_COUNT.incrementAndGet();
		LAST_TRIM_DURATION_NANOS.set(System.nanoTime() - start);

		// Throttle explicit GC; only if opt-in via system property
		if (!ENABLE_EXPLICIT_GC) return;

		long now = System.nanoTime();
		long lastGc = LAST_GC_NANOS.get();
		if (now - lastGc >= MIN_GC_INTERVAL.toNanos() && LAST_GC_NANOS.compareAndSet(lastGc, now)) {
			try {
				// If Shenandoah is active, request concurrent explicit GC to avoid long STWs
				if (ShenandoahTuner.isShenandoahActive() && !ShenandoahTuner.isExplicitGcInvokesConcurrent()) {
					ShenandoahTuner.setExplicitGcInvokesConcurrent(true);
				}
				System.gc();
				GC_COUNT.incrementAndGet();
				if (DEBUG_LOGS) LOGGER.debug("System.gc() invoked by Mercury (reason: {}, gc={})", reason, ShenandoahTuner.buildSummary());
			} catch (Throwable t) {
				if (DEBUG_LOGS) LOGGER.debug("System.gc() failed ({}): {}", reason, t.toString());
			}
		}
	}

	private static void trimNettyCaches() {
		// Trim allocator caches on the current thread; reduces retained direct/heap buffers
		PooledByteBufAllocator.DEFAULT.trimCurrentThreadCache();

		if (!NETTY_TRIM_ALL_EVENT_LOOPS || KNOWN_EVENT_EXECUTORS.isEmpty()) return;

		for (EventExecutor exec : KNOWN_EVENT_EXECUTORS) {
			try {
				exec.submit(PooledByteBufAllocator.DEFAULT::trimCurrentThreadCache);
			} catch (Throwable ignored) {}
		}
	}

	private static void scheduleDelayedTrim(Duration delay) {
		long when = System.nanoTime() + (delay != null ? delay.toNanos() : 0L);
		SCHEDULED_TRIM_AT_NANOS.set(when);
	}

	private static long toMb(long bytes) {
		return Math.round(bytes / (1024.0 * 1024.0));
	}

	// ===== Runtime controls (for commands / config screen) =====

	public static void setEnableExplicitGc(boolean enable) {
		ENABLE_EXPLICIT_GC = enable;
	}

	public static void setDebugLogs(boolean enable) {
		DEBUG_LOGS = enable;
	}

	public static void setLowMemoryRatio(double ratio) {
		if (ratio >= 0.5 && ratio <= 0.99) {
			LOW_MEMORY_RATIO = ratio;
		}
	}

	public static void setMinTrimIntervalMillis(long millis) {
		if (millis >= 100) {
			MIN_TRIM_INTERVAL = Duration.ofMillis(millis);
		}
	}

	public static void setMinGcIntervalMillis(long millis) {
		if (millis >= 100) {
			MIN_GC_INTERVAL = Duration.ofMillis(millis);
		}
	}

	public static void setTrimOnIdleSeconds(long seconds) {
		if (seconds >= 0 && seconds <= 86400) {
			TRIM_ON_IDLE_SECONDS = seconds;
		}
	}

	public static void setEnableAdaptiveTrendGuard(boolean enable) {
		ENABLE_ADAPTIVE_TREND_GUARD = enable;
	}

	public static void setTrendGuardMinIncreaseMb(long mb) {
		if (mb >= 1 && mb <= 8192) {
			TREND_GUARD_MIN_INCREASE_BYTES = mb * 1024L * 1024L;
		}
	}

	public static void setNettyTrimAllEventLoops(boolean enable) {
		NETTY_TRIM_ALL_EVENT_LOOPS = enable;
	}

	public static void setIncludeDirectInRatio(boolean include) {
		INCLUDE_DIRECT_IN_RATIO = include;
	}

	public static void setHysteresisMargin(double margin) {
		if (margin >= 0.0 && margin <= 0.5) {
			HYSTERESIS_MARGIN = margin;
		}
	}

	public static void setAutoDegradeEnabled(boolean enable) {
		AUTO_DEGRADE_ENABLED = false;
	}

	public static void setAutoDegradeHighRatio(double ratio) {
		// disabled
	}

	public static void setAutoDegradeLowRatio(double ratio) {
		// disabled
	}

	public static void setAutoDegradeMinDurationSeconds(long seconds) {
		// disabled
	}

	public static void setAutoDegradeViewDistanceDelta(int delta) {
		// disabled
	}

	public static void setAutoDegradeSimulationDistanceDelta(int delta) {
		// disabled
	}

	public static String getStatus() {
		Runtime rt = Runtime.getRuntime();
		long used = rt.totalMemory() - rt.freeMemory();
		long total = rt.totalMemory();
		long max = rt.maxMemory();
		double ratio = max > 0 ? (double) used / (double) max : 0.0;

		long activeHeapBytes = -1L;
		long activeDirectBytes = -1L;
		int numHeapArenas = -1;
		int numDirectArenas = -1;
		try {
			PooledByteBufAllocatorMetric m = PooledByteBufAllocator.DEFAULT.metric();
			if (m != null) {
				numHeapArenas = m.numHeapArenas();
				numDirectArenas = m.numDirectArenas();
				long h = 0L;
				for (PoolArenaMetric a : m.heapArenas()) h += a.numActiveBytes();
				long d = 0L;
				for (PoolArenaMetric a : m.directArenas()) d += a.numActiveBytes();
				activeHeapBytes = h;
				activeDirectBytes = d;
			}
		} catch (Throwable ignored) {}

		long directUsed = 0L;
		long mappedUsed = 0L;
		long directMax = -1L;
		try {
			long[] dm = getDirectAndMappedMemoryUsed();
			directUsed = dm[0];
			mappedUsed = dm[1];
			directMax = estimateMaxDirectMemory();
		} catch (Throwable ignored) {}

		long totalUsedCombined = used + (INCLUDE_DIRECT_IN_RATIO ? directUsed : 0L);
		long totalMaxCombined = max + ((INCLUDE_DIRECT_IN_RATIO && directMax > 0L) ? directMax : 0L);
		double totalRatio = totalMaxCombined > 0 ? (double) totalUsedCombined / (double) totalMaxCombined : ratio;

		return String.format(
			"enableExplicitGc=%s, debugLogs=%s, ratioThreshold=%.2f, hysteresisMargin=%.2f, includeDirectInRatio=%s, trimIntervalMs=%d, gcIntervalMs=%d, idleSeconds=%d, adaptiveTrend=%s, trendMinIncreaseMb=%d, nettyTrimAllEventLoops=%s, autoDegrade(enabled=%s,high=%.2f,low=%.2f,durationSec=%d,deltaVD=%d,deltaSD=%d,active=%s), gc={%s}, heapUsed=%dMB, heapTotal=%dMB, heapMax=%dMB, used/Max=%.2f, totalUsed/Max=%.2f, directUsed=%dMB, mappedUsed=%dMB, directMaxMb=%d, trims=%d, gcs=%d, lastTrimReason=%s, lastTrimDurationMs=%.2f, netty(heapArenas=%d,directArenas=%d,activeHeapMb=%d,activeDirectMb=%d), eventLoops=%d",
			Boolean.toString(ENABLE_EXPLICIT_GC),
			Boolean.toString(DEBUG_LOGS),
			LOW_MEMORY_RATIO,
			HYSTERESIS_MARGIN,
			Boolean.toString(INCLUDE_DIRECT_IN_RATIO),
			MIN_TRIM_INTERVAL.toMillis(),
			MIN_GC_INTERVAL.toMillis(),
			TRIM_ON_IDLE_SECONDS,
			Boolean.toString(ENABLE_ADAPTIVE_TREND_GUARD),
			TREND_GUARD_MIN_INCREASE_BYTES / (1024L * 1024L),
			Boolean.toString(NETTY_TRIM_ALL_EVENT_LOOPS),
			Boolean.toString(AUTO_DEGRADE_ENABLED),
			AUTO_DEGRADE_HIGH_RATIO,
			AUTO_DEGRADE_LOW_RATIO,
			AUTO_DEGRADE_MIN_DURATION_SECONDS,
			AUTO_DEGRADE_VIEW_DISTANCE_DELTA,
			AUTO_DEGRADE_SIM_DISTANCE_DELTA,
			Boolean.toString(DEGRADE_ACTIVE),
			ShenandoahTuner.buildSummary(),
			toMb(used),
			toMb(total),
			toMb(max),
			ratio,
			totalRatio,
			toMb(directUsed),
			toMb(mappedUsed),
			directMax > 0 ? toMb(directMax) : -1,
			TRIM_COUNT.get(),
			GC_COUNT.get(),
			LAST_TRIM_REASON,
			LAST_TRIM_DURATION_NANOS.get() / 1_000_000.0,
			numHeapArenas,
			numDirectArenas,
			activeHeapBytes > 0 ? toMb(activeHeapBytes) : -1,
			activeDirectBytes > 0 ? toMb(activeDirectBytes) : -1,
			KNOWN_EVENT_EXECUTORS.size()
		);
	}

	// ===== Exposed getters for config persistence =====
	public static boolean isEnableExplicitGc() {
		return ENABLE_EXPLICIT_GC;
	}

	public static boolean isDebugLogs() {
		return DEBUG_LOGS;
	}

	public static double getLowMemoryRatio() {
		return LOW_MEMORY_RATIO;
	}

	public static long getMinTrimIntervalMillis() {
		return MIN_TRIM_INTERVAL.toMillis();
	}

	public static long getMinGcIntervalMillis() {
		return MIN_GC_INTERVAL.toMillis();
	}

	public static long getTrimOnIdleSeconds() {
		return TRIM_ON_IDLE_SECONDS;
	}

	public static boolean isEnableAdaptiveTrendGuard() {
		return ENABLE_ADAPTIVE_TREND_GUARD;
	}

	public static long getTrendGuardMinIncreaseMb() {
		return TREND_GUARD_MIN_INCREASE_BYTES / (1024L * 1024L);
	}

	public static boolean isNettyTrimAllEventLoops() {
		return NETTY_TRIM_ALL_EVENT_LOOPS;
	}

	public static int getKnownEventLoopsCount() {
		return KNOWN_EVENT_EXECUTORS.size();
	}

	public static void trimNow(boolean withGc, String reason) {
		long start = System.nanoTime();
		try {
			trimNettyCaches();
		} catch (Throwable ignored) {}
		LAST_TRIM_REASON = reason != null ? reason : "command";
		TRIM_COUNT.incrementAndGet();
		LAST_TRIM_DURATION_NANOS.set(System.nanoTime() - start);
		if (withGc) {
			try {
				System.gc();
				GC_COUNT.incrementAndGet();
			} catch (Throwable ignored) {}
		}
	}

	// ===== Internal helpers =====
	private static void tryCollectServerEventLoops(MinecraftServer server) {
		try {
			Object networkIo = null;
			// Try direct accessor first
			try {
				Method m = MinecraftServer.class.getDeclaredMethod("getNetworkIo");
				m.setAccessible(true);
				networkIo = m.invoke(server);
			} catch (ReflectiveOperationException ignored) {}

			if (networkIo == null) {
				// Fallback: scan fields of server to find ServerNetworkIo
				for (Field f : MinecraftServer.class.getDeclaredFields()) {
					if (!ServerNetworkIo.class.isAssignableFrom(f.getType())) continue;
					f.setAccessible(true);
					networkIo = f.get(server);
					break;
				}
			}

			if (networkIo == null) return;

			collectEventExecutorsFromNetworkIo(networkIo);
		} catch (Throwable t) {
			if (DEBUG_LOGS) LOGGER.debug("Collect event loops failed: {}", t.toString());
		}
	}

	private static void collectEventExecutorsFromNetworkIo(Object networkIo) {
		try {
			// Find EventLoopGroup fields on ServerNetworkIo (boss/worker groups)
			for (Field f : networkIo.getClass().getDeclaredFields()) {
				if (!EventLoopGroup.class.isAssignableFrom(f.getType())) continue;
				f.setAccessible(true);
				Object groupObj = f.get(networkIo);
				if (groupObj instanceof EventLoopGroup group) {
					for (EventExecutor exec : group) {
						KNOWN_EVENT_EXECUTORS.add(exec);
					}
				}
			}
		} catch (Throwable t) {
			if (DEBUG_LOGS) LOGGER.debug("Reflect EventLoopGroup failed: {}", t.toString());
		}
	}

	private static long[] getDirectAndMappedMemoryUsed() {
		long direct = 0L;
		long mapped = 0L;
		try {
			for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
				String name = pool.getName();
				if ("direct".equalsIgnoreCase(name)) {
					direct = pool.getMemoryUsed();
				} else if ("mapped".equalsIgnoreCase(name)) {
					mapped = pool.getMemoryUsed();
				}
			}
		} catch (Throwable ignored) {}
		return new long[] { direct, mapped };
	}

	private static long estimateMaxDirectMemory() {
		try {
			Class<?> vm = Class.forName("sun.misc.VM");
			Method m = vm.getDeclaredMethod("maxDirectMemory");
			m.setAccessible(true);
			Object v = m.invoke(null);
			if (v instanceof Long) return (Long) v;
		} catch (Throwable ignored) {}
		try {
			Class<?> vm = Class.forName("jdk.internal.misc.VM");
			Method m = vm.getDeclaredMethod("maxDirectMemory");
			m.setAccessible(true);
			Object v = m.invoke(null);
			if (v instanceof Long) return (Long) v;
		} catch (Throwable ignored) {}
		return -1L;
	}

	public static boolean isIncludeDirectInRatio() {
		return INCLUDE_DIRECT_IN_RATIO;
	}

	public static double getHysteresisMargin() {
		return HYSTERESIS_MARGIN;
	}

	public static boolean isAutoDegradeEnabled() { return AUTO_DEGRADE_ENABLED; }
	public static double getAutoDegradeHighRatio() { return AUTO_DEGRADE_HIGH_RATIO; }
	public static double getAutoDegradeLowRatio() { return AUTO_DEGRADE_LOW_RATIO; }
	public static long getAutoDegradeMinDurationSeconds() { return AUTO_DEGRADE_MIN_DURATION_SECONDS; }
	public static int getAutoDegradeViewDistanceDelta() { return AUTO_DEGRADE_VIEW_DISTANCE_DELTA; }
	public static int getAutoDegradeSimulationDistanceDelta() { return AUTO_DEGRADE_SIM_DISTANCE_DELTA; }

	private static double currentTotalMemoryPressureRatio() {
		Runtime rt = Runtime.getRuntime();
		long heapUsed = rt.totalMemory() - rt.freeMemory();
		long heapMax = rt.maxMemory();
		long used = heapUsed;
		long max = heapMax;
		if (INCLUDE_DIRECT_IN_RATIO) {
			long[] dm = getDirectAndMappedMemoryUsed();
			used += dm[0];
			long directMax = estimateMaxDirectMemory();
			if (directMax > 0L) max += directMax;
		}
		if (max <= 0L) return 0.0;
		return (double) used / (double) max;
	}
} 