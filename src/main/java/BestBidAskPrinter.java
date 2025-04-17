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
import java.util.*;

public class BestBidAskPrinter {

    private static final String WS_URI       = "wss://ws-feed.prime.coinbase.com";
    private static final String CHANNEL      = "l2_data";
    private static final String[] PRODUCT_IDS = {"ETH-USD", "BTC-USD"};

    private static class Book {
        final TreeMap<Double, Double> bids = new TreeMap<>(Collections.reverseOrder());
        final TreeMap<Double, Double> asks = new TreeMap<>();
    }
    private final Map<String, Book> books = new HashMap<>();

    private static final ThreadLocal<ObjectMapper> MAPPER =
            ThreadLocal.withInitial(ObjectMapper::new);

    public static void main(String[] args) throws Exception {
        new BestBidAskPrinter().start();
    }

    private void start() throws Exception {
        WebSocketClient client = new WebSocketClient(new URI(WS_URI)) {

            @Override public void onOpen(ServerHandshake hs) { send(buildSubscribeMessage()); }
            @Override public void onMessage(String msg)      { handle(msg); }
            @Override public void onClose(int c,String r,boolean rem){ System.out.println("WebSocket closed: "+r+" – reconnecting…");reconnect();}
            @Override public void onError(Exception ex)      { System.err.println("WebSocket error: "+ex.getMessage()); }
        };
        client.connectBlocking();
    }

    /* ---------- message handling ---------- */
    private void handle(String raw) {
        try {
            JsonNode root = MAPPER.get().readTree(raw);
            if (!CHANNEL.equals(root.path("channel").asText())) return;

            JsonNode events = root.path("events");
            if (!events.isArray() || events.isEmpty()) return;

            JsonNode evt  = events.get(0);
            String type    = evt.path("type").asText();
            String product = evt.path("product_id").asText();
            if (product.isEmpty()) return;

            JsonNode updates = evt.path("updates");
            if (!updates.isArray()) return;

            books.computeIfAbsent(product, p -> new Book());
            Book book = books.get(product);

            if ("snapshot".equals(type)) { book.bids.clear(); book.asks.clear(); }
            applyUpdates(updates, book);
            printBBA(product, book);

        } catch (Exception ex) {
            System.err.println("Parse error: " + ex.getMessage());
        }
    }

    private void applyUpdates(JsonNode updates, Book book) {
        for (JsonNode u : updates) {
            String side = u.path("side").asText();
            double px   = u.path("px").asDouble();
            double qty  = u.path("qty").asDouble();

            TreeMap<Double, Double> depth = "bid".equals(side) ? book.bids : book.asks;
            if (qty == 0.0) depth.remove(px); else depth.put(px, qty);
        }
    }

    private void printBBA(String product, Book book) {
        Map.Entry<Double, Double> bid = book.bids.firstEntry();
        Map.Entry<Double, Double> ask = book.asks.firstEntry();
        if (bid != null && ask != null) {
            System.out.printf(
                    "%s → Best Bid: %.8f (qty %.6f) | Best Ask: %.8f (qty %.6f)%n",
                    product, bid.getKey(), bid.getValue(), ask.getKey(), ask.getValue());
        }
    }

    private String buildSubscribeMessage() {
        long currentTimeMillis = System.currentTimeMillis();
        String ts = String.valueOf(currentTimeMillis / 1000);
        String key = env("API_KEY"), sec = env("SECRET_KEY"),
                pas = env("PASSPHRASE"), acct = env("SVC_ACCOUNTID");

        String sig = sign(CHANNEL, key, sec, acct, ts, PRODUCT_IDS);
        String prods = String.join("\",\"", PRODUCT_IDS);

        return String.format(
                "{"
                        + "\"type\":\"subscribe\","
                        + "\"channel\":\"%s\","
                        + "\"access_key\":\"%s\","
                        + "\"api_key_id\":\"%s\","
                        + "\"timestamp\":\"%s\","
                        + "\"passphrase\":\"%s\","
                        + "\"signature\":\"%s\","
                        + "\"product_ids\":[\"%s\"]"
                        + "}",
                CHANNEL, key, acct, ts, pas, sig, prods
        );
    }

    private static String sign(String ch, String key, String secret,
                               String acct, String ts, String[] prods) {
        String joined = String.join("", prods);
        String msg    = ch + key + acct + ts + joined;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder()
                    .encodeToString(mac.doFinal(msg.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) { throw new RuntimeException(e); }
    }

    private static String env(String n) {
        String v = System.getenv(n);
        if (v == null || v.isEmpty())
            throw new IllegalStateException("Missing env var: " + n);
        return v;
    }
}