package com.example.riftweight;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Minimal Discord Rich Presence client (no external library).
 * Talks to the running Discord desktop app over its local IPC pipe (Windows named pipe or
 * Unix socket). Strictly request/response on ONE thread - do not share it between threads.
 */
public final class DiscordIpc implements AutoCloseable {

	/** Reason of the last failed connect(), for logging. */
	public static volatile String lastError = null;

	private interface Pipe {
		void write(byte[] data) throws IOException;

		void readFully(byte[] buffer) throws IOException;

		void close();
	}

	private record Frame(int opcode, String json) {}

	private final Pipe pipe;

	private DiscordIpc(Pipe pipe) {
		this.pipe = pipe;
	}

	/** Connects to the first running Discord client. Returns null if Discord is not running / refuses. */
	public static DiscordIpc connect(String clientId) {
		Set<String> errors = new LinkedHashSet<>();
		for (int i = 0; i < 10; i++) {
			for (String path : candidatePaths(i)) {
				Pipe pipe = null;
				try {
					pipe = open(path);
					if (pipe == null) continue;
					DiscordIpc ipc = new DiscordIpc(pipe);
					ipc.handshake(clientId);
					lastError = null;
					return ipc;
				} catch (Exception e) {
					// same message for all 10 pipe numbers -> keep only distinct ones
					errors.add(e.toString().replaceAll("discord-ipc-\\d", "discord-ipc-N"));
					if (pipe != null) pipe.close();
				}
			}
		}
		lastError = errors.isEmpty() ? "no Discord pipe found (is the Discord desktop app running?)"
				: String.join(" | ", errors);
		return null;
	}

	/** Shows "details" as the first line of the activity, with an elapsed-time counter since startEpochSeconds. */
	public void setActivity(String details, long startEpochSeconds) throws IOException {
		if (details.length() > 128) details = details.substring(0, 128); // Discord limit
		String json = "{\"cmd\":\"SET_ACTIVITY\",\"args\":{\"pid\":" + ProcessHandle.current().pid()
				+ ",\"activity\":{\"details\":" + quote(details)
				+ ",\"timestamps\":{\"start\":" + startEpochSeconds + "}}}"
				+ ",\"nonce\":" + quote(UUID.randomUUID().toString()) + "}";
		send(1, json);
		Frame response = readResponse();
		if (response.json().contains("\"evt\":\"ERROR\"")) {
			throw new IOException("Discord rejected the activity: " + response.json());
		}
	}

	@Override
	public void close() {
		pipe.close();
	}

	// ---------------------------------------------------------------- protocol

	private void handshake(String clientId) throws IOException {
		send(0, "{\"v\":1,\"client_id\":" + quote(clientId) + "}");
		Frame frame = readResponse();
		if (frame.opcode() == 2 || !frame.json().contains("\"READY\"")) {
			throw new IOException("Discord handshake failed: " + frame.json());
		}
	}

	private void send(int opcode, String json) throws IOException {
		byte[] payload = json.getBytes(StandardCharsets.UTF_8);
		ByteBuffer buffer = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(opcode).putInt(payload.length).put(payload);
		pipe.write(buffer.array()); // one write per frame
	}

	private Frame read() throws IOException {
		byte[] header = new byte[8];
		pipe.readFully(header);
		ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
		int opcode = h.getInt();
		int length = h.getInt();
		if (length < 0 || length > 1_000_000) throw new IOException("Bad frame length " + length);
		byte[] body = new byte[length];
		pipe.readFully(body);
		return new Frame(opcode, new String(body, StandardCharsets.UTF_8));
	}

	/** Reads the next frame; answers Discord's PING (3) with PONG (4) on the way. */
	private Frame readResponse() throws IOException {
		Frame frame;
		do {
			frame = read();
			if (frame.opcode() == 3) send(4, frame.json());
		} while (frame.opcode() == 3);
		return frame;
	}

	/** JSON string literal; non-ASCII is escaped so the payload is plain ASCII. */
	static String quote(String s) {
		StringBuilder sb = new StringBuilder("\"");
		for (char c : s.toCharArray()) {
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04x", (int) c));
					else sb.append(c);
				}
			}
		}
		return sb.append('"').toString();
	}

	// ---------------------------------------------------------------- transport

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	private static List<String> candidatePaths(int i) {
		List<String> paths = new ArrayList<>();
		if (isWindows()) {
			paths.add("\\\\.\\pipe\\discord-ipc-" + i);
			return paths;
		}
		List<String> bases = new ArrayList<>();
		for (String env : new String[]{"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
			String v = System.getenv(env);
			if (v != null && !v.isBlank()) bases.add(v);
		}
		bases.add("/tmp");
		String[] subDirs = {"", "snap.discord/", "app/com.discordapp.Discord/", "app/com.discordapp.DiscordCanary/"};
		for (String base : bases) {
			String b = base.endsWith("/") ? base : base + "/";
			for (String sub : subDirs) paths.add(b + sub + "discord-ipc-" + i);
		}
		return paths;
	}

	private static Pipe open(String path) throws IOException {
		if (isWindows()) {
			return new FilePipe(new RandomAccessFile(path, "rw")); // throws if the pipe does not exist
		}
		if (!Files.exists(Path.of(path))) return null;
		return new SocketPipe(SocketChannel.open(UnixDomainSocketAddress.of(path)));
	}

	private static final class FilePipe implements Pipe {
		private final RandomAccessFile file;

		FilePipe(RandomAccessFile file) {
			this.file = file;
		}

		@Override
		public void write(byte[] data) throws IOException {
			file.write(data);
		}

		@Override
		public void readFully(byte[] buffer) throws IOException {
			file.readFully(buffer);
		}

		@Override
		public void close() {
			try {
				file.close();
			} catch (IOException ignored) {
			}
		}
	}

	private static final class SocketPipe implements Pipe {
		private final SocketChannel channel;

		SocketPipe(SocketChannel channel) {
			this.channel = channel;
		}

		@Override
		public void write(byte[] data) throws IOException {
			ByteBuffer buffer = ByteBuffer.wrap(data);
			while (buffer.hasRemaining()) channel.write(buffer);
		}

		@Override
		public void readFully(byte[] data) throws IOException {
			ByteBuffer buffer = ByteBuffer.wrap(data);
			while (buffer.hasRemaining()) {
				if (channel.read(buffer) < 0) throw new EOFException();
			}
		}

		@Override
		public void close() {
			try {
				channel.close();
			} catch (IOException ignored) {
			}
		}
	}
}
