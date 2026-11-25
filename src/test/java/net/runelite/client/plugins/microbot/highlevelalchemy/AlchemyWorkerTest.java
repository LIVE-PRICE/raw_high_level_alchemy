package net.runelite.client.plugins.microbot.highlevelalchemy;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AlchemyWorkerTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;

    @Test void completedOffersUseSnapshotNamesWithoutCallingTheClient() throws Exception {
        HighLevelAlchemyPlugin plugin = new HighLevelAlchemyPlugin();
        GeTradeHistoryDatabase database = new GeTradeHistoryDatabase(directory);
        field("tradeHistory").set(plugin, database);
        field("client").set(plugin, java.lang.reflect.Proxy.newProxyInstance(
            net.runelite.api.Client.class.getClassLoader(),
            new Class<?>[]{net.runelite.api.Client.class},
            (proxy, method, arguments) -> { throw new AssertionError("Worker called client API: " + method.getName()); }));
        Class<?> offerType = Class.forName(HighLevelAlchemyPlugin.class.getName() + "$Offer");
        java.lang.reflect.Constructor<?> offerConstructor = offerType.getDeclaredConstructor();
        offerConstructor.setAccessible(true);
        Object offer = offerConstructor.newInstance();
        set(offer, "id", 1201);
        set(offer, "name", "Rune kiteshield");
        set(offer, "state", net.runelite.api.GrandExchangeOfferState.BOUGHT);
        set(offer, "traded", 2);
        set(offer, "spent", 60000L);
        Class<?> snapshotType = Class.forName(HighLevelAlchemyPlugin.class.getName() + "$Snapshot");
        java.lang.reflect.Constructor<?> snapshotConstructor = snapshotType.getDeclaredConstructor();
        snapshotConstructor.setAccessible(true);
        Object snapshot = snapshotConstructor.newInstance();
        set(snapshot, "offers", java.util.Collections.singletonList(offer));
        java.lang.reflect.Method record = HighLevelAlchemyPlugin.class.getDeclaredMethod("recordTradeOffers", snapshotType);
        record.setAccessible(true);
        record.invoke(plugin, snapshot);
        assertEquals(30000L, database.latestBuyPrice(0, 1201, "Rune kiteshield").longValue());
        assertTrue(java.nio.file.Files.readString(database.accountFile(0)).contains("Rune kiteshield"));
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test void failedPeriodicTaskStopsInsteadOfLeavingThePluginStuckRunning() throws Exception {
        HighLevelAlchemyPlugin plugin = new HighLevelAlchemyPlugin();
        CompletableFuture<Void> task = new CompletableFuture<>();
        task.completeExceptionally(new NoSuchMethodError("missing client API"));
        field("running").set(plugin, true);
        field("runTask").set(plugin, task);
        plugin.checkWorker();
        assertFalse(field("running").getBoolean(plugin));
        assertTrue(field("status").get(plugin).toString().contains("NoSuchMethodError"));
    }
    @Test void pendingTaskRemainsRunning() throws Exception {
        HighLevelAlchemyPlugin plugin = new HighLevelAlchemyPlugin();
        field("running").set(plugin, true);
        field("runTask").set(plugin, new CompletableFuture<Void>());
        plugin.checkWorker();
        assertTrue(field("running").getBoolean(plugin));
    }
    private static Field field(String name) throws Exception {
        Field field = HighLevelAlchemyPlugin.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
}
