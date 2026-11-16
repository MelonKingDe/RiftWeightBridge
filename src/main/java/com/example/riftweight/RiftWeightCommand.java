package com.example.riftweight;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * /riftweight                  -> your own Rift Weight
 * /riftweight <name>           -> check a player (selected profile)
 * /riftweight <name> <profile> -> a specific profile
 * /riftweight hud              -> toggle the "not in database" HUD
 * /riftweight hud <x> <y>      -> move the HUD (x < 0 = distance from the right edge)
 * /riftweight auto             -> toggle the automatic check (1 player from the HUD list every few minutes)
 */
public final class RiftWeightCommand {

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.build();

	private RiftWeightCommand() {}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
				dispatcher.register(ClientCommands.literal("riftweight")
						.executes(ctx -> run(ctx.getSource(), Minecraft.getInstance().getUser().getName(), null))
						.then(ClientCommands.literal("auto").executes(ctx -> toggleAuto(ctx.getSource())))
						.then(ClientCommands.literal("hud")
								.executes(ctx -> toggleHud(ctx.getSource()))
								.then(ClientCommands.argument("x", IntegerArgumentType.integer())
										.then(ClientCommands.argument("y", IntegerArgumentType.integer(0))
												.executes(ctx -> moveHud(ctx.getSource(),
														IntegerArgumentType.getInteger(ctx, "x"),
														IntegerArgumentType.getInteger(ctx, "y"))))))
						.then(ClientCommands.argument("player", StringArgumentType.word())
								.executes(ctx -> run(ctx.getSource(),
										StringArgumentType.getString(ctx, "player"), null))
								.then(ClientCommands.argument("profile", StringArgumentType.word())
										.executes(ctx -> run(ctx.getSource(),
												StringArgumentType.getString(ctx, "player"),
												StringArgumentType.getString(ctx, "profile")))))));
	}

	/** /riftweight auto: turns the automatic check of one HUD-list player every few minutes on/off. */
	private static int toggleAuto(FabricClientCommandSource source) {
		ModConfig.autoCheck = !ModConfig.autoCheck;
		ModConfig.save();
		source.sendFeedback(lit("Auto check (1 player every " + ModConfig.autoCheckMinutes + " min): "
				+ (ModConfig.autoCheck ? "ON" : "OFF"), ChatFormatting.GRAY));
		return 1;
	}

	/** /riftweight hud <x> <y>: moves the HUD immediately and saves the position. */
	private static int moveHud(FabricClientCommandSource source, int x, int y) {
		ModConfig.hud = true;
		ModConfig.hudX = x;
		ModConfig.hudY = y;
		ModConfig.save();
		source.sendFeedback(lit("HUD moved to x=" + x + ", y=" + y
				+ (x < 0 ? " (x counts from the right edge)" : ""), ChatFormatting.GRAY));
		return 1;
	}

	/** /riftweight hud: shows/hides the list of lobby players who are not in the database. */
	private static int toggleHud(FabricClientCommandSource source) {
		ModConfig.hud = !ModConfig.hud;
		ModConfig.save();
		source.sendFeedback(lit("Missing-players HUD: " + (ModConfig.hud ? "ON" : "OFF"), ChatFormatting.GRAY));
		return 1;
	}

	private static int run(FabricClientCommandSource source, String player, String profile) {
		source.sendFeedback(lit("Loading Rift Weight for " + player + " ...", ChatFormatting.GRAY));

		String url = RiftWeightMod.API_BASE + "/api/check/" + enc(player)
				+ (profile != null ? "?profile=" + enc(profile) : "");
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(25))
				.header("User-Agent", "RiftWeightMod/1.0")
				.GET()
				.build();

		// Async so the game never freezes; chat output must go back to the client thread.
		HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.whenComplete((response, error) -> Minecraft.getInstance().execute(() -> {
					if (error != null) {
						source.sendError(Component.literal("Could not reach the Rift Weight API: " + error));
						return;
					}
					switch (response.statusCode()) {
						case 200 -> show(source, response.body());
						case 404 -> source.sendError(Component.literal("No data found for " + player
								+ (profile != null ? " (profile " + profile + ")" : "") + "."));
						case 429 -> source.sendError(Component.literal("Too many requests, please wait a moment."));
						default -> source.sendError(Component.literal(
								"API error (HTTP " + response.statusCode() + ")."));
					}
				}));
		return 1;
	}

	private static void show(FabricClientCommandSource source, String body) {
		try {
			JsonObject o = JsonParser.parseString(body).getAsJsonObject();
			String name = o.get("name").getAsString();
			String profile = o.get("profile").getAsString();
			double weight = o.get("weight").getAsDouble();

			source.sendFeedback(lit(name, ChatFormatting.WHITE, ChatFormatting.BOLD)
					.append(lit(" (" + profile + ")", ChatFormatting.GRAY)));

			MutableComponent line = lit("Rift Weight: ", ChatFormatting.LIGHT_PURPLE)
					.append(lit(fmt(weight), ChatFormatting.WHITE));
			JsonElement rank = o.get("rank");
			JsonElement total = o.get("total");
			if (rank != null && !rank.isJsonNull() && total != null && !total.isJsonNull()) {
				line.append(lit("  #" + rank.getAsInt() + " of " + total.getAsInt(), ChatFormatting.GOLD));
				// Update the nametag/chat tag right away
				RankCache.put(RankCache.parseUuid(o.get("uuid").getAsString()), rank.getAsInt(), total.getAsInt());
			}
			source.sendFeedback(line);

			for (JsonElement e : o.getAsJsonArray("breakdown")) {
				JsonObject b = e.getAsJsonObject();
				double w = b.get("weight").getAsDouble();
				if (w <= 0) continue;
				source.sendFeedback(lit(" - " + b.get("name").getAsString() + ": ", ChatFormatting.GRAY)
						.append(lit(fmt(w), ChatFormatting.WHITE))
						.append(lit("  (" + b.get("percent").getAsDouble() + "%)", ChatFormatting.DARK_GRAY)));
			}
		} catch (Exception ex) {
			source.sendError(Component.literal("Could not read the API response."));
		}
	}

	private static MutableComponent lit(String s, ChatFormatting... formats) {
		return Component.literal(s).withStyle(formats);
	}

	private static String fmt(double v) {
		return String.format(Locale.US, "%,.2f", v);
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}
}
