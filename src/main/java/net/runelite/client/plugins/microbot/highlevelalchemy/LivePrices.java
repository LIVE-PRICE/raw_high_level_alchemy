package net.runelite.client.plugins.microbot.highlevelalchemy;

import com.google.gson.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.slf4j.LoggerFactory;

/** Latest executed instant-buy prices; never substitutes old CSV prices or zero. */
final class LivePrices {
    static final class Quote {
        final int price; final long timestamp;
        Quote(int price,long timestamp){this.price=price;this.timestamp=timestamp;}
    }
    private static final class QuoteSnapshot {
        final Map<Integer, Quote> quotes;
        final long fetched;

        QuoteSnapshot(Map<Integer, Quote> quotes, long fetched) {
            this.quotes = quotes;
            this.fetched = fetched;
        }
    }
    private volatile QuoteSnapshot latest = new QuoteSnapshot(Collections.emptyMap(), 0);
    private final Map<Integer, Quote> lastKnown = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean membersRequested;
    private volatile Map<Integer, AlchemyPolicy.Item> membersItems;

    void refresh() {
        try {
            Map<Integer, Quote> quotes = parse(fetch("latest").getAsJsonObject());
            lastKnown.putAll(quotes);
            latest = new QuoteSnapshot(quotes, System.currentTimeMillis());
        } catch (IOException | RuntimeException e) {
            LoggerFactory.getLogger(LivePrices.class).warn("HIGH_LEVEL_ALCHEMY price refresh failed", e);
        }
    }
    Map<Integer, AlchemyPolicy.Item> requestMembersItems() {
        membersRequested = true;
        return membersItems;
    }
    void refreshMembersItems() {
        if (!membersRequested || membersItems != null) return;
        try {
            Map<Integer, AlchemyPolicy.Item> items = parseMembersItems(fetch("mapping").getAsJsonArray());
            if (items.isEmpty()) throw new IOException("Price API returned no members items");
            membersItems = Collections.unmodifiableMap(items);
        } catch (IOException | RuntimeException e) {
            LoggerFactory.getLogger(LivePrices.class).warn("HIGH_LEVEL_ALCHEMY members catalogue refresh failed", e);
        }
    }
    private static JsonElement fetch(String route) throws IOException {
        HttpURLConnection c=(HttpURLConnection)new URL("https://prices.runescape.wiki/api/v1/osrs/" + route).openConnection();
        c.setRequestProperty("User-Agent","HIGH_LEVEL_ALCHEMY/1.0.15 Microbot personal alchemy plugin");
        c.setConnectTimeout(8000); c.setReadTimeout(8000);
        try {
            if(c.getResponseCode()!=200) throw new IOException("Price API HTTP "+c.getResponseCode());
            try(Reader reader=new InputStreamReader(c.getInputStream(),StandardCharsets.UTF_8)) {
                return new JsonParser().parse(reader);
            }
        } finally {c.disconnect();}
    }
    static Map<Integer, AlchemyPolicy.Item> parseMembersItems(JsonArray mapping) {
        Map<Integer, AlchemyPolicy.Item> items = new LinkedHashMap<>();
        for (JsonElement entry : mapping) {
            JsonObject row = entry.getAsJsonObject();
            if (!row.has("members") || row.get("members").isJsonNull() || !row.get("members").getAsBoolean()
                    || !row.has("highalch") || row.get("highalch").isJsonNull()
                    || !row.has("limit") || row.get("limit").isJsonNull()) continue;
            int id = row.get("id").getAsInt();
            int highAlch = row.get("highalch").getAsInt();
            int limit = row.get("limit").getAsInt();
            if (id <= 0 || highAlch <= 0 || limit <= 0 || id == 561 || id == 563) continue;
            items.putIfAbsent(id, new AlchemyPolicy.Item(row.get("name").getAsString(), id, highAlch, limit));
        }
        return items;
    }
    static Map<Integer,Quote> parse(JsonObject root) {
        Map<Integer,Quote> result=new HashMap<>();
        for(Map.Entry<String,JsonElement> entry:root.getAsJsonObject("data").entrySet()) {
            JsonObject q=entry.getValue().getAsJsonObject();
            if(!q.has("high") || q.get("high").isJsonNull() || !q.has("highTime") || q.get("highTime").isJsonNull()) continue;
            long price=q.get("high").getAsLong(), time=q.get("highTime").getAsLong();
            if(price>0 && price<=Integer.MAX_VALUE && time>0) result.put(Integer.parseInt(entry.getKey()),new Quote((int)price,time));
        }
        return result;
    }
    int ownedPrice(int id,long now) {
        int fresh=price(id,now);
        if(fresh>0)return fresh;
        Quote known=lastKnown.get(id);
        return known==null?0:known.price;
    }
    int price(int id,long now) {
        QuoteSnapshot snapshot = latest;
        Quote q=snapshot.quotes.get(id);
        return q!=null && now-snapshot.fetched<=120000 && now/1000-q.timestamp<=3600 && q.timestamp<=now/1000+60 ? q.price:0;
    }
}
