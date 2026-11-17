package com.example.riftweight;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds the rank tag to chat messages of the form "<prefix> Name [other tags]: message".
 * The text before the FIRST colon is split into words; the last word that is a player of the
 * database (see {@link RankBoard}) is the sender. The tag is inserted right before the colon, so it
 * is the rightmost tag after the name.
 *
 * Works for messages the server sends as system/game messages (e.g. Hypixel).
 */
public final class ChatRanks {

	/** A word that can be a Minecraft name; symbols glued to it are ignored. */
	private static final Pattern NAME_TOKEN = Pattern.compile("(?<![A-Za-z0-9_])[A-Za-z0-9_]{3,16}(?![A-Za-z0-9_])");

	private record Segment(Style style, String text) {}

	private static final ArrayDeque<String> DEBUG = new ArrayDeque<>();
	private static volatile long messagesSeen = 0;

	private ChatRanks() {}

	public static void register() {
		ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
			if (overlay || !ModConfig.chat) return message; // overlay = action bar
			try {
				return modify(message);
			} catch (Exception e) {
				debug("EXCEPTION " + e);
				return message; // never break the chat
			}
		});
	}

	private static Component modify(Component message) {
		messagesSeen++;

		// Flatten into styled segments, so we can insert the tag without losing styles/hover/click
		List<Segment> segments = new ArrayList<>();
		StringBuilder plain = new StringBuilder();
		message.visit((style, text) -> {
			segments.add(new Segment(style, text));
			plain.append(text);
			return Optional.empty();
		}, Style.EMPTY);

		String text = plain.toString();
		int colon = text.indexOf(':');
		if (colon <= 0) return message; // not a chat line

		String sender = findSender(text.substring(0, colon));
		int rank = sender == null ? 0 : RankBoard.rankOf(sender);
		debug("\"" + describe(text) + "\" -> " + (sender == null ? "no player of the database before the colon"
				: sender + " #" + rank));
		if (sender == null || rank <= 0) return message;

		// Rebuild the message with the tag inserted before the colon
		MutableComponent out = Component.empty();
		int pos = 0;
		boolean inserted = false;
		for (Segment s : segments) {
			String t = s.text();
			int end = pos + t.length();
			if (!inserted && colon >= pos && colon < end) {
				int cut = colon - pos;
				if (cut > 0) out.append(Component.literal(t.substring(0, cut)).setStyle(s.style()));
				out.append(Component.literal(" ")).append(ModConfig.tag(rank));
				out.append(Component.literal(t.substring(cut)).setStyle(s.style()));
				inserted = true;
			} else {
				out.append(Component.literal(t).setStyle(s.style()));
			}
			pos = end;
		}
		return inserted ? out : message;
	}

	/** Last word in the prefix that is in the database, or null. */
	private static String findSender(String prefix) {
		List<String> words = new ArrayList<>();
		Matcher m = NAME_TOKEN.matcher(prefix);
		while (m.find()) words.add(m.group());
		for (int i = words.size() - 1; i >= 0; i--) {
			if (RankBoard.rankOf(words.get(i)) > 0) return words.get(i);
		}
		return null;
	}

	// ---------------------------------------------------------------- /riftweight chatdebug

	private static String describe(String text) {
		String s = text.length() > 48 ? text.substring(0, 48) + "..." : text;
		StringBuilder sb = new StringBuilder();
		for (char c : s.toCharArray()) {
			if (c >= 0x20 && c < 0x7f) sb.append(c);
			else sb.append(String.format("\\u%04x", (int) c)); // make invisible characters visible
		}
		return sb.toString();
	}

	private static synchronized void debug(String line) {
		DEBUG.addLast(line);
		while (DEBUG.size() > 12) DEBUG.removeFirst();
	}

	/** The last chat lines with a colon and what the mod made of them. */
	public static synchronized List<String> debugLines() {
		return new ArrayList<>(DEBUG);
	}

	public static long messagesSeen() {
		return messagesSeen;
	}
}
