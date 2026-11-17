package com.example.riftweight;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The whole leaderboard as a local map "name (lower case) -> rank", downloaded from /api/ranks every
 * 2 minutes. Everything that needs a rank (nametag, chat, "not in database" HUD) just looks the player
 * NAME up here: instant, no UUIDs, no per-player requests.
 * A player who is not in the map is not in the database.
 */
public final class RankBoard {

	private static final long REFRESH_MS = 2 * 60_000L;
	private static final long RETRY_MS = 30_000L;

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();

	// Maps are never modified after they are published; they are replaced as a whole.
	private static volatile Map<String, Integer> ranks = Map.of();
	private static volatile int total = 0;
	private static volatile boolean loaded = false;
	private static volatile boolean failing = false;
	private static volatile long nextFetchAt = 0;
	private static final AtomicBoolean FETCHING = new AtomicBoolean(false);

	private RankBoard() {}

	public static void start() {
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "RiftWeight-Ranks");
			t.setDaemon(true);
			return t;
		});
		executor.scheduleWithFixedDelay(RankBoard::tickSafe, 1, 5, TimeUnit.SECONDS);
	}

	/** Rank of the player's best profile (1 = best), or 0 if he is not in the database. */
	public static int rankOf(String name) {
		if (name == null) return 0;
		Integer rank = ranks.get(name.toLowerCase(Locale.ROOT));
		return rank == null ? 0 : rank;
	}

	public static int total() {
		return total;
	}

	public static int size() {
		return ranks.size();
	}

	/** True once the list has been downloaded at least once. */
	public static boolean isLoaded() {
		return loaded;
	}

	/** True if the last download failed (the old list, if any, stays in use). */
	public static boolean isFailing() {
		return failing;
	}

	/** Adds/updates one player right away, e.g. after /riftweight <name> or an auto check. */
	public static synchronized void put(String name, int rank, int newTotal) {
		if (name == null || rank <= 0) return;
		Map<String, Integer> copy = new HashMap<>(ranks);
		copy.put(name.toLowerCase(Locale.ROOT), rank);
		ranks = copy;
		total = Math.max(total, newTotal);
		loaded = true;
	}

	private static void tickSafe() {
		try {
			tick();
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	private static void tick() {
		long now = System.currentTimeMillis();
		if (now < nextFetchAt || !FETCHING.compareAndSet(false, true)) return;

		HttpRequest request = HttpRequest.newBuilder(URI.create(RiftWeightMod.API_BASE + "/api/ranks"))
				.timeout(Duration.ofSeconds(15))
				.header("User-Agent", "RiftWeightMod/1.0")
				.GET()
				.build();
		HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
			long done = System.currentTimeMillis();
			try {
				if (err != null || res.statusCode() != 200) {
					failing = true;
					nextFetchAt = done + RETRY_MS;
					return;
				}
				JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
				Map<String, Integer> fresh = new HashMap<>();
				for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("ranks").entrySet()) {
					fresh.put(e.getKey(), e.getValue().getAsInt());
				}
				synchronized (RankBoard.class) {
					ranks = fresh;
					total = root.get("total").getAsInt();
					loaded = true;
				}
				failing = false;
				nextFetchAt = done + REFRESH_MS;
			} catch (Exception e) {
				failing = true;
				nextFetchAt = done + RETRY_MS;
			} finally {
				FETCHING.set(false);
			}
		});
	}
}
