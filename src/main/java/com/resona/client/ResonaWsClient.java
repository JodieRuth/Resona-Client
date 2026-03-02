package com.resona.client;

import java.net.URI;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class ResonaWsClient extends WebSocketClient {

    public interface StatusListener {

        void onStatus(String message);
    }

    private static final Gson GSON = new Gson();
    private final StatusListener statusListener;

    public ResonaWsClient(URI serverUri, StatusListener statusListener) {
        super(serverUri);
        this.statusListener = statusListener;
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        if (statusListener != null) {
            statusListener.onStatus("[Resona] ws connected");
        }
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "mcp_register");
        obj.addProperty("role", "minecraft_mod");
        com.google.gson.JsonArray prefixes = new com.google.gson.JsonArray();
        prefixes.add("mc");
        obj.add("prefixes", prefixes);
        send(GSON.toJson(obj));
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonObject obj = GSON.fromJson(message, JsonObject.class);
            if (obj != null && obj.has("type")
                && "mcp_request".equals(
                    obj.get("type")
                        .getAsString())) {
                JsonObject response = ResonaMcpServer.get()
                    .handleWsRequest(obj);
                response.addProperty("type", "mcp_response");
                if (obj.has("id")) {
                    response.add("id", obj.get("id"));
                }
                send(GSON.toJson(response));
                return;
            }
        } catch (Exception e) {}
        if (statusListener != null) {
            statusListener.onStatus("[Resona] ws message: " + message);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        if (statusListener != null) {
            statusListener.onStatus("[Resona] ws closed");
        }
    }

    @Override
    public void onError(Exception ex) {
        if (statusListener != null) {
            statusListener.onStatus("[Resona] ws error: " + (ex == null ? "unknown" : ex.getMessage()));
        }
    }

    public boolean sendQuestion(String text) {
        if (!isOpen()) {
            return false;
        }
        JsonObject obj = new JsonObject();
        obj.addProperty("question", text);
        send(GSON.toJson(obj));
        return true;
    }
}
