package net.runelite.client.plugins.microbot.highlevelalchemy;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure calculations; gold values deliberately use long arithmetic. */
public final class AlchemyPolicy {
    public static final int MAX_CASTS_PER_HR = 1200;
    public static final int MAX_CASTS_PER_BUY_LIMIT = 4800;
    public static final long BUY_LIMIT_WINDOW_MS = 4 * 60 * 60 * 1000L;
    public static final class Item {
        public final String name;
        public final int id, highAlch, buyLimit;
        Item(String name, int id, int highAlch, int buyLimit) {
            this.name=name; this.id=id; this.highAlch=highAlch; this.buyLimit=buyLimit;
            if(id<=0 || highAlch<=0 || buyLimit<=0) throw new IllegalArgumentException("Invalid item");
        }
        public long buyLimitHighAlchValue(){return (long)highAlch*buyLimit;}
        public long buyLimitLivePrice(int price){return (long)price*buyLimit;}
        public long havbld(int price){return ((long)highAlch-price)*buyLimit;}
        long profitPerCast(int price, int naturePrice) {
            return (long) highAlch - price - naturePrice;
        }
        boolean profitableAt(int price, int naturePrice) {
            return price > 0 && naturePrice > 0 && profitPerCast(price, naturePrice) > 0;
        }
    }
    static boolean profitableOwned(Item item, java.math.BigDecimal recordedCost, int knownPrice, int naturePrice) {
        if(naturePrice<=0)return false;
        java.math.BigDecimal cost=recordedCost;
        if(cost==null && knownPrice>0)cost=java.math.BigDecimal.valueOf(knownPrice);
        return cost!=null && cost.signum()>0 && java.math.BigDecimal.valueOf(item.highAlch)
            .subtract(cost).subtract(java.math.BigDecimal.valueOf(naturePrice)).signum()>0;
    }
    static boolean includesMembersItems(int days, boolean membersWorld) {
        return days > 0 && membersWorld;
    }
    static boolean needsMembersWorld(int days, boolean membersWorld) {
        return days > 0 && !membersWorld;
    }
    public static int geSlots(int membershipDays) {
        if(membershipDays<0) throw new IllegalArgumentException("Membership is unknown");
        return membershipDays==0 ? 3 : 8;
    }
    public static int quantity(Item item,int price,long coins,long ownedHighAlch) {
        if(price<=0 || coins<0) return 0;
        // Alchemy-item offers are all-or-nothing, regardless of owned alch value.
        return coins >= item.buyLimitLivePrice(price) ? item.buyLimit : 0;
    }
    public static final class Purchase {
        public final Item item;
        public final int price;
        public final long profitPerItem;
        Purchase(Item item,int price,int naturePrice) {
            this.item=item;this.price=price;this.profitPerItem=item.profitPerCast(price,naturePrice);
        }
    }
    /** Highest per-cast profit among eligible, fully affordable batches. */
    public static Purchase select(Collection<Item> items, java.util.function.IntUnaryOperator prices,
                                  java.util.function.IntPredicate eligible,long coins,int naturePrice) {
        if(naturePrice<=0 || coins<0)return null;
        Purchase best=null;
        for(Item item:items) {
            int price=prices.applyAsInt(item.id);
            if(price<=0 || item.buyLimitLivePrice(price)>coins)continue;
            Purchase candidate=new Purchase(item,price,naturePrice);
            if(candidate.profitPerItem<=0 || !eligible.test(item.id))continue;
            if(best==null || candidate.profitPerItem>best.profitPerItem
                    || (candidate.profitPerItem==best.profitPerItem
                        && item.buyLimitLivePrice(price)<best.item.buyLimitLivePrice(best.price)))best=candidate;
        }
        return best;
    }
    public static boolean collectToBank(int freeSlots) { return freeSlots<=3; }
    static int withdrawalSlots(int freeSlots) {
        // Withdrawals can start at 25 occupied slots, with two slots left afterward.
        return Math.max(0, freeSlots - 2);
    }
    public static boolean serviceNeeded(long now,long nextService,boolean hasAlchItems,boolean hasRunes,boolean castInFlight) {
        return !castInFlight && (!hasAlchItems || !hasRunes || now>=nextService);
    }
    public static Item selectAlch(Collection<Item> items,Map<Integer,Long> quantities,boolean smallestStack,Random random) {
        List<Item> candidates=new ArrayList<>();
        for(Item item:items)if(quantities.getOrDefault(item.id,0L)>0)candidates.add(item);
        Comparator<Item> order=smallestStack
            ? Comparator.comparingLong(i->quantities.get(i.id))
            : Comparator.comparingInt((Item i)->i.highAlch).reversed();
        candidates.sort(order.thenComparingInt(i->i.id));
        return candidates.isEmpty()?null:candidates.get(random.nextInt(Math.min(3,candidates.size())));
    }
    public static int overlapSlot(java.awt.Rectangle spell,List<java.awt.Rectangle> slots) {
        int best=-1;long area=0;
        if(spell==null)return best;
        for(int i=0;i<slots.size();i++) {
            if(slots.get(i)==null)continue;
            java.awt.Rectangle overlap=spell.intersection(slots.get(i));
            long candidate=overlap.width>2&&overlap.height>2?(long)overlap.width*overlap.height:0;
            if(candidate>area){best=i;area=candidate;}
        }
        return best;
    }
    public static List<Item> load() {
        List<Item> items=new ArrayList<>();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                AlchemyPolicy.class.getResourceAsStream("items.csv")),StandardCharsets.UTF_8))) {
            r.readLine(); String line;
            while((line=r.readLine())!=null) {
                String[] c=line.split(",");
                items.add(new Item(c[0],Integer.parseInt(c[1]),Integer.parseInt(c[2]),Integer.parseInt(c[3])));
            }
        } catch(IOException e){throw new UncheckedIOException(e);}
        return Collections.unmodifiableList(items);
    }
    private AlchemyPolicy(){}
}
