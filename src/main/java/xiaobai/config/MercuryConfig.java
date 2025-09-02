package xiaobai.config;

import net.fabricmc.loader.api.FabricLoader;
import xiaobai.memory.MemoryOptimizer;
import xiaobai.memory.ShenandoahTuner;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class MercuryConfig {
	private static final String FILE_NAME = "mercury.properties";

	private MercuryConfig() {}

	public static void load() {
		Path configDir = FabricLoader.getInstance().getConfigDir();
		Path file = configDir.resolve(FILE_NAME);
		if (!Files.exists(file)) return;

		Properties props = new Properties();
		try (InputStream in = Files.newInputStream(file)) {
			props.load(in);
			apply(props);
		} catch (IOException ignored) {
		}
	}

	public static void save() {
		Path configDir = FabricLoader.getInstance().getConfigDir();
		Path file = configDir.resolve(FILE_NAME);
		Properties props = capture();
		try {
			if (!Files.exists(configDir)) {
				Files.createDirectories(configDir);
			}
			try (OutputStream out = Files.newOutputStream(file)) {
				props.store(out, "Mercury server optimization settings");
			}
		} catch (IOException ignored) {
		}
	}

	private static void apply(Properties p) {
		String ratio = p.getProperty("mercury.lowMemoryRatio");
		if (ratio != null) {
			try {
				MemoryOptimizer.setLowMemoryRatio(Double.parseDouble(ratio));
			} catch (NumberFormatException ignored) {}
		}

		String trimMs = p.getProperty("mercury.minTrimIntervalMillis");
		if (trimMs != null) {
			try {
				MemoryOptimizer.setMinTrimIntervalMillis(Long.parseLong(trimMs));
			} catch (NumberFormatException ignored) {}
		}

		String gcMs = p.getProperty("mercury.minGcIntervalMillis");
		if (gcMs != null) {
			try {
				MemoryOptimizer.setMinGcIntervalMillis(Long.parseLong(gcMs));
			} catch (NumberFormatException ignored) {}
		}

		String enableGc = p.getProperty("mercury.enableExplicitGc");
		if (enableGc != null) {
			MemoryOptimizer.setEnableExplicitGc(Boolean.parseBoolean(enableGc));
		}

		String debug = p.getProperty("mercury.debugLogs");
		if (debug != null) {
			MemoryOptimizer.setDebugLogs(Boolean.parseBoolean(debug));
		}

		String idleSec = p.getProperty("mercury.trimOnIdleSeconds");
		if (idleSec != null) {
			try {
				MemoryOptimizer.setTrimOnIdleSeconds(Long.parseLong(idleSec));
			} catch (NumberFormatException ignored) {}
		}

		String adaptive = p.getProperty("mercury.enableAdaptiveTrendGuard");
		if (adaptive != null) {
			MemoryOptimizer.setEnableAdaptiveTrendGuard(Boolean.parseBoolean(adaptive));
		}

		String trendMb = p.getProperty("mercury.trendGuardMinIncreaseMb");
		if (trendMb != null) {
			try {
				MemoryOptimizer.setTrendGuardMinIncreaseMb(Long.parseLong(trendMb));
			} catch (NumberFormatException ignored) {}
		}

		String trimAllEventLoops = p.getProperty("mercury.nettyTrimAllEventLoops");
		if (trimAllEventLoops != null) {
			MemoryOptimizer.setNettyTrimAllEventLoops(Boolean.parseBoolean(trimAllEventLoops));
		}

		String includeDirect = p.getProperty("mercury.includeDirectInRatio");
		if (includeDirect != null) {
			MemoryOptimizer.setIncludeDirectInRatio(Boolean.parseBoolean(includeDirect));
		}

		String hysteresis = p.getProperty("mercury.hysteresisMargin");
		if (hysteresis != null) {
			try {
				MemoryOptimizer.setHysteresisMargin(Double.parseDouble(hysteresis));
			} catch (NumberFormatException ignored) {}
		}

		// Optional Shenandoah tuning (best-effort)
		if (ShenandoahTuner.isShenandoahActive()) {
			String egc = p.getProperty("mercury.shenandoah.explicitGcConcurrent");
			if (egc != null) ShenandoahTuner.setExplicitGcInvokesConcurrent(Boolean.parseBoolean(egc));
			String uncommit = p.getProperty("mercury.shenandoah.enableUncommit");
			if (uncommit != null) ShenandoahTuner.setUncommitEnabled(Boolean.parseBoolean(uncommit));
			String uncommitDelay = p.getProperty("mercury.shenandoah.uncommitDelayMs");
			if (uncommitDelay != null) {
				try { ShenandoahTuner.setUncommitDelayMs(Long.parseLong(uncommitDelay)); } catch (NumberFormatException ignored) {}
			}
			String heur = p.getProperty("mercury.shenandoah.heuristics");
			if (heur != null) ShenandoahTuner.setHeuristics(heur);
			String thr = p.getProperty("mercury.shenandoah.garbageThreshold");
			if (thr != null) {
				try { ShenandoahTuner.setGarbageThreshold(Integer.parseInt(thr)); } catch (NumberFormatException ignored) {}
			}
			String gInt = p.getProperty("mercury.shenandoah.guaranteedGcIntervalMs");
			if (gInt != null) {
				try { ShenandoahTuner.setGuaranteedGcIntervalMs(Long.parseLong(gInt)); } catch (NumberFormatException ignored) {}
			}
		}

		// Auto-degrade is disabled; ignore related config keys if present.
	}

	private static Properties capture() {
		Properties p = new Properties();
		p.setProperty("mercury.lowMemoryRatio", Double.toString(MemoryOptimizer.getLowMemoryRatio()));
		p.setProperty("mercury.minTrimIntervalMillis", Long.toString(MemoryOptimizer.getMinTrimIntervalMillis()));
		p.setProperty("mercury.minGcIntervalMillis", Long.toString(MemoryOptimizer.getMinGcIntervalMillis()));
		p.setProperty("mercury.enableExplicitGc", Boolean.toString(MemoryOptimizer.isEnableExplicitGc()));
		p.setProperty("mercury.debugLogs", Boolean.toString(MemoryOptimizer.isDebugLogs()));
		p.setProperty("mercury.trimOnIdleSeconds", Long.toString(MemoryOptimizer.getTrimOnIdleSeconds()));
		p.setProperty("mercury.enableAdaptiveTrendGuard", Boolean.toString(MemoryOptimizer.isEnableAdaptiveTrendGuard()));
		p.setProperty("mercury.trendGuardMinIncreaseMb", Long.toString(MemoryOptimizer.getTrendGuardMinIncreaseMb()));
		p.setProperty("mercury.nettyTrimAllEventLoops", Boolean.toString(MemoryOptimizer.isNettyTrimAllEventLoops()));
		p.setProperty("mercury.includeDirectInRatio", Boolean.toString(MemoryOptimizer.isIncludeDirectInRatio()));
		p.setProperty("mercury.hysteresisMargin", Double.toString(MemoryOptimizer.getHysteresisMargin()));

		// Persist Shenandoah tuning snapshot (best-effort)
		p.setProperty("mercury.shenandoah.gc", ShenandoahTuner.buildSummary());
		if (ShenandoahTuner.isShenandoahActive()) {
			p.setProperty("mercury.shenandoah.explicitGcConcurrent", Boolean.toString(ShenandoahTuner.isExplicitGcInvokesConcurrent()));
			Boolean uncommit = ShenandoahTuner.isUncommitEnabled();
			if (uncommit != null) p.setProperty("mercury.shenandoah.enableUncommit", Boolean.toString(uncommit));
			Long d = ShenandoahTuner.getUncommitDelayMs();
			if (d != null) p.setProperty("mercury.shenandoah.uncommitDelayMs", Long.toString(d));
			String heur = ShenandoahTuner.getHeuristics();
			if (heur != null) p.setProperty("mercury.shenandoah.heuristics", heur);
			Integer thr = ShenandoahTuner.getGarbageThreshold();
			if (thr != null) p.setProperty("mercury.shenandoah.garbageThreshold", Integer.toString(thr));
			Long gInt = ShenandoahTuner.getGuaranteedGcIntervalMs();
			if (gInt != null) p.setProperty("mercury.shenandoah.guaranteedGcIntervalMs", Long.toString(gInt));
		}

		// Auto-degrade is disabled; do not persist related keys.
		return p;
	}
} 