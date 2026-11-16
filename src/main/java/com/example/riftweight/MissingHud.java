package com.example.riftweight;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * HUD list of all players in your lobby (tab list) who are NOT in the database yet,
 * so you can check them with /riftweight <name>. Players in the database never appear here.
 * Toggle with /riftweight hud, position in config/riftweight.json (hudX, hudY).
 */
public final class MissingHud {

	private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
	private static final int MAX_LINES = 14;

	private static final int GOLD = 0xFFFFAA00;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFAAAAAA;
	private static final int RED = 0xFFFF5555;
	private static final int BACKGROUND = 0x90000000;

	private record Line(String text, int color) {}

	private MissingHud() {}

	public static void register() {
		HudElementRegistry.attachElementBefore(
				VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath("riftweight", "missing_players"),
				MissingHud::extract);
	}

	private static void extract(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
		if (!ModConfig.hud) return;
		Minecraft mc = Minecraft.getInstance();
		ClientPacketListener connection = mc.getConnection();
		if (connection == null) return;

		List<String> missing = new ArrayList<>();
		int loading = 0;
		int errors = 0;
		for (PlayerInfo info : connection.getOnlinePlayers()) {
			var profile = info.getProfile();
			UUID id = profile.id();
			String name = profile.name();
			// real accounts only: skips NPCs and the fake tab entries of Hypixel
			if (id == null || name == null || id.version() != 4 || !NAME.matcher(name).matches()) continue;

			RankCache.Entry entry = RankCache.get(id); // also queues the lookup
			if (entry == null) loading++;
			else if (entry.rank() == 0) missing.add(name);
			else if (entry.rank() < 0) errors++;
		}
		if (missing.isEmpty() && errors == 0) return; // nothing to show

		missing.sort(Comparator.comparing(String::toLowerCase));

		List<Line> lines = new ArrayList<>();
		if (!missing.isEmpty()) {
			lines.add(new Line("Not in database (" + missing.size() + ")", GOLD));
			for (int i = 0; i < Math.min(MAX_LINES, missing.size()); i++) {
				lines.add(new Line(missing.get(i), WHITE));
			}
			if (missing.size() > MAX_LINES) {
				lines.add(new Line("+" + (missing.size() - MAX_LINES) + " more", GRAY));
			}
		}
		if (loading > 0) lines.add(new Line("checking " + loading + " ...", GRAY));
		if (errors > 0) lines.add(new Line("API unreachable", RED));

		Font font = mc.font;
		int lineHeight = font.lineHeight + 1;
		int maxWidth = 0;
		for (Line l : lines) maxWidth = Math.max(maxWidth, font.width(l.text()));
		int boxWidth = maxWidth + 6;
		int boxHeight = lines.size() * lineHeight + 5;

		int x = ModConfig.hudX >= 0
				? ModConfig.hudX
				: mc.getWindow().getGuiScaledWidth() + ModConfig.hudX - boxWidth;
		int y = ModConfig.hudY;

		graphics.fill(x, y, x + boxWidth, y + boxHeight, BACKGROUND);
		int textY = y + 3;
		for (Line l : lines) {
			graphics.text(font, l.text(), x + 3, textY, l.color(), true);
			textY += lineHeight;
		}
	}
}
