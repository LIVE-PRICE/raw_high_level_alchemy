package net.runelite.client.plugins.microbot.highlevelalchemy;

import java.util.HashMap;
import java.util.Map;

/** Account-scoped ownership survives restarts, while progress clocks are run-local. */
final class AlchemyOfferTracker {
    static final long STALLED_MS = 120_000L;
    private final Map<Integer, String> owned = new HashMap<>();
    private final Map<Integer, Integer> traded = new HashMap<>();
    private final Map<Integer, Long> progressAt = new HashMap<>();

    void restore(String saved) {
        owned.clear();
        traded.clear();
        progressAt.clear();
        if (saved == null) return;
        for (String row : saved.split(";")) {
            String[] fields = row.split(":");
            if (fields.length != 4) continue;
            try {
                int slot = Integer.parseInt(fields[0]);
                int id = Integer.parseInt(fields[1]);
                int quantity = Integer.parseInt(fields[2]);
                long price = Long.parseLong(fields[3]);
                if (slot >= 0 && slot < 8 && id > 0 && quantity > 0 && price > 0)
                    remember(slot, id, quantity, price);
            } catch (NumberFormatException ignored) {
                // Ignore an invalid saved row without losing valid ownership records.
            }
        }
    }

    void remember(int slot, int id, int quantity, long price) {
        owned.put(slot, id + ":" + quantity + ":" + price);
    }

    boolean matches(int slot, int id, int quantity, long price) {
        return (id + ":" + quantity + ":" + price).equals(owned.get(slot));
    }

    void forget(int slot) {
        owned.remove(slot);
        traded.remove(slot);
        progressAt.remove(slot);
    }

    boolean stalled(int slot, int filled, long now) {
        Integer previous = traded.put(slot, filled);
        if (previous == null || previous != filled) progressAt.put(slot, now);
        return now - progressAt.get(slot) >= STALLED_MS;
    }

    String serialize() {
        StringBuilder saved = new StringBuilder();
        owned.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(entry -> saved.append(entry.getKey()).append(':').append(entry.getValue()).append(';'));
        return saved.toString();
    }
}
