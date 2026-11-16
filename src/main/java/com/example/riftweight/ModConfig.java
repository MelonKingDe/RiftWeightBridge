package com.example.riftweight;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Settings stored in config/riftweight.json (created on first start, applied on game start):
 *   icon     - symbol shown in the tag
 *   format   - tag template, placeholders {icon} and {rank}
 *   nametag  - show the tag above players' heads
 *   chat     - show the tag in chat messages
 *   hud      - show the "not in database" player list (toggle in game with /riftweight hud)
 *   hudX     - HUD x position in GUI pixels; negative = distance from the right edge
 *   hudY     - HUD y position in GUI pixels
 */
public final class ModConfig {

	public static String icon = "\u0444"; // Cyrillic "ф"
	public static String format = "[{icon} {rank}]";
	public static boolean nametag = true;
	public static boolean chat = true;
	public static boolean hud = true;
	public static int hudX = -4;
	public static int hudY = 70;

	private ModConfig() {}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("riftweight.json");
	}

	public static void load() {
		try {
			Path file = file();
			if (Files.exists(file)) {
				JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
				if (o.has("icon")) icon = o.get("icon").getAsString();
				if (o.has("format")) format = o.get("format").getAsString();
				if (o.has("nametag")) nametag = o.get("nametag").getAsBoolean();
				if (o.has("chat")) chat = o.get("chat").getAsBoolean();
				if (o.has("hud")) hud = o.get("hud").getAsBoolean();
				if (o.has("hudX")) hudX = o.get("hudX").getAsInt();
				if (o.has("hudY")) hudY = o.get("hudY").getAsInt();
			}
		} catch (Exception e) {
			e.printStackTrace(); // fall back to defaults
		}
		save(); // adds missing keys, drops removed ones
	}

	public static void save() {
		try {
			JsonObject out = new JsonObject();
			out.addProperty("icon", icon);
			out.addProperty("format", format);
			out.addProperty("nametag", nametag);
			out.addProperty("chat", chat);
			out.addProperty("hud", hud);
			out.addProperty("hudX", hudX);
			out.addProperty("hudY", hudY);
			Files.writeString(file(), new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(out));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/** Builds the tag, e.g. "[ф 12]". Top 3 are gold, everything else light purple. */
	public static MutableComponent tag(int rank) {
		String text = format.replace("{icon}", icon).replace("{rank}", Integer.toString(rank));
		return Component.literal(text).withStyle(rank <= 3 ? ChatFormatting.GOLD : ChatFormatting.LIGHT_PURPLE);
	}
}
