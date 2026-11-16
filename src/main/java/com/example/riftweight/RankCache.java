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
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Cache für Ränge. Der Nametag-Mixin ruft {@link #get(UUID)} pro Frame auf (billiger Hash-Lookup).
 * Unbekannte/abgelaufene UUIDs werden gesammelt und sekündlich in EINEM Batch-Request
 * (GET /api/players?uuids=a,b,c) geholt. Nie auf dem Render-Thread blockieren!
 */
public final class RankCache {

	/** rank <= 0 bedeutet: nicht in der Rangliste. */
	public record Entry(int rank, int total, long expiresAt) {
		public boolean ranked() {
			return rank > 0;
		}
	}

	private static final long TTL_MS = 5 * 60_000L;          // bekannter Rang: 5 Min gültig
	private static final long NOT_FOUND_TTL_MS = 2 * 60_000L; // nicht gelistet: nach 2 Min nochmal fragen
	private static final long ERROR_RETRY_MS = 30_000L;       // API-Fehler: nach 30 s nochmal
	private static final int MAX_BATCH = 50;                  // muss zu API_MAX_BATCH im Bot passen

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();

	private static final Map<UUID, Entry> CACHE = new ConcurrentHashMap<>();
	private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet(); // wartet oder wird gerade geladen
	private static final Queue<UUID> PENDING = new ConcurrentLinkedQueue<>();

	private RankCache() {}

	public static void start() {
		ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "RiftWeight-Fetcher");
			t.setDaemon(true);
			return t;
		});
		scheduler.scheduleWithFixedDelay(RankCache::flushSafe, 1, 1, TimeUnit.SECONDS);
	}

	/** Liefert den (evtl. leicht veralteten) Eintrag oder null, und stößt bei Bedarf ein Nachladen an. */
	public static Entry get(UUID id) {
		Entry e = CACHE.get(id);
		if ((e == null || e.expiresAt() < System.currentTimeMillis())
				&& id.version() == 4 // echte Spieler-Accounts; filtert NPCs (z.B. Hypixel v2) raus
				&& IN_FLIGHT.add(id)) {
			PENDING.add(id);
		}
		return e;
	}

	/** Vom /riftweight-Befehl genutzt, damit der Nametag sofort den frischen Rang zeigt. */
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
		} catch (Throwable t) { // sonst würde der Scheduler weitere Läufe einstellen
			t.printStackTrace();
		}
	}

	private static void flush() {
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
							? new Entry(old.rank(), old.total(), now + ERROR_RETRY_MS) // alten Rang behalten
							: new Entry(0, 0, now + ERROR_RETRY_MS));
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
				CACHE.putIfAbsent(id, new Entry(0, 0, now + ERROR_RETRY_MS));
			}
		} finally {
			IN_FLIGHT.removeAll(batch);
		}
	}
}
