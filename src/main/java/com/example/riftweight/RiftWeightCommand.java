package com.example.riftweight;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * /riftweight                  -> eigener Rift Weight
 * /riftweight <name>           -> Spieler checken (ausgewähltes Profil)
 * /riftweight <name> <profil>  -> bestimmtes Profil
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
						.then(ClientCommands.argument("player", StringArgumentType.word())
								.executes(ctx -> run(ctx.getSource(),
										StringArgumentType.getString(ctx, "player"), null))
								.then(ClientCommands.argument("profile", StringArgumentType.word())
										.executes(ctx -> run(ctx.getSource(),
												StringArgumentType.getString(ctx, "player"),
												StringArgumentType.getString(ctx, "profile")))))));
	}

	private static int run(FabricClientCommandSource source, String player, String profile) {
		source.sendFeedback(lit("Rift Weight für " + player + " wird geladen ...", ChatFormatting.GRAY));

		String url = RiftWeightMod.API_BASE + "/api/check/" + enc(player)
				+ (profile != null ? "?profile=" + enc(profile) : "");
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(25))
				.header("User-Agent", "RiftWeightMod/1.0")
				.GET()
				.build();

		// Asynchron, damit das Spiel nicht hängt. Chat-Ausgabe zurück auf den Client-Thread.
		HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.whenComplete((response, error) -> Minecraft.getInstance().execute(() -> {
					if (error != null) {
						source.sendError(Component.literal("Rift-Weight-API nicht erreichbar."));
						return;
					}
					switch (response.statusCode()) {
						case 200 -> show(source, response.body());
						case 404 -> source.sendError(Component.literal("Keine Daten für " + player
								+ (profile != null ? " (Profil " + profile + ")" : "") + " gefunden."));
						case 429 -> source.sendError(Component.literal("Zu viele Anfragen, bitte kurz warten."));
						default -> source.sendError(Component.literal(
								"Fehler von der API (HTTP " + response.statusCode() + ")."));
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

			var line = lit("Rift Weight: ", ChatFormatting.LIGHT_PURPLE)
					.append(lit(fmt(weight), ChatFormatting.WHITE));
			JsonElement rank = o.get("rank");
			JsonElement total = o.get("total");
			if (rank != null && !rank.isJsonNull() && total != null && !total.isJsonNull()) {
				line.append(lit("  #" + rank.getAsInt() + " von " + total.getAsInt(), ChatFormatting.GOLD));
				// Nametag sofort aktualisieren
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
			source.sendError(Component.literal("Antwort der API konnte nicht gelesen werden."));
		}
	}

	private static net.minecraft.network.chat.MutableComponent lit(String s, ChatFormatting... formats) {
		return Component.literal(s).withStyle(formats);
	}

	private static String fmt(double v) {
		return String.format(Locale.US, "%,.2f", v);
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}
}
