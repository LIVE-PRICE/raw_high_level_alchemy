package net.runelite.client.plugins.microbot.highlevelalchemy;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AlchemyPolicyTest {
    @Test void purchaseComparisonUsesActualFillSpendAndFormatsUnitAndTotals() {
        assertEquals("Last Purchase: 70 × Rune platelegs\n"
                + "Price Bought: 37,671 | 2,636,970\n"
                + "High Alch Value: 38,400 | 2,688,000",
            PurchaseComparison.format("Rune platelegs", 70, 2_636_970, 38_000, 38_400));
    }
    @Test void withdrawalsStopAbove25OccupiedSlots() {
        assertEquals(26, AlchemyPolicy.withdrawalSlots(28));
        assertEquals(2, AlchemyPolicy.withdrawalSlots(4));
        assertEquals(1, AlchemyPolicy.withdrawalSlots(3));
        assertEquals(0, AlchemyPolicy.withdrawalSlots(2));
        assertEquals(0, AlchemyPolicy.withdrawalSlots(0));
    }
    @Test void exactUserFormulasAndOverflow() {
        AlchemyPolicy.Item rune=new AlchemyPolicy.Item("Rune platebody",1127,39000,70);
        assertEquals(2730000,rune.buyLimitHighAlchValue());
        assertEquals(2660000,rune.buyLimitLivePrice(38000));
        assertEquals(70000,rune.havbld(38000));
        assertEquals(-70000,rune.havbld(40000));
        assertEquals(36_000_000_000L,new AlchemyPolicy.Item("Large",1,2000000,18000).buyLimitHighAlchValue());
    }
    @Test void neverPlacePartialAlchemyItemOffers() {
        AlchemyPolicy.Item item=new AlchemyPolicy.Item("Test",1,200,100);
        assertEquals(0,AlchemyPolicy.quantity(item,100,9000,10001));
        assertEquals(0,AlchemyPolicy.quantity(item,100,9000,10000));
        // Below, equal to, and above the old owned-value threshold must all reject partials.
        for(long owned:new long[]{0,9999,10000,10001,100000}) {
            for(long coins:new long[]{0,1,99,100,9000,9999})
                assertEquals(0,AlchemyPolicy.quantity(item,100,coins,owned));
            assertEquals(100,AlchemyPolicy.quantity(item,100,10000,owned));
        }
        // Insufficient cash must never shrink a batch to 99.
        assertEquals(0,AlchemyPolicy.quantity(item,100,10000-100,0));
        AlchemyPolicy.Item rune=new AlchemyPolicy.Item("Rune platebody",1127,39000,70);
        assertEquals(0,AlchemyPolicy.quantity(rune,38000,2659999,0));
        assertEquals(70,AlchemyPolicy.quantity(rune,38000,2660000,0));
        assertEquals(100,AlchemyPolicy.quantity(item,100,10000,10001));
        assertEquals(100,AlchemyPolicy.quantity(item,100,50000,10001));
        assertEquals(0,AlchemyPolicy.quantity(item,0,50000,10001));
        assertEquals(0,AlchemyPolicy.quantity(item,100,0,10001));
    }
    @Test void selectHighestUnitProfitNotHighestBatchProfit() {
        AlchemyPolicy.Item rune=new AlchemyPolicy.Item("Rune full helm",1163,21120,70);
        AlchemyPolicy.Item necklace=new AlchemyPolicy.Item("Emerald necklace",1658,855,18000);
        Map<Integer,Integer> prices=new HashMap<>();prices.put(1163,20400);prices.put(1658,535);
        assertTrue(necklace.havbld(535)>rune.havbld(20400));
        AlchemyPolicy.Purchase choice=AlchemyPolicy.select(Arrays.asList(necklace,rune),prices::get,id->true,20000000,100);
        assertSame(rune,choice.item);assertEquals(620,choice.profitPerItem);
    }
    @Test void skipUnaffordableAndUnavailableItemsThenReevaluateCash() {
        AlchemyPolicy.Item rune=new AlchemyPolicy.Item("Rune full helm",1163,21120,70);
        AlchemyPolicy.Item adamant=new AlchemyPolicy.Item("Adamant platebody",1123,9984,125);
        AlchemyPolicy.Item steel=new AlchemyPolicy.Item("Steel platebody",1119,1200,125);
        List<AlchemyPolicy.Item> candidates=Arrays.asList(steel,adamant,rune);
        Map<Integer,Integer> prices=new HashMap<>();prices.put(1163,20400);prices.put(1123,9300);prices.put(1119,800);
        assertSame(rune,AlchemyPolicy.select(candidates,prices::get,id->true,1428000,100).item);
        assertSame(adamant,AlchemyPolicy.select(candidates,prices::get,id->true,1427999,100).item);
        assertSame(steel,AlchemyPolicy.select(candidates,prices::get,id->true,1162499,100).item);
        assertSame(steel,AlchemyPolicy.select(candidates,prices::get,id->true,100000,100).item);
        assertNull(AlchemyPolicy.select(candidates,prices::get,id->true,99999,100));
        assertSame(adamant,AlchemyPolicy.select(candidates,prices::get,id->id!=1163,5000000,100).item);
        assertSame(steel,AlchemyPolicy.select(candidates,prices::get,id->id==1119,5000000,100).item);
        assertNull(AlchemyPolicy.select(candidates,prices::get,id->false,5000000,100));
        assertNull(AlchemyPolicy.select(candidates,id->0,id->true,5000000,100));
        assertNull(AlchemyPolicy.select(candidates,prices::get,id->true,5000000,1000));
        // After spending on the best batch, choose again against the reduced cash stack.
        long cash=1600000;
        AlchemyPolicy.Purchase first=AlchemyPolicy.select(candidates,prices::get,id->true,cash,100);
        cash-=first.item.buyLimitLivePrice(first.price);
        assertSame(steel,AlchemyPolicy.select(candidates,prices::get,id->id!=first.item.id,cash,100).item);
    }
    @Test void collectionDestinationUsesStrictFourSlotThreshold() {
        for(int free=0;free<=28;free++)assertEquals(free<=3,AlchemyPolicy.collectToBank(free));
    }
    @Test void stockedInventoryAlchsBetweenServiceWindows() {
        assertFalse(AlchemyPolicy.serviceNeeded(999,1000,true,true,false));
        assertTrue(AlchemyPolicy.serviceNeeded(1000,1000,true,true,false));
        assertTrue(AlchemyPolicy.serviceNeeded(999,1000,false,true,false));
        assertTrue(AlchemyPolicy.serviceNeeded(999,1000,true,false,false));
        assertFalse(AlchemyPolicy.serviceNeeded(1001,1000,true,true,true));
        assertFalse(AlchemyPolicy.serviceNeeded(1001,1000,false,false,true));
    }
    @Test void selectionJitterStaysWithinTopThreeAndCanChooseEach() {
        List<AlchemyPolicy.Item> pool=new ArrayList<>();Map<Integer,Long> quantities=new HashMap<>();
        for(int i=1;i<=5;i++){pool.add(new AlchemyPolicy.Item("Item "+i,i,i*100,70));quantities.put(i,(long)i);}
        Set<Integer> small=new HashSet<>(),valuable=new HashSet<>();Random rng=new Random(73);
        for(int n=0;n<200;n++) {
            small.add(AlchemyPolicy.selectAlch(pool,quantities,true,rng).id);
            valuable.add(AlchemyPolicy.selectAlch(pool,quantities,false,rng).id);
        }
        assertEquals(new HashSet<>(Arrays.asList(1,2,3)),small);
        assertEquals(new HashSet<>(Arrays.asList(3,4,5)),valuable);
        quantities.clear();assertNull(AlchemyPolicy.selectAlch(pool,quantities,true,rng));
        quantities.put(2,1L);assertEquals(2,AlchemyPolicy.selectAlch(pool,quantities,false,rng).id);
        quantities.put(1,0L);assertEquals(2,AlchemyPolicy.selectAlch(pool,quantities,true,rng).id);
    }
    @Test void overlapUsesActualGeometryAndRejectsMissingOrTouchingBounds() {
        java.awt.Rectangle spell=new java.awt.Rectangle(100,100,30,30);
        List<java.awt.Rectangle> slots=Arrays.asList(null,new java.awt.Rectangle(70,100,30,30),
            new java.awt.Rectangle(110,110,30,30),new java.awt.Rectangle(105,105,25,25));
        assertEquals(3,AlchemyPolicy.overlapSlot(spell,slots));
        assertEquals(-1,AlchemyPolicy.overlapSlot(null,slots));
        assertEquals(-1,AlchemyPolicy.overlapSlot(spell,slots.subList(0,2)));
        assertEquals(-1,AlchemyPolicy.overlapSlot(spell,Collections.singletonList(new java.awt.Rectangle(129,100,30,30))));
    }
    @Test void countersAreIndependentAndResetForNewRun() {
        RunCounters counters=new RunCounters();
        counters.increment(RunCounters.Action.BANK_OPENS);
        counters.increment(RunCounters.Action.ALCHS);counters.increment(RunCounters.Action.ALCHS);
        assertEquals(1,counters.get(RunCounters.Action.BANK_OPENS));
        assertEquals(2,counters.get(RunCounters.Action.ALCHS));
        assertEquals(0,counters.get(RunCounters.Action.GE_OPENS));
        assertEquals(0,counters.get(RunCounters.Action.COLLECTIONS));
        assertEquals(0,counters.get(RunCounters.Action.BUY_OFFERS));
        assertTrue(counters.summary().contains("ALCHS=2"));
        assertTrue(counters.panel().contains("\nGE_OPENS=0"));
        counters.reset();for(RunCounters.Action action:RunCounters.Action.values())assertEquals(0,counters.get(action));
    }
    @Test void onlyClosedToOpenTransitionsCount() {
        assertTrue(RunCounters.opened(false,true));
        assertFalse(RunCounters.opened(true,true));
        assertFalse(RunCounters.opened(false,false));
        assertFalse(RunCounters.opened(true,false));
    }
    @Test void alchConfirmationRequiresConsumptionAndMagicExperience() {
        assertTrue(RunCounters.confirmedAlch(10,9,1000,1065));
        assertTrue(RunCounters.confirmedAlch(1,0,1000,1065));
        assertFalse(RunCounters.confirmedAlch(10,10,1000,1065));
        assertFalse(RunCounters.confirmedAlch(10,9,1000,1000));
        assertFalse(RunCounters.confirmedAlch(10,0,1000,1065));
        assertFalse(RunCounters.confirmedAlch(0,0,1000,1065));
    }
    @Test void selectionSurvivesDelayedInventoryAndPointerJitter() {
        java.awt.Rectangle slot=new java.awt.Rectangle(100,100,32,32);
        java.awt.Point planned=new java.awt.Point(115,115);
        assertEquals(AlchTargeting.Decision.WAIT,AlchTargeting.decide(false,null,1127,-1,planned,planned,600));
        assertEquals(AlchTargeting.Decision.WAIT,AlchTargeting.decide(true,null,1127,1127,planned,planned,1200));
        assertEquals(AlchTargeting.Decision.CLICK_ITEM,AlchTargeting.decide(true,slot,1127,1127,planned,new java.awt.Point(116,114),1800));
        assertEquals(AlchTargeting.Decision.ALIGN_POINTER,AlchTargeting.decide(true,slot,1127,1127,planned,new java.awt.Point(0,0),1800));
        assertEquals(AlchTargeting.Decision.CLICK_ITEM,AlchTargeting.decide(true,slot,1127,1127,planned,planned,2400));
    }
    @Test void targetingNeverClicksWrongItemOrMissingSelection() {
        java.awt.Rectangle slot=new java.awt.Rectangle(100,100,32,32);
        java.awt.Point planned=new java.awt.Point(115,115);
        assertEquals(AlchTargeting.Decision.WAIT,AlchTargeting.decide(true,slot,1127,995,planned,planned,600));
        assertEquals(AlchTargeting.Decision.WAIT,AlchTargeting.decide(true,slot,1127,995,planned,planned,3001));
        assertEquals(AlchTargeting.Decision.RECOVER,AlchTargeting.decide(true,slot,1127,995,planned,planned,6001));
        assertEquals(AlchTargeting.Decision.RECOVER,AlchTargeting.decide(false,slot,1127,1127,planned,planned,6001));
        assertEquals(AlchTargeting.Decision.RECOVER,AlchTargeting.decide(true,null,1127,1127,planned,planned,6001));
    }
    @Test void productionCastLoopDoesNotForceTabsOrRestartSetupOnTimeout() throws Exception {
        String source=java.nio.file.Files.readString(java.nio.file.Paths.get("src/main/java/net/runelite/client/plugins/microbot/highlevelalchemy/OverlapAlcher.java"));
        String selected=source.substring(source.indexOf("if(phase==Phase.SELECTED)"),source.indexOf("if(now<nextCast)"));
        assertFalse(selected.contains("Rs2Tab."));assertFalse(selected.contains("cancel(client)"));
        String loop=source.substring(source.indexOf("if(selected(client)){phase=Phase.SELECTED"));
        assertFalse(loop.contains("Rs2Tab."));assertFalse(loop.contains("Rs2Magic.canCast("));
        assertTrue(source.contains("phase=Phase.READ_SPELL"));
        assertTrue(source.contains("phase=Phase.READ_INVENTORY"));
    }
    @Test void reconstructEmptyOverlapSlotFromReportedClientLayout() {
        List<java.awt.Rectangle> raw=new ArrayList<>();
        for(int i=0;i<28;i++)raw.add(new java.awt.Rectangle(563+(i%4)*42,213+(i/4)*36,36,32));
        for(int i:new int[]{12,14,24,25,26,27})raw.set(i,new java.awt.Rectangle(-1,-1,36,32));
        List<java.awt.Rectangle> repaired=InventoryGeometry.complete(raw);
        assertEquals(new java.awt.Rectangle(563,321,36,32),repaired.get(12));
        assertEquals(12,AlchemyPolicy.overlapSlot(new java.awt.Rectangle(578,325,24,24),repaired));
        assertEquals(new java.awt.Rectangle(689,429,36,32),repaired.get(27));
        assertEquals(-1,raw.get(12).x);
    }
    @Test void refuseUnprovenGridAndSupportShiftedResizedLayouts() {
        List<java.awt.Rectangle> raw=new ArrayList<>(Collections.nCopies(28,null));
        raw.set(0,new java.awt.Rectangle(100,200,36,32));
        assertNull(InventoryGeometry.complete(raw).get(12));
        raw.set(1,new java.awt.Rectangle(146,200,36,32));raw.set(4,new java.awt.Rectangle(100,240,36,32));
        assertEquals(new java.awt.Rectangle(100,320,36,32),InventoryGeometry.complete(raw).get(12));
        raw.set(5,new java.awt.Rectangle(147,240,36,32));
        assertNull(InventoryGeometry.complete(raw).get(12));
        assertNull(InventoryGeometry.complete(Collections.nCopies(28,new java.awt.Rectangle(-1,-1,36,32))).get(12));
    }
    @Test void alchConfirmationMeasuresNotedStackQuantityRatherThanOccupiedSlots() {
        net.runelite.api.Item[] before={new net.runelite.api.Item(1663,18000),new net.runelite.api.Item(561,4800)};
        net.runelite.api.Item[] after={new net.runelite.api.Item(1663,17999),new net.runelite.api.Item(561,4799)};
        assertEquals(before.length,after.length);
        assertEquals(18000,RunCounters.itemQuantity(before,1663));
        assertEquals(17999,RunCounters.itemQuantity(after,1663));
        assertTrue(RunCounters.confirmedAlch(RunCounters.itemQuantity(before,1663),RunCounters.itemQuantity(after,1663),1000,1065));
        assertFalse(RunCounters.confirmedAlch(RunCounters.itemQuantity(before,1663),RunCounters.itemQuantity(before,1663),1000,1065));
        assertEquals(0,RunCounters.itemQuantity(null,1663));
        assertEquals(2,RunCounters.itemQuantity(new net.runelite.api.Item[]{new net.runelite.api.Item(1662,1),new net.runelite.api.Item(1662,1),null},1662));
    }
    @Test void profitUsesActualFillCostWithoutCountingRepeatedObservations() {
        AlchemyProfit ledger=new AlchemyProfit();
        ledger.observe(0,1127,70,38000,2,75000,true,false);
        ledger.observe(0,1127,70,38000,2,75000,true,false);
        assertEquals("1500",AlchemyProfit.gp(ledger.alch(1127,39000)));
        assertEquals("1500",AlchemyProfit.gp(ledger.alch(1127,39000)));
        assertNull(ledger.alch(1127,39000));
        assertEquals("3000",AlchemyProfit.gp(ledger.total()));assertEquals(1,ledger.unknown());
        ledger.observe(0,1127,70,38000,3,113000,true,false);
        assertEquals("1000",AlchemyProfit.gp(ledger.alch(1127,39000)));
    }
    @Test void profitPreservesUnknownCostLossesAndRunReset() {
        AlchemyProfit ledger=new AlchemyProfit();ledger.seed(1127,1);
        ledger.observe(0,1127,70,40000,1,40000,true,false);
        assertNull(ledger.alch(1127,39000));
        assertEquals("-1000",AlchemyProfit.gp(ledger.alch(1127,39000)));
        ledger.reset();assertEquals("0",AlchemyProfit.gp(ledger.total()));assertEquals(0,ledger.unknown());
        ledger.observe(0,1127,70,38000,10,380000,true,true);
        assertNull(ledger.alch(1127,39000));
    }
    @Test void consumedUnnotedItemRequiresRepositioningEvenWhenAnotherRemains() {
        net.runelite.api.Item[] inv={new net.runelite.api.Item(1662,1),new net.runelite.api.Item(-1,0),new net.runelite.api.Item(1663,50)};
        assertEquals(1,RunCounters.itemQuantity(inv,1662));
        assertFalse(RunCounters.itemInSlot(inv,1,1662));
        assertTrue(RunCounters.itemInSlot(inv,0,1662));
        assertTrue(RunCounters.itemInSlot(inv,2,1663));
        assertFalse(RunCounters.itemInSlot(inv,2,1662));
        assertFalse(RunCounters.itemInSlot(null,0,1662));
        assertFalse(RunCounters.itemInSlot(inv,28,1662));
    }
    @Test void purchaseCostsAndOfferProgressSurviveRestart() {
        AlchemyProfit original=new AlchemyProfit();
        original.observe(0,1127,70,38000,2,75000,true,false);
        assertEquals("1500",AlchemyProfit.gp(original.alch(1127,39000,40000)));
        AlchemyProfit restored=new AlchemyProfit();restored.restore(original.serialize());
        restored.observe(0,1127,70,38000,2,75000,true,false);
        assertEquals("1500",AlchemyProfit.gp(restored.alch(1127,39000,40000)));
        assertFalse(restored.hasKnownCost(1127));
        restored.observe(0,1127,70,38000,3,113000,true,false);
        assertEquals("1000",AlchemyProfit.gp(restored.alch(1127,39000,40000)));
        assertEquals("2500",AlchemyProfit.gp(restored.total()));
    }
    @Test void untrackedCostsUseLivePriceIncludingAfterRestart() {
        AlchemyProfit original=new AlchemyProfit();original.seed(1127,2);
        AlchemyProfit restored=new AlchemyProfit();restored.restore(original.serialize());
        assertEquals("800",AlchemyProfit.gp(restored.alch(1127,39000,38200)));
        assertEquals("-100",AlchemyProfit.gp(restored.alch(1127,39000,39100)));
        assertEquals("500",AlchemyProfit.gp(restored.alch(1127,39000,38500)));
        assertEquals("1200",AlchemyProfit.gp(restored.total()));assertEquals(0,restored.unknown());
    }
    @Test void membershipAndCapacity() {
        assertEquals(3,AlchemyPolicy.geSlots(0));assertEquals(8,AlchemyPolicy.geSlots(1));
        assertThrows(IllegalArgumentException.class,()->AlchemyPolicy.geSlots(-1));
        assertEquals(4*AlchemyPolicy.MAX_CASTS_PER_HR,AlchemyPolicy.MAX_CASTS_PER_BUY_LIMIT);
    }
    @Test void membershipWorldActionsRequirePositiveDays() {
        for (int days : new int[]{-1, 0, 1, 30}) {
            assertFalse(AlchemyPolicy.includesMembersItems(days, false));
            assertEquals(days > 0, AlchemyPolicy.includesMembersItems(days, true));
            assertEquals(days > 0, AlchemyPolicy.needsMembersWorld(days, false));
            assertFalse(AlchemyPolicy.needsMembersWorld(days, true));
        }
    }
    @Test void membersCatalogueSupportsFullLimitBuyingAndFreshProfitableCasts() {
        String mapping = "["
            + "{\"id\":1399,\"name\":\"Earth battlestaff\",\"members\":true,\"highalch\":9300,\"limit\":18000},"
            + "{\"id\":1399,\"name\":\"Duplicate\",\"members\":true,\"highalch\":1,\"limit\":1},"
            + "{\"id\":1127,\"name\":\"Rune platebody\",\"members\":false,\"highalch\":39000,\"limit\":70},"
            + "{\"id\":2,\"members\":true,\"highalch\":0,\"limit\":10},"
            + "{\"id\":3,\"members\":true,\"highalch\":10},"
            + "{\"id\":4,\"members\":true,\"highalch\":10,\"limit\":null},"
            + "{\"id\":561,\"members\":true,\"highalch\":10,\"limit\":1000},"
            + "{\"id\":563,\"members\":true,\"highalch\":10,\"limit\":1000}]";
        Map<Integer, AlchemyPolicy.Item> catalogue = LivePrices.parseMembersItems(new JsonParser().parse(mapping).getAsJsonArray());
        assertEquals(Set.of(1399), catalogue.keySet());
        AlchemyPolicy.Item staff = catalogue.get(1399);
        assertEquals(18000, staff.buyLimit);
        assertTrue(staff.profitableAt(9000, 200));
        assertFalse(staff.profitableAt(9100, 200));
        assertFalse(staff.profitableAt(0, 200));
        assertFalse(staff.profitableAt(9000, 0));
        long batchCost = staff.buyLimitLivePrice(9000);
        assertNull(AlchemyPolicy.select(catalogue.values(), id -> 9000, id -> true, batchCost - 1, 200));
        assertSame(staff, AlchemyPolicy.select(catalogue.values(), id -> 9000, id -> true, batchCost, 200).item);
    }
    @Test void exactAllowlistNoVariantsOrDuplicates() {
        List<AlchemyPolicy.Item> items=AlchemyPolicy.load();Set<Integer> ids=new HashSet<>();
        for(AlchemyPolicy.Item i:items)assertTrue(ids.add(i.id));
        assertEquals(133,items.size());assertTrue(ids.containsAll(Arrays.asList(1127,11079,2914,2904)));
        assertFalse(ids.contains(563));assertFalse(ids.contains(561));assertFalse(ids.contains(4151));
        assertEquals(70,items.stream().filter(i->i.id==1127).findFirst().get().buyLimit);
        Map<Integer,String> names=new HashMap<>();
        for(AlchemyPolicy.Item i:items)names.put(i.id,i.name);
        assertEquals("Adamant dagger",names.get(1211));
        AlchemyPolicy.Item dagger=items.stream().filter(i->i.id==1211).findFirst().orElseThrow();
        assertEquals(480,dagger.highAlch);assertEquals(125,dagger.buyLimit);
        assertEquals("Adamant shortsword",names.get(1287));
        assertEquals("Mithril shortsword",names.get(1285));
        assertEquals("Rune shortsword",names.get(1289));
        assertEquals("Steel shortsword",names.get(1281));
        assertFalse(items.stream().anyMatch(i->Arrays.asList(
                "Adamant sword","Mithril sword","Rune sword","Steel sword").contains(i.name)));
    }
    @Test void missingPricesNeverBecomeFreeItems() {
        Map<Integer,LivePrices.Quote> result=LivePrices.parse(new JsonParser().parse("{\"data\":{\"1\":{\"high\":null,\"highTime\":1},\"2\":{\"high\":200,\"highTime\":100},\"3\":{\"high\":0,\"highTime\":100}}}").getAsJsonObject());
        assertEquals(Collections.singleton(2),result.keySet());assertEquals(200,result.get(2).price);
    }
}
