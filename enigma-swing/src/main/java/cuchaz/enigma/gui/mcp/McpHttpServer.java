package cuchaz.enigma.gui.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import cuchaz.enigma.gui.Gui;

/**
 * A small HTTP server exposing the Enigma project over the Model Context Protocol.
 * Bound to the loopback interface only, so it is reachable from local MCP clients but
 * not from the network.
 */
public final class McpHttpServer {
	private final Gui gui;
	private final int port;
	private final boolean bindAll;

	private HttpServer server;
	private McpProtocol protocol;

	public McpHttpServer(Gui gui, int port, boolean bindAll) {
		this.gui = gui;
		this.port = port;
		this.bindAll = bindAll;
	}

	public synchronized void start() throws IOException {
		if (this.server != null) {
			return;
		}

		this.protocol = new McpProtocol(this.gui);
		InetSocketAddress address = this.bindAll ? new InetSocketAddress(this.port) : new InetSocketAddress(InetAddress.getLoopbackAddress(), this.port);
		this.server = HttpServer.create(address, 0);
		this.server.createContext("/mcp", this::handle);
		this.server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "enigma-mcp");
			thread.setDaemon(true);
			return thread;
		}));
		this.server.start();
		System.out.println("[MCP] server started on " + (this.bindAll ? "0.0.0.0" : "127.0.0.1") + ":" + this.port);
	}

	public synchronized void stop() {
		if (this.server != null) {
			this.server.stop(0);
			this.server = null;
			this.protocol = null;
			System.out.println("[MCP] server stopped");
		}
	}

	public synchronized boolean isRunning() {
		return this.server != null;
	}

	public int getPort() {
		return this.port;
	}

	private void handle(HttpExchange exchange) throws IOException {
		try {
			if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
				respond(exchange, 405, "application/json", "{\"error\":\"Only POST is supported\"}");
				return;
			}

			String body;

			try (InputStream in = exchange.getRequestBody()) {
				body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}

			String response = this.protocol.handle(body);

			if (response == null) {
				exchange.sendResponseHeaders(202, -1);
				exchange.close();
				return;
			}

			respond(exchange, 200, "application/json", response);
		} catch (RuntimeException e) {
			respond(exchange, 500, "application/json", "{\"error\":\"" + e.toString().replace("\"", "'") + "\"}");
		}
	}

	private void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.sendResponseHeaders(status, bytes.length);

		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}
}
