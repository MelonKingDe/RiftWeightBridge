package com.example.riftweight;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Helpers to insert a component into an existing one without losing its styles. */
public final class ComponentUtil {

	/** Leading SkyBlock level such as "[481] ". */
	private static final Pattern LEVEL = Pattern.compile("^\\s*\\[\\d+\\]\\s*");

	/** A word that can be a Minecraft name (3-16 chars), not part of a longer word. */
	private static final Pattern NAME_TOKEN = Pattern.compile("(?<![A-Za-z0-9_])[A-Za-z0-9_]{3,16}(?![A-Za-z0-9_])");

	private record Segment(Style style, String text) {}

	private ComponentUtil() {}

	/**
	 * Finds the real online player an entry belongs to: the first word (of the first few) in the text that
	 * is the name of a player on the tab list. Needed because on Hypixel SkyBlock the visible tab entries
	 * are fake entries with a different UUID. Returns null for headers, offline entries etc.
	 */
	public static PlayerInfo findOnlinePlayer(Component component, ClientPacketListener connection) {
		Matcher m = NAME_TOKEN.matcher(component.getString());
		for (int tries = 0; tries < 4 && m.find(); tries++) {
			PlayerInfo info = connection.getPlayerInfo(m.group());
			if (info != null) return info;
		}
		return null;
	}

	/** Puts {@code insertion} in front of the whole component. */
	public static Component insertAtStart(Component original, Component insertion) {
		return Component.empty().append(insertion).append(original);
	}

	/**
	 * Inserts {@code insertion} right after a leading "[level] " (or at the very start if there is none).
	 * Example: "[481] MelonKingDe" -> "[481] ф MelonKingDe".
	 */
	public static Component insertAfterLevel(Component original, Component insertion) {
		List<Segment> segments = new ArrayList<>();
		StringBuilder plain = new StringBuilder();
		original.visit((style, text) -> {
			segments.add(new Segment(style, text));
			plain.append(text);
			return Optional.empty();
		}, Style.EMPTY);
		if (plain.length() == 0) return original;

		Matcher m = LEVEL.matcher(plain);
		int index = m.find() ? m.end() : 0;

		MutableComponent out = Component.empty();
		int pos = 0;
		boolean done = false;
		for (Segment s : segments) {
			String t = s.text();
			int end = pos + t.length();
			if (!done && index >= pos && index < end) {
				int cut = index - pos;
				if (cut > 0) out.append(Component.literal(t.substring(0, cut)).setStyle(s.style()));
				out.append(insertion);
				out.append(Component.literal(t.substring(cut)).setStyle(s.style()));
				done = true;
			} else {
				out.append(Component.literal(t).setStyle(s.style()));
			}
			pos = end;
		}
		if (!done) out.append(insertion);
		return out;
	}
}
