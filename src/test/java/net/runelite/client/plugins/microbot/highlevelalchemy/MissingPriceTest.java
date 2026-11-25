package net.runelite.client.plugins.microbot.highlevelalchemy;

import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MissingPriceTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    @Test void ownedItemsUseRecordedCostsWhileUnknownAndUnprofitableItemsAreSkipped() {
        AlchemyPolicy.Item item=new AlchemyPolicy.Item("Owned item",123,1000,100);
        assertTrue(AlchemyPolicy.profitableOwned(item,new BigDecimal("700"),0,150));
        assertFalse(AlchemyPolicy.profitableOwned(item,new BigDecimal("900"),0,150));
        assertTrue(AlchemyPolicy.profitableOwned(item,null,700,150));
        assertFalse(AlchemyPolicy.profitableOwned(item,null,0,150));
        assertFalse(AlchemyPolicy.profitableOwned(item,null,700,0));
        assertFalse(item.profitableAt(0,150));
    }

    @Test void cachedOwnedPricesNeverBecomeFreshPurchaseQuotes() throws Exception {
        LivePrices prices=new LivePrices();
        Field field=LivePrices.class.getDeclaredField("lastKnown");field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<Integer,LivePrices.Quote> known=(Map<Integer,LivePrices.Quote>)field.get(prices);
        known.put(123,new LivePrices.Quote(700,1));
        assertEquals(700,prices.ownedPrice(123,System.currentTimeMillis()));
        assertEquals(0,prices.price(123,System.currentTimeMillis()));
        assertEquals(0,prices.ownedPrice(456,System.currentTimeMillis()));
        AlchemyPolicy.Item item=new AlchemyPolicy.Item("Owned item",123,1000,100);
        assertNull(AlchemyPolicy.select(List.of(item),id->prices.price(id,System.currentTimeMillis()),id->true,100000,150));
    }

    @Test void inspectingRecordedCostDoesNotConsumeTheLot() {
        AlchemyProfit profit=new AlchemyProfit();profit.seed(123,2);
        assertNull(profit.knownCost(123));
        profit.resolveUnknownCost(123,700);
        assertEquals(new BigDecimal("700"),profit.knownCost(123));
        assertEquals(new BigDecimal("150"),profit.alch(123,1000,0,150));
        assertEquals(new BigDecimal("700"),profit.knownCost(123));
        profit.alch(123,1000,0,150);assertNull(profit.knownCost(123));
    }

    @Test void unrelatedUnpricedMembersItemDoesNotOpenGeHistoryOrWaitForACost() throws Exception {
        HighLevelAlchemyPlugin plugin=new HighLevelAlchemyPlugin();
        set(plugin,"tradeHistory",new GeTradeHistoryDatabase(temporary));
        AlchemyPolicy.Item junk=new AlchemyPolicy.Item("Unpriced unrelated item",123,2,100);
        set(plugin,"prices",new LivePrices());
        set(plugin,"addedMembersItems",Map.of(junk.id,junk));
        set(plugin,"ownedNaturePrice",150);
        Class<?> snapshotClass=Class.forName(HighLevelAlchemyPlugin.class.getName()+"$Snapshot");
        Constructor<?> constructor=snapshotClass.getDeclaredConstructor();constructor.setAccessible(true);
        Object snapshot=constructor.newInstance();
        set(snapshot,"candidates",List.of(junk));set(snapshot,"inventory",new HashMap<>(Map.of(junk.id,1L)));
        set(snapshot,"days",6);set(snapshot,"membersWorld",true);
        Method resolve=HighLevelAlchemyPlugin.class.getDeclaredMethod("resolveUnknownCosts",snapshotClass,long.class,boolean.class);
        resolve.setAccessible(true);
        assertEquals(true,resolve.invoke(plugin,snapshot,System.currentTimeMillis(),false));
    }

    private static void set(Object target,String name,Object value) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
}
