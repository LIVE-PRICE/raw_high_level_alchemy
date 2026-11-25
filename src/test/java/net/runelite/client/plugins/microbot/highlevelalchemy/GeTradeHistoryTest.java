package net.runelite.client.plugins.microbot.highlevelalchemy;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeTradeHistoryTest {
    @TempDir Path temporary;

    @Test void parsesWidgetRowsWithImplicitAndExplicitQuantities(){
        Map<Integer,String> text=new TreeMap<>();
        text.put(2,"Bought:");text.put(3,"Iron platebody");text.put(5,"41 coins");
        text.put(8,"Sold:");text.put(9,"Mithril med helm x 125");text.put(11,"612 coins");
        List<GeHistoryParser.Entry> rows=GeHistoryParser.parse(text);
        assertEquals(2,rows.size());
        assertEquals("BUY",rows.get(0).operation);assertEquals("Iron platebody",rows.get(0).itemName);assertEquals(1,rows.get(0).quantity);assertEquals(41,rows.get(0).priceEach);
        assertEquals("SELL",rows.get(1).operation);assertEquals(125,rows.get(1).quantity);assertEquals(612,rows.get(1).priceEach);
    }

    @Test void persistsGlobalAndPerAccountHistoryWithoutRepeatingAnUnchangedPage()throws Exception{
        GeTradeHistoryDatabase database=new GeTradeHistoryDatabase(temporary);
        List<GeHistoryParser.Entry> initial=List.of(new GeHistoryParser.Entry("BUY","Iron platebody",1,41),new GeHistoryParser.Entry("SELL","Coal",100,180));
        assertEquals(2,database.recordHistory(10,initial,name->name.equals("Iron platebody")?1115:453));
        assertEquals(0,database.recordHistory(10,initial,name->-1));
        List<GeHistoryParser.Entry> updated=List.of(new GeHistoryParser.Entry("BUY","Nature rune",50,92),initial.get(0),initial.get(1));
        assertEquals(1,database.recordHistory(10,updated,name->name.equals("Nature rune")?561:-1));
        assertEquals(3,Files.readAllLines(database.globalFile()).size());
        assertEquals(3,Files.readAllLines(database.accountFile(10)).size());
        assertEquals(41,database.latestBuyPrice(10,1115,"Iron platebody"));
        assertEquals(92,database.latestBuyPrice(10,561,"Nature rune"));
        assertNull(database.latestBuyPrice(11,1115,"Iron platebody"));
    }

    @Test void unknownItemIdsStillResolveRecordedCostsByName() throws Exception {
        GeTradeHistoryDatabase database = new GeTradeHistoryDatabase(temporary);
        database.recordHistory(10, List.of(new GeHistoryParser.Entry("BUY", "Unknown item", 5, 123)), name -> -1);
        assertEquals(123, database.latestBuyPrice(10, 99999, "Unknown item"));
    }

    @Test void completedOfferIsRecordedOnceUntilItsSlotIsCleared()throws Exception{
        GeTradeHistoryDatabase database=new GeTradeHistoryDatabase(temporary);
        GeTradeHistoryDatabase.OfferRecord buy=new GeTradeHistoryDatabase.OfferRecord(2,1115,"Iron platebody","BUY",125,41,false);
        assertEquals(1,database.recordOffers(99,List.of(buy)));
        assertEquals(0,database.recordOffers(99,List.of(buy)));
        assertEquals(0,database.recordOffers(99,List.of(new GeTradeHistoryDatabase.OfferRecord(2,0,"","",0,0,true))));
        assertEquals(1,database.recordOffers(99,List.of(buy)));
        assertEquals(2,Files.readAllLines(database.accountFile(99)).size());
    }

    @Test void natureRuneCostIsIncludedInPerCastAndOverallProfit(){
        AlchemyProfit profit=new AlchemyProfit();profit.seed(1115,2);assertTrue(profit.resolveUnknownCost(1115,41));
        assertEquals("195",AlchemyProfit.gp(profit.alch(1115,336,0,100)));
        assertEquals("185",AlchemyProfit.gp(profit.alch(1115,336,0,110)));
        assertEquals("380",AlchemyProfit.gp(profit.total()));
    }
}
