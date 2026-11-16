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
 *   autoScan - periodically check a random lobby player who has no rank yet (fills the database)
 *   scanIntervalSeconds - seconds between auto scans (minimum 5)
 */
public final class ModConfig {

	public static String icon = "\u0444"; // Cyrillic "ф"
	public static String format = "[{icon} {rank}]";
	public static boolean nametag = true;
	public static boolean chat = true;
	public static boolean autoScan = true;
	public static int scanIntervalSeconds = 10;

	private ModConfig() {}

	public static void load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("riftweight.json");
		try {
			if (Files.exists(file)) {
				JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
				if (o.has("icon")) icon = o.get("icon").getAsString();
				if (o.has("format")) format = o.get("format").getAsString();
				if (o.has("nametag")) nametag = o.get("nametag").getAsBoolean();
				if (o.has("chat")) chat = o.get("chat").getAsBoolean();
				if (o.has("autoScan")) autoScan = o.get("autoScan").getAsBoolean();
				if (o.has("scanIntervalSeconds")) scanIntervalSeconds = o.get("scanIntervalSeconds").getAsInt();
			}
			scanIntervalSeconds = Math.max(5, scanIntervalSeconds);
			// Write back so missing keys are added and the user can see all options
			JsonObject out = new JsonObject();
			out.addProperty("icon", icon);
			out.addProperty("format", format);
			out.addProperty("nametag", nametag);
			out.addProperty("chat", chat);
			out.addProperty("autoScan", autoScan);
			out.addProperty("scanIntervalSeconds", scanIntervalSeconds);
			Files.writeString(file, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(out));
		} catch (Exception e) {
			e.printStackTrace(); // fall back to defaults
		}
	}

	/** Builds the tag, e.g. "[ф 12]". Top 3 are gold, everything else light purple. */
	public static MutableComponent tag(int rank) {
		String text = format.replace("{icon}", icon).replace("{rank}", Integer.toString(rank));
		return Component.literal(text).withStyle(rank <= 3 ? ChatFormatting.GOLD : ChatFormatting.LIGHT_PURPLE);
	}
}
