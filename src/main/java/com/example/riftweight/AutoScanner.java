package com.example.riftweight;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Fills the database: every few seconds it picks ONE random player from the tab list (your lobby)
 * who has no rank yet and checks him through the API (/api/check/<name>?scan=1).
 *
 * "No rank yet" = the batch lookup of {@link RankCache} found nothing for this player.
 * Only one check runs at a time, failed/unknown players get a cooldown, and the scanner
 * backs off when the API says "too many requests".
 */
public final class AutoScanner {

	private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

	private static final long NOT_FOUND_COOLDOWN_MS = 6 * 60 * 60_000L; // no SkyBlock data -> try again in 6 h
	private static final long ERROR_COOLDOWN_MS = 5 * 60_000L;
	private static final long RATE_LIMIT_PAUSE_MS = 90_000L;
	private static final long ERROR_PAUSE_MS = 30_000L;

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();
	private static final Random RANDOM = new Random();

	private static final Map<UUID, Long> COOLDOWN = new ConcurrentHashMap<>();
	private static final AtomicBoolean BUSY = new AtomicBoolean(false);
	private static volatile long pausedUntil = 0;
	private static int ticks = 0;

	private record Candidate(UUID id, String name) {}

	private AutoScanner() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!ModConfig.autoScan) return;
			if (++ticks < ModConfig.scanIntervalSeconds * 20) return; // 20 ticks = 1 second
			ticks = 0;
			try {
				scan(client);
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
	}

	private static void scan(Minecraft client) {
		ClientPacketListener connection = client.getConnection();
		if (connection == null || client.level == null) return; // not in a world
		long now = System.currentTimeMillis();
		if (BUSY.get() || now < pausedUntil) return;

		List<Candidate> candidates = new ArrayList<>();
		for (PlayerInfo info : connection.getOnlinePlayers()) {
			var profile = info.getProfile();
			UUID id = profile.id();
			String name = profile.name();
			if (id == null || name == null || id.version() != 4 || !NAME.matcher(name).matches()) continue; // skip NPCs
			Long until = COOLDOWN.get(id);
			if (until != null && until > now) continue;

			// get() also queues unknown players for the batch lookup, so the whole lobby gets resolved over time
			RankCache.Entry entry = RankCache.get(id);
			if (entry == null || entry.ranked()) continue; // not looked up yet, or already has a rank
			candidates.add(new Candidate(id, name));
		}
		if (candidates.isEmpty()) return;

		check(candidates.get(RANDOM.nextInt(candidates.size())));
	}

	private static void check(Candidate c) {
		if (!BUSY.compareAndSet(false, true)) return;

		HttpRequest request = HttpRequest.newBuilder(
						URI.create(RiftWeightMod.API_BASE + "/api/check/" + c.name() + "?scan=1"))
				.timeout(Duration.ofSeconds(30))
				.header("User-Agent", "RiftWeightMod/1.0")
				.GET()
				.build();

		HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
			long now = System.currentTimeMillis();
			try {
				if (err != null) {
					pausedUntil = now + ERROR_PAUSE_MS * 2; // API unreachable
					COOLDOWN.put(c.id(), now + ERROR_COOLDOWN_MS);
					return;
				}
				switch (res.statusCode()) {
					case 200 -> {
						JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
						JsonElement rank = o.get("rank");
						JsonElement total = o.get("total");
						if (rank != null && !rank.isJsonNull() && total != null && !total.isJsonNull()) {
							RankCache.put(c.id(), rank.getAsInt(), total.getAsInt()); // tag appears immediately
						} else {
							COOLDOWN.put(c.id(), now + ERROR_COOLDOWN_MS);
						}
					}
					case 404 -> COOLDOWN.put(c.id(), now + NOT_FOUND_COOLDOWN_MS);
					case 429 -> pausedUntil = now + RATE_LIMIT_PAUSE_MS;
					default -> {
						COOLDOWN.put(c.id(), now + ERROR_COOLDOWN_MS);
						pausedUntil = now + ERROR_PAUSE_MS;
					}
				}
			} catch (Exception e) {
				COOLDOWN.put(c.id(), now + ERROR_COOLDOWN_MS);
			} finally {
				BUSY.set(false);
			}
		});
	}
}
