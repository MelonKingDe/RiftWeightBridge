package com.example.riftweight;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

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

/**
 * Every few minutes (config autoCheckMinutes, default 3) it checks ONE random player who is currently
 * in the "not in database" HUD list, through /api/check/<name> - exactly like typing /riftweight <name>.
 * Players without SkyBlock data get a long cooldown so they are not picked again and again.
 * Toggle with /riftweight auto.
 */
public final class AutoChecker {

	private static final long NOT_FOUND_COOLDOWN_MS = 6 * 60 * 60_000L; // no SkyBlock data -> retry in 6 h
	private static final long ERROR_COOLDOWN_MS = 5 * 60_000L;
	private static final long RATE_LIMIT_PAUSE_MS = 90_000L;
	private static final long ERROR_PAUSE_MS = 60_000L;

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();
	private static final Random RANDOM = new Random();

	private static final Map<UUID, Long> COOLDOWN = new ConcurrentHashMap<>();
	private static final AtomicBoolean BUSY = new AtomicBoolean(false);
	private static volatile long pausedUntil = 0;
	private static int ticks = 0;

	private AutoChecker() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!ModConfig.autoCheck) {
				ticks = 0;
				return;
			}
			if (++ticks < ModConfig.autoCheckMinutes * 60 * 20) return; // 20 ticks = 1 second
			ticks = 0;
			try {
				checkOne(client);
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
	}

	private static void checkOne(Minecraft client) {
		if (client.getConnection() == null || client.level == null) return; // not in a world
		long now = System.currentTimeMillis();
		if (BUSY.get() || now < pausedUntil) return;

		List<Lobby.Player> candidates = new ArrayList<>();
		for (Lobby.Player p : Lobby.scan().missing()) {
			Long until = COOLDOWN.get(p.id());
			if (until == null || until <= now) candidates.add(p);
		}
		if (candidates.isEmpty()) return;

		check(candidates.get(RANDOM.nextInt(candidates.size())));
	}

	private static void check(Lobby.Player player) {
		if (!BUSY.compareAndSet(false, true)) return;

		HttpRequest request = HttpRequest.newBuilder(
						URI.create(RiftWeightMod.API_BASE + "/api/check/" + player.name()))
				.timeout(Duration.ofSeconds(30))
				.header("User-Agent", "RiftWeightMod/1.0")
				.GET()
				.build();

		HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
			long now = System.currentTimeMillis();
			try {
				if (err != null) { // API unreachable
					pausedUntil = now + ERROR_PAUSE_MS;
					COOLDOWN.put(player.id(), now + ERROR_COOLDOWN_MS);
					return;
				}
				switch (res.statusCode()) {
					case 200 -> {
						JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
						JsonElement rank = o.get("rank");
						JsonElement total = o.get("total");
						if (rank != null && !rank.isJsonNull() && total != null && !total.isJsonNull()) {
							RankCache.put(player.id(), rank.getAsInt(), total.getAsInt()); // leaves the HUD list
						} else {
							COOLDOWN.put(player.id(), now + ERROR_COOLDOWN_MS);
						}
					}
					case 404 -> COOLDOWN.put(player.id(), now + NOT_FOUND_COOLDOWN_MS);
					case 429 -> pausedUntil = now + RATE_LIMIT_PAUSE_MS;
					default -> {
						COOLDOWN.put(player.id(), now + ERROR_COOLDOWN_MS);
						pausedUntil = now + ERROR_PAUSE_MS;
					}
				}
			} catch (Exception e) {
				COOLDOWN.put(player.id(), now + ERROR_COOLDOWN_MS);
			} finally {
				BUSY.set(false);
			}
		});
	}
}
