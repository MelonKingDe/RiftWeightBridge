package com.example.riftweight;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD list of all players in your lobby (tab list) who are NOT in the database yet,
 * so you can check them with /riftweight <name>. Players in the database never appear here.
 * Toggle with /riftweight hud, position in config/riftweight.json (hudX, hudY).
 */
public final class MissingHud {

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
		if (mc.getConnection() == null) return;

		Lobby.Result result = Lobby.scan();
		List<Lobby.Player> missing = result.missing();
		int loading = result.loading();
		int errors = result.errors();
		if (missing.isEmpty() && errors == 0) return; // nothing to show

		List<Line> lines = new ArrayList<>();
		if (!missing.isEmpty()) {
			lines.add(new Line("Not in database (" + missing.size() + ")", GOLD));
			for (int i = 0; i < Math.min(MAX_LINES, missing.size()); i++) {
				lines.add(new Line(missing.get(i).name(), WHITE));
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
