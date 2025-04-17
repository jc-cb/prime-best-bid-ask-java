/*
 * Copyright 2025-present Coinbase Global, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

public class BestBidAskPrinter {

    private static final String WS_URI = "wss://ws-feed.prime.coinbase.com";
    private static final String CHANNEL = "l2_data";
    private static final String PRODUCT_ID = "ETH-USD";

    // bids: highest‑price first
    private final TreeMap<Double, Double> bids =
            new TreeMap<>((a, b) -> Double.compare(b, a));
    // asks: lowest‑price first
    private final TreeMap<Double, Double> asks = new TreeMap<>();

    private final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        new BestBidAskPrinter().start();
    }

    private void start() throws Exception {
        URI uri = new URI(WS_URI);
        WebSocketClient client = new WebSocketClient(uri) {

            @Override public void onOpen(ServerHandshake handshake) {
                send(buildSubscribeMessage());
            }

            @Override public void onMessage(String msg) {
                handleMessage(msg);
            }

            @Override public void onClose(int code, String reason, boolean remote) {
                System.out.println("WebSocket closed: " + reason + " - reconnecting…");
                reconnect();
            }

            @Override public void onError(Exception ex) {
                System.err.println("WebSocket error: " + ex.getMessage());
            }
        };

        client.connectBlocking();
    }

    private void handleMessage(String raw) {
        try {
            JsonNode root = mapper.readTree(raw);
            if (!root.path("channel").asText().equals(CHANNEL)) return;

            JsonNode events = root.path("events");
            if (!events.isArray() || events.isEmpty()) return;

            JsonNode firstEvent = events.get(0);
            String type = firstEvent.path("type").asText();

            if ("snapshot".equals(type)) {
                bids.clear();
                asks.clear();
                applyUpdates(firstEvent.path("updates"));
            } else if ("l2update".equals(type) || "update".equals(type)) { // "update" for Prime
                applyUpdates(firstEvent.path("updates"));
            }

            printBestBidAsk();
        } catch (Exception e) {
            System.err.println("Failed to process message: " + e.getMessage());
        }
    }

    private void applyUpdates(JsonNode updates) {
        if (!updates.isArray()) return;
        for (JsonNode u : updates) {
            String side = u.path("side").asText();
            double price  = u.path("px").asDouble();
            double qty    = u.path("qty").asDouble();

            TreeMap<Double, Double> book = "bid".equals(side) ? bids : asks;
            if (qty == 0.0) {
                book.remove(price);
            } else {
                book.put(price, qty);
            }
        }
    }

    private void printBestBidAsk() {
        Map.Entry<Double, Double> bestBid = bids.firstEntry();
        Map.Entry<Double, Double> bestAsk = asks.firstEntry();
        if (bestBid != null && bestAsk != null) {
            System.out.printf(
                    "Best Bid: %.8f (qty %.6f) | Best Ask: %.8f (qty %.6f)%n",
                    bestBid.getKey(), bestBid.getValue(),
                    bestAsk.getKey(), bestAsk.getValue());
        }
    }

    private String buildSubscribeMessage() {
        long ts = System.currentTimeMillis() / 1000;
        String timestamp = String.valueOf(ts);

        String apiKey   = env("API_KEY");
        String secret   = env("SECRET_KEY");
        String pass     = env("PASSPHRASE");
        String acctId   = env("SVC_ACCOUNTID");

        String signature = sign(CHANNEL, apiKey, secret, acctId, timestamp, PRODUCT_ID);

        return String.format(
                "{" +
                        "\"type\":\"subscribe\"," +
                        "\"channel\":\"%s\"," +
                        "\"access_key\":\"%s\"," +
                        "\"api_key_id\":\"%s\"," +
                        "\"timestamp\":\"%s\"," +
                        "\"passphrase\":\"%s\"," +
                        "\"signature\":\"%s\"," +
                        "\"product_ids\":[\"%s\"]" +
                        "}", CHANNEL, apiKey, acctId, timestamp, pass, signature, PRODUCT_ID);
    }

    private static String sign(String channel, String key, String secret,
                               String accountId, String timestamp, String productId) {
        String message = channel + key + accountId + timestamp + productId;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] rawSig = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(rawSig);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("HMAC failure", e);
        }
    }

    private static String env(String name) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException("Missing env var: " + name);
        }
        return v;
    }
}
