package xiaobai.memory;

import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.VMOption;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Utilities for detecting Shenandoah GC and tuning its HotSpot flags at runtime (best-effort).
 * All operations are safe and no-op on non-Shenandoah or when options are unavailable.
 */
public final class ShenandoahTuner {
	private static volatile Boolean CACHED_IS_SHENANDOAH = null;
	private static final String[] SHEN_GC_NAME_MARKERS = new String[] {
		"Shenandoah", // matches typical names: "Shenandoah Cycles", "Shenandoah Pauses"
	};

	private ShenandoahTuner() {}

	public static boolean isShenandoahActive() {
		Boolean cached = CACHED_IS_SHENANDOAH;
		if (cached != null) return cached;
		boolean detected = false;
		try {
			for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
				String name = gc.getName();
				if (name == null) continue;
				String lower = name.toLowerCase(Locale.ROOT);
				for (String m : SHEN_GC_NAME_MARKERS) {
					if (lower.contains(m.toLowerCase(Locale.ROOT))) {
						detected = true;
						break;
					}
				}
			}
		} catch (Throwable ignored) {}
		CACHED_IS_SHENANDOAH = detected;
		return detected;
	}

	public static String currentGcNames() {
		try {
			List<String> names = new ArrayList<>();
			for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
				if (gc.getName() != null) names.add(gc.getName());
			}
			return String.join("+", names);
		} catch (Throwable t) {
			return "unknown";
		}
	}

	private static HotSpotDiagnosticMXBean getDiag() {
		try {
			return ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
		} catch (Throwable t) {
			return null;
		}
	}

	private static String getVmOptionValue(String name) {
		try {
			HotSpotDiagnosticMXBean diag = getDiag();
			if (diag == null) return null;
			VMOption opt = diag.getVMOption(name);
			return opt != null ? opt.getValue() : null;
		} catch (Throwable t) {
			return null;
		}
	}

	private static boolean setVmOptionValue(String name, String value) {
		try {
			HotSpotDiagnosticMXBean diag = getDiag();
			if (diag == null) return false;
			diag.setVMOption(name, value);
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	// ===== Shenandoah-specific helpers =====

	public static boolean isExplicitGcInvokesConcurrent() {
		String v = getVmOptionValue("ExplicitGCInvokesConcurrent");
		return "true".equalsIgnoreCase(v);
	}

	public static boolean setExplicitGcInvokesConcurrent(boolean enable) {
		return setVmOptionValue("ExplicitGCInvokesConcurrent", Boolean.toString(enable));
	}

	public static Boolean isUncommitEnabled() {
		String v = getVmOptionValue("ShenandoahUncommit");
		if (v == null) return null;
		return "true".equalsIgnoreCase(v);
	}

	public static boolean setUncommitEnabled(boolean enable) {
		return setVmOptionValue("ShenandoahUncommit", Boolean.toString(enable));
	}

	public static Long getUncommitDelayMs() {
		String v = getVmOptionValue("ShenandoahUncommitDelay");
		if (v == null) return null;
		try { return Long.parseLong(v); } catch (NumberFormatException e) { return null; }
	}

	public static boolean setUncommitDelayMs(long millis) {
		return setVmOptionValue("ShenandoahUncommitDelay", Long.toString(millis));
	}

	public static String getHeuristics() {
		return getVmOptionValue("ShenandoahGCHeuristics");
	}

	public static boolean setHeuristics(String value) {
		if (value == null) return false;
		String v = value.toLowerCase(Locale.ROOT);
		if (!v.equals("adaptive") && !v.equals("static") && !v.equals("compact") && !v.equals("aggressive")) {
			return false;
		}
		return setVmOptionValue("ShenandoahGCHeuristics", value);
	}

	public static Integer getGarbageThreshold() {
		String v = getVmOptionValue("ShenandoahGarbageThreshold");
		if (v == null) return null;
		try { return Integer.parseInt(v); } catch (NumberFormatException e) { return null; }
	}

	public static boolean setGarbageThreshold(int percent) {
		if (percent < 0 || percent > 100) return false;
		return setVmOptionValue("ShenandoahGarbageThreshold", Integer.toString(percent));
	}

	public static Long getGuaranteedGcIntervalMs() {
		String v = getVmOptionValue("ShenandoahGuaranteedGCInterval");
		if (v == null) return null;
		try { return Long.parseLong(v); } catch (NumberFormatException e) { return null; }
	}

	public static boolean setGuaranteedGcIntervalMs(long millis) {
		if (millis < 0) return false;
		return setVmOptionValue("ShenandoahGuaranteedGCInterval", Long.toString(millis));
	}

	public static String buildSummary() {
		String gcNames = currentGcNames();
		if (!isShenandoahActive()) {
			return "type=" + gcNames;
		}
		String heur = Objects.toString(getHeuristics(), "unknown");
		Boolean uncommit = isUncommitEnabled();
		String uncommitStr = uncommit == null ? "unknown" : uncommit.toString();
		String uncommitDelay = Objects.toString(getUncommitDelayMs(), "unknown");
		String thr = Objects.toString(getGarbageThreshold(), "unknown");
		String gInt = Objects.toString(getGuaranteedGcIntervalMs(), "unknown");
		boolean egc = isExplicitGcInvokesConcurrent();
		return String.format("type=Shenandoah(heuristics=%s,explicitConcurrent=%s,uncommit=%s,uncommitDelayMs=%s,garbageThreshold=%s,guaranteedGcIntervalMs=%s)",
			heur, Boolean.toString(egc), uncommitStr, uncommitDelay, thr, gInt);
	}

	public static void applyStartupTuningFromSystemProperties(boolean debugLogs) {
		if (!isShenandoahActive()) return;
		boolean tune = getBooleanProperty("mercury.shenandoah.tuneAtStartup", true);
		if (!tune) return;

		// Best-effort: enable concurrent explicit GC so System.gc() won't cause stop-the-world
		try {
			String egcProp = System.getProperty("mercury.shenandoah.explicitGcConcurrent");
			boolean egc = egcProp == null ? true : Boolean.parseBoolean(egcProp);
			setExplicitGcInvokesConcurrent(egc);
		} catch (Throwable ignored) {}

		// Enable uncommit and adjust delay if provided
		try {
			String unc = System.getProperty("mercury.shenandoah.enableUncommit");
			if (unc != null) setUncommitEnabled(Boolean.parseBoolean(unc));
		} catch (Throwable ignored) {}
		try {
			String delay = System.getProperty("mercury.shenandoah.uncommitDelayMs");
			if (delay != null) {
				long ms = Long.parseLong(delay);
				if (ms >= 0) setUncommitDelayMs(ms);
			}
		} catch (Throwable ignored) {}

		// Optional heuristics and thresholds
		try {
			String heur = System.getProperty("mercury.shenandoah.heuristics");
			if (heur != null) setHeuristics(heur);
		} catch (Throwable ignored) {}
		try {
			String thr = System.getProperty("mercury.shenandoah.garbageThreshold");
			if (thr != null) setGarbageThreshold(Integer.parseInt(thr));
		} catch (Throwable ignored) {}
		try {
			String gint = System.getProperty("mercury.shenandoah.guaranteedGcIntervalMs");
			if (gint != null) setGuaranteedGcIntervalMs(Long.parseLong(gint));
		} catch (Throwable ignored) {}
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
} 