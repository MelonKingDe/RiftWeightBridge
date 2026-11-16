package com.example.riftweight;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds the rank tag to chat messages of the form "<prefix> Name [other tags]: message".
 * The tag is inserted right before the ": ", so it is the rightmost tag after the name.
 *
 * Works for messages the server sends as system/game messages (e.g. Hypixel). Vanilla signed
 * player chat cannot be modified on the client.
 * The sender must be on the tab list; the first message of a player may come before his rank
 * has been loaded, later messages show it.
 */
public final class ChatRanks {

	private static final Pattern TRAILING_TAG = Pattern.compile("\\s*\\[[^\\]]*\\]\\s*$");
	private static final Pattern NAME_AT_END = Pattern.compile("[A-Za-z0-9_]{3,16}$");

	private record Segment(Style style, String text) {}

	private ChatRanks() {}

	public static void register() {
		ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
			if (overlay || !ModConfig.chat) return message; // overlay = action bar
			try {
				return modify(message);
			} catch (Exception e) {
				return message; // never break the chat
			}
		});
	}

	private static Component modify(Component message) {
		// Flatten into styled segments, so we can insert the tag without losing styles/hover/click
		List<Segment> segments = new ArrayList<>();
		StringBuilder plain = new StringBuilder();
		message.visit((style, text) -> {
			segments.add(new Segment(style, text));
			plain.append(text);
			return Optional.empty();
		}, Style.EMPTY);

		String text = plain.toString();
		int colon = text.indexOf(": ");
		if (colon < 3) return message;

		// Part before the colon, without trailing "[...]" tags that other mods put after the name
		String core = text.substring(0, colon);
		String previous;
		do {
			previous = core;
			core = TRAILING_TAG.matcher(core).replaceFirst("");
		} while (!core.equals(previous));
		core = core.stripTrailing();

		Matcher m = NAME_AT_END.matcher(core);
		if (!m.find()) return message;

		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection == null) return message;
		PlayerInfo info = connection.getPlayerInfo(m.group());
		if (info == null) return message; // not a player on the tab list
		UUID id = info.getProfile().id();

		RankCache.Entry entry = RankCache.get(id);
		if (entry == null || !entry.ranked()) return message;

		// Rebuild the message with the tag inserted before ": "
		MutableComponent out = Component.empty();
		int pos = 0;
		boolean inserted = false;
		for (Segment s : segments) {
			String t = s.text();
			int end = pos + t.length();
			if (!inserted && colon >= pos && colon < end) {
				int cut = colon - pos;
				if (cut > 0) out.append(Component.literal(t.substring(0, cut)).setStyle(s.style()));
				out.append(Component.literal(" ")).append(ModConfig.tag(entry.rank()));
				out.append(Component.literal(t.substring(cut)).setStyle(s.style()));
				inserted = true;
			} else {
				out.append(Component.literal(t).setStyle(s.style()));
			}
			pos = end;
		}
		return inserted ? out : message;
	}
}
