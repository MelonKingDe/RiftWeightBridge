package com.example.riftweight;

import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Helper for /riftweight debug: shows what the tab list hook sees. */
public final class Debug {

	public static boolean enabled = false;
	public static long hookCalls = 0;

	private static final Map<String, String> SAMPLES = new LinkedHashMap<>() {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
			return size() > 14;
		}
	};

	private Debug() {}

	public static void clear() {
		hookCalls = 0;
		SAMPLES.clear();
	}

	public static void record(String shown, PlayerInfo real, RankCache.Entry entry) {
		String outcome;
		if (real == null) {
			outcome = "no online player found in this text";
		} else {
			String id = real.getProfile().id().toString().substring(0, 8);
			String state = entry == null ? "rank still loading"
					: entry.ranked() ? "rank #" + entry.rank() : "not in database";
			outcome = real.getProfile().name() + " (" + id + ") -> " + state;
		}
		SAMPLES.put(shown, outcome);
	}

	public static List<String> dump() {
		List<String> lines = new ArrayList<>();
		SAMPLES.forEach((shown, outcome) -> lines.add("\"" + shown + "\" => " + outcome));
		return lines;
	}
}
