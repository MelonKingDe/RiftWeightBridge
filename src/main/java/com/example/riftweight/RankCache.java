package com.example.riftweight;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Rank cache. The nametag mixin and the chat hook call {@link #get(UUID)} (cheap hash lookup).
 * Unknown/expired UUIDs are collected and fetched once per second in ONE batch request
 * (GET /api/players?uuids=a,b,c). Never block the render thread!
 */
public final class RankCache {

	/** rank > 0: ranked, 0: not in the database, -1: unknown (API error, will be retried). */
	public record Entry(int rank, int total, long expiresAt) {
		public boolean ranked() {
			return rank > 0;
		}
	}

	private static final long TTL_MS = 5 * 60_000L;          // known rank: valid for 5 min
	private static final long NOT_FOUND_TTL_MS = 2 * 60_000L; // not listed: ask again after 2 min
	private static final long ERROR_RETRY_MS = 30_000L;       // API error: retry after 30 s
	private static final int MAX_BATCH = 50;                  // must match API_MAX_BATCH in the bot

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();

	private static final Map<UUID, Entry> CACHE = new ConcurrentHashMap<>();
	private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet(); // queued or currently loading
	private static final Queue<UUID> PENDING = new ConcurrentLinkedQueue<>();

	// Name based lookups (chat): lower-case name -> UUID of players we have seen, and a fallback that
	// asks the API by name (/api/player/<name>) for senders we have never seen.
	private static final Map<String, UUID> NAMES = new ConcurrentHashMap<>();
	private static final Map<String, Entry> BY_NAME = new ConcurrentHashMap<>();
	private static final Set<String> NAME_IN_FLIGHT = ConcurrentHashMap.newKeySet();
	private static final Queue<String> NAME_PENDING = new ConcurrentLinkedQueue<>();
	private static final int MAX_NAME_LOOKUPS_PER_SECOND = 3;
	private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

	private RankCache() {}

	public static void start() {
		ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "RiftWeight-Fetcher");
			t.setDaemon(true);
			return t;
		});
		scheduler.scheduleWithFixedDelay(RankCache::flushSafe, 1, 1, TimeUnit.SECONDS);
	}

	/** Returns the (possibly slightly stale) entry or null, and triggers a reload if needed. */
	public static Entry get(UUID id) {
		Entry e = CACHE.get(id);
		if ((e == null || e.expiresAt() < System.currentTimeMillis())
				&& id.version() == 4 // real player accounts only; filters NPCs (e.g. Hypixel v2)
				&& IN_FLIGHT.add(id)) {
			PENDING.add(id);
		}
		return e;
	}

	/** Remembers which UUID belongs to a name (called for every player we see). */
	public static void rememberName(String name, UUID id) {
		if (name == null || id == null || !NAME_PATTERN.matcher(name).matches()) return;
		String key = name.toLowerCase(Locale.ROOT);
		if (!id.equals(NAMES.get(key))) NAMES.put(key, id);
	}

	/** UUID of a player with this name that we have seen before, or null. */
	public static UUID uuidForName(String name) {
		return NAMES.get(name.toLowerCase(Locale.ROOT));
	}

	/** Rank by name, for players we have never seen. Asks the API in the background; null until it answers. */
	public static Entry getByName(String name) {
		if (!NAME_PATTERN.matcher(name).matches()) return null;
		String key = name.toLowerCase(Locale.ROOT);
		Entry e = BY_NAME.get(key);
		if ((e == null || e.expiresAt() < System.currentTimeMillis()) && NAME_IN_FLIGHT.add(key)) {
			NAME_PENDING.add(key);
		}
		return e;
	}

	/** Used by /riftweight so the tag shows the fresh rank immediately. */
	public static void put(UUID id, int rank, int total) {
		CACHE.put(id, new Entry(rank, total, System.currentTimeMillis() + TTL_MS));
	}

	public static UUID parseUuid(String hex32) {
		return new UUID(Long.parseUnsignedLong(hex32.substring(0, 16), 16),
				Long.parseUnsignedLong(hex32.substring(16), 16));
	}

	private static void flushSafe() {
		try {
			flush();
		} catch (Throwable t) { // otherwise the scheduler would stop running
			t.printStackTrace();
		}
	}

	private static void flushNames() {
		for (int i = 0; i < MAX_NAME_LOOKUPS_PER_SECOND; i++) {
			String key = NAME_PENDING.poll();
			if (key == null) return;
			HttpRequest request = HttpRequest.newBuilder(URI.create(RiftWeightMod.API_BASE + "/api/player/" + key))
					.timeout(Duration.ofSeconds(10))
					.header("User-Agent", "RiftWeightMod/1.0")
					.GET()
					.build();
			HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
					.whenComplete((res, err) -> handleName(key, res, err));
		}
	}

	private static void handleName(String key, HttpResponse<String> res, Throwable err) {
		long now = System.currentTimeMillis();
		try {
			if (err != null || (res.statusCode() / 100 != 2 && res.statusCode() != 404)) {
				Entry old = BY_NAME.get(key);
				BY_NAME.put(key, old != null
						? new Entry(old.rank(), old.total(), now + ERROR_RETRY_MS)
						: new Entry(-1, 0, now + ERROR_RETRY_MS));
				return;
			}
			if (res.statusCode() == 404) {
				BY_NAME.put(key, new Entry(0, 0, now + NOT_FOUND_TTL_MS));
				return;
			}
			JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
			JsonObject best = o.getAsJsonObject("best");
			Entry entry = new Entry(best.get("rank").getAsInt(), best.get("total").getAsInt(), now + TTL_MS);
			BY_NAME.put(key, entry);
			UUID id = parseUuid(o.get("uuid").getAsString());
			NAMES.put(key, id);
			CACHE.put(id, entry);
		} catch (Exception e) {
			BY_NAME.putIfAbsent(key, new Entry(-1, 0, now + ERROR_RETRY_MS));
		} finally {
			NAME_IN_FLIGHT.remove(key);
		}
	}

	private static void flush() {
		flushNames();
		List<UUID> batch = new ArrayList<>();
		UUID u;
		while (batch.size() < MAX_BATCH && (u = PENDING.poll()) != null) {
			batch.add(u);
		}
		if (batch.isEmpty()) return;

		String ids = batch.stream().map(x -> x.toString().replace("-", "")).collect(Collectors.joining(","));
		HttpRequest request = HttpRequest.newBuilder(URI.create(RiftWeightMod.API_BASE + "/api/players?uuids=" + ids))
				.timeout(Duration.ofSeconds(10))
				.header("User-Agent", "RiftWeightMod/1.0")
				.GET()
				.build();
		HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.whenComplete((res, err) -> handle(batch, res, err));
	}

	private static void handle(List<UUID> batch, HttpResponse<String> res, Throwable err) {
		long now = System.currentTimeMillis();
		try {
			if (err != null || res.statusCode() / 100 != 2) {
				for (UUID id : batch) {
					Entry old = CACHE.get(id);
					CACHE.put(id, old != null
							? new Entry(old.rank(), old.total(), now + ERROR_RETRY_MS) // keep the old rank
							: new Entry(-1, 0, now + ERROR_RETRY_MS));
				}
				return;
			}
			JsonObject players = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonObject("players");
			for (UUID id : batch) {
				String key = id.toString().replace("-", "");
				if (players.has(key)) {
					JsonObject p = players.getAsJsonObject(key);
					CACHE.put(id, new Entry(p.get("rank").getAsInt(), p.get("total").getAsInt(), now + TTL_MS));
				} else {
					CACHE.put(id, new Entry(0, 0, now + NOT_FOUND_TTL_MS));
				}
			}
		} catch (Exception e) {
			for (UUID id : batch) {
				CACHE.putIfAbsent(id, new Entry(-1, 0, now + ERROR_RETRY_MS));
			}
		} finally {
			IN_FLIGHT.removeAll(batch);
		}
	}
}
