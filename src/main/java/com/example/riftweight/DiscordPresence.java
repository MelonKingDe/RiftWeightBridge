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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Discord Rich Presence: "MelonKingDe #1 out of 243 people checked".
 * The rank comes from /api/player/<your name>. Needs the Discord desktop app to be running
 * (the browser version has no Rich Presence). The activity title ("Playing ...") is the name of
 * the Discord application whose ID is set in RiftWeightMod.DISCORD_APP_ID.
 * Everything runs on its own background thread; the game is never blocked.
 * Problems are written to the game log ("[riftweight] Discord Rich Presence: ...") and shown by
 * /riftweight discord.
 */
public final class DiscordPresence {

	private static final long REFRESH_MS = 3 * 60_000L;  // fetch the rank + re-send the activity
	private static final long RETRY_MS = 30_000L;        // after an API error

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();
	private static final long START_SECONDS = System.currentTimeMillis() / 1000;

	// worker thread only
	private static DiscordIpc ipc;
	private static String lastSent;
	private static long nextFetchAt = 0;
	private static long nextSendAt = 0;

	// read by /riftweight discord
	private static volatile boolean running = false;
	private static volatile boolean connected = false;
	private static volatile String text = null;
	private static volatile String configProblem = null;
	private static volatile String apiProblem = null;
	private static volatile String discordProblem = null;

	private DiscordPresence() {}

	private static boolean appIdSet() {
		String id = RiftWeightMod.DISCORD_APP_ID;
		return id != null && !id.isBlank() && !id.startsWith("YOUR_");
	}

	public static void start() {
		if (!ModConfig.discord) {
			configProblem = "turned off in config/riftweight.json (\"discord\": false)";
		} else if (!appIdSet()) {
			configProblem = "DISCORD_APP_ID in RiftWeightMod.java is still the placeholder";
		}
		if (configProblem != null) {
			log("not started: " + configProblem);
			return;
		}

		running = true;
		log("started");
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
			setDiscordProblem("connection lost or rejected: " + t);
		}
	}

	private static void tick() throws Exception {
		long now = System.currentTimeMillis();

		if (text == null || now >= nextFetchAt) {
			String fresh = fetchText();
			nextFetchAt = now + (fresh != null ? REFRESH_MS : RETRY_MS);
			if (fresh != null) text = fresh;
		}
		if (text == null) return; // nothing to show yet (see apiProblem)

		if (ipc == null) {
			ipc = DiscordIpc.connect(RiftWeightMod.DISCORD_APP_ID);
			if (ipc == null) {
				connected = false;
				setDiscordProblem(DiscordIpc.lastError);
				return; // Discord not running, retry later
			}
			connected = true;
			setDiscordProblem(null);
			log("connected to Discord");
			lastSent = null;
		}

		// re-send on change and periodically (this also detects a dead pipe after a Discord restart)
		if (!text.equals(lastSent) || now >= nextSendAt) {
			ipc.setActivity(text, START_SECONDS);
			if (lastSent == null) log("presence set: " + text);
			lastSent = text;
			nextSendAt = now + REFRESH_MS;
			setDiscordProblem(null);
		}
	}

	private static void closeIpc() {
		connected = false;
		if (ipc != null) ipc.close();
		ipc = null;
	}

	/** Builds the presence text from the API, or returns null on errors (stored in apiProblem). */
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
				setApiProblem(null);
				return ign + " #" + best.get("rank").getAsInt() + " out of " + best.get("total").getAsInt()
						+ " people checked";
			}
			if (response.statusCode() == 404) {
				setApiProblem(null);
				return ign + " is not ranked yet";
			}
			setApiProblem("API answered HTTP " + response.statusCode());
		} catch (Exception e) {
			setApiProblem("API not reachable: " + e);
		}
		return null;
	}

	// ---------------------------------------------------------------- status / logging

	/** Lines for /riftweight discord. */
	public static List<String> statusLines() {
		List<String> lines = new ArrayList<>();
		lines.add("turned on in config: " + ModConfig.discord);
		lines.add("application ID set: " + appIdSet());
		lines.add("running: " + running);
		lines.add("presence text: " + (text == null ? "(none yet)" : text));
		lines.add("connected to Discord: " + connected);
		if (configProblem != null) lines.add("PROBLEM: " + configProblem);
		if (apiProblem != null) lines.add("PROBLEM (API): " + apiProblem);
		if (discordProblem != null) lines.add("PROBLEM (Discord): " + discordProblem);
		return lines;
	}

	private static void setApiProblem(String problem) {
		if (!Objects.equals(apiProblem, problem)) {
			apiProblem = problem;
			if (problem != null) log(problem);
		}
	}

	private static void setDiscordProblem(String problem) {
		if (!Objects.equals(discordProblem, problem)) {
			discordProblem = problem;
			if (problem != null) log(problem);
		}
	}

	private static void log(String message) {
		System.out.println("[riftweight] Discord Rich Presence: " + message);
	}
}
