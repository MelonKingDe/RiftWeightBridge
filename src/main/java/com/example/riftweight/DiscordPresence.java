package com.example.riftweight;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Discord Rich Presence: "MelonKingDe #1 out of 243 people checked".
 * The rank comes from /api/player/<your name>. Needs the Discord desktop app to be running
 * (the browser version has no Rich Presence). The activity title ("Playing ...") is the name of
 * the Discord application whose ID is set in RiftWeightMod.DISCORD_APP_ID.
 * Everything runs on its own background thread; the game is never blocked.
 */
public final class DiscordPresence {

	private static final long REFRESH_MS = 3 * 60_000L;  // fetch the rank + re-send the activity
	private static final long RETRY_MS = 30_000L;        // after an API error

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();
	private static final long START_SECONDS = System.currentTimeMillis() / 1000;

	// only touched by the single worker thread
	private static DiscordIpc ipc;
	private static String text;
	private static String lastSent;
	private static long nextFetchAt = 0;
	private static long nextSendAt = 0;
	private static boolean warned = false;

	private DiscordPresence() {}

	public static void start() {
		String appId = RiftWeightMod.DISCORD_APP_ID;
		if (!ModConfig.discord || appId == null || appId.isBlank() || appId.startsWith("YOUR_")) return;

		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "RiftWeight-Discord");
			t.setDaemon(true);
			return t;
		});
		executor.scheduleWithFixedDelay(DiscordPresence::tickSafe, 10, 15, TimeUnit.SECONDS);
	}

	private static void tickSafe() {
		try {
			tick();
		} catch (Throwable t) { // pipe broke (Discord closed/restarted) -> reconnect on the next tick
			closeIpc();
			lastSent = null;
		}
	}

	private static void tick() throws Exception {
		long now = System.currentTimeMillis();

		if (text == null || now >= nextFetchAt) {
			String fresh = fetchText();
			nextFetchAt = now + (fresh != null ? REFRESH_MS : RETRY_MS);
			if (fresh != null) text = fresh;
		}
		if (text == null) return; // nothing to show yet

		if (ipc == null) {
			ipc = DiscordIpc.connect(RiftWeightMod.DISCORD_APP_ID);
			if (ipc == null) {
				if (!warned && DiscordIpc.lastError != null && !DiscordIpc.lastError.contains("FileNotFound")) {
					warned = true;
					System.out.println("[riftweight] Discord Rich Presence: " + DiscordIpc.lastError);
				}
				return; // Discord not running, retry later
			}
			lastSent = null;
		}

		// re-send on change and periodically (this also detects a dead pipe after a Discord restart)
		if (!text.equals(lastSent) || now >= nextSendAt) {
			ipc.setActivity(text, START_SECONDS);
			lastSent = text;
			nextSendAt = now + REFRESH_MS;
		}
	}

	private static void closeIpc() {
		if (ipc != null) ipc.close();
		ipc = null;
	}

	/** Builds the presence text from the API, or returns null on errors. */
	private static String fetchText() {
		try {
			String ign = Minecraft.getInstance().getUser().getName();
			HttpRequest request = HttpRequest.newBuilder(URI.create(RiftWeightMod.API_BASE + "/api/player/"
							+ URLEncoder.encode(ign, StandardCharsets.UTF_8)))
					.timeout(Duration.ofSeconds(10))
					.header("User-Agent", "RiftWeightMod/1.0")
					.GET()
					.build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() == 200) {
				JsonObject best = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("best");
				return ign + " #" + best.get("rank").getAsInt() + " out of " + best.get("total").getAsInt()
						+ " people checked";
			}
			if (response.statusCode() == 404) {
				return ign + " is not ranked yet";
			}
		} catch (Exception ignored) {
		}
		return null;
	}
}
