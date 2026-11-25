package net.runelite.client.plugins.microbot.highlevelalchemy;

import java.math.*;
import java.util.*;
import com.google.gson.*;

/** FIFO purchase accounting; pre-existing holdings retain an explicitly unknown cost. */
final class AlchemyProfit {
    static final class Lot { long quantity;BigDecimal cost;Lot(long q,BigDecimal c){quantity=q;cost=c;} }
    private final Map<Integer,Deque<Lot>> lots=new HashMap<>();
    private final Map<Integer,long[]> offers=new HashMap<>();
    private BigDecimal total=BigDecimal.ZERO;
    private long unknown;
    synchronized void reset(){lots.clear();offers.clear();total=BigDecimal.ZERO;unknown=0;}
    synchronized void seed(int id,long quantity){if(quantity>0)lots.computeIfAbsent(id,k->new ArrayDeque<>()).add(new Lot(quantity,null));}
    synchronized void observe(int slot,int id,int requested,long price,int traded,long spent,boolean buy,boolean baseline){
        if(!buy){offers.remove(slot);return;}
        long[] old=offers.get(slot);
        if(old==null||old[0]!=id||old[1]!=requested||old[2]!=price||traded<old[3]||spent<old[4])old=new long[]{id,requested,price,0,0};
        long q=traded-old[3],cost=spent-old[4];
        if(!baseline&&q>0&&cost>0)lots.computeIfAbsent(id,k->new ArrayDeque<>()).add(new Lot(q,BigDecimal.valueOf(cost).divide(BigDecimal.valueOf(q),12,RoundingMode.HALF_UP)));
        offers.put(slot,new long[]{id,requested,price,traded,spent});
    }
    synchronized String serialize(){
        JsonObject root=new JsonObject();JsonArray batches=new JsonArray(), checkpoints=new JsonArray();
        lots.forEach((id,queue)->queue.forEach(lot->{JsonObject b=new JsonObject();b.addProperty("id",id);b.addProperty("quantity",lot.quantity);b.addProperty("cost",lot.cost);batches.add(b);}));
        offers.forEach((slot,values)->{JsonArray row=new JsonArray();row.add(slot);for(long value:values)row.add(value);checkpoints.add(row);});
        root.add("lots",batches);root.add("offers",checkpoints);return root.toString();
    }
    synchronized void restore(String saved){
        reset();if(saved==null||saved.isEmpty())return;
        JsonObject root=new JsonParser().parse(saved).getAsJsonObject();
        for(JsonElement element:root.getAsJsonArray("lots")){
            JsonObject b=element.getAsJsonObject();long q=b.get("quantity").getAsLong();
            if(q>0)lots.computeIfAbsent(b.get("id").getAsInt(),k->new ArrayDeque<>()).add(new Lot(q,b.get("cost").isJsonNull()?null:b.get("cost").getAsBigDecimal()));
        }
        for(JsonElement element:root.getAsJsonArray("offers")){
            JsonArray row=element.getAsJsonArray();long[] values=new long[5];for(int i=0;i<5;i++)values[i]=row.get(i+1).getAsLong();offers.put(row.get(0).getAsInt(),values);
        }
    }
    synchronized BigDecimal knownCost(int id){
        Deque<Lot> queue=lots.get(id);
        return queue==null||queue.isEmpty()?null:queue.peek().cost;
    }
    synchronized boolean hasKnownCost(int id){Deque<Lot> queue=lots.get(id);return queue!=null&&!queue.isEmpty()&&queue.peek().cost!=null;}
    synchronized boolean resolveUnknownCost(int id,long price){
        if(price<=0)return false;Deque<Lot> queue=lots.get(id);if(queue==null)return false;boolean changed=false;
        for(Lot lot:queue)if(lot.cost==null){lot.cost=BigDecimal.valueOf(price);changed=true;}return changed;
    }
    synchronized BigDecimal alch(int id,int highAlch){return alch(id,highAlch,0);}
    synchronized BigDecimal alch(int id,int highAlch,int livePrice){
        return alch(id,highAlch,livePrice,0);
    }
    synchronized BigDecimal alch(int id,int highAlch,int livePrice,int natureRunePrice){
        Deque<Lot> queue=lots.get(id);Lot lot=queue==null?null:queue.peek();
        BigDecimal cost=lot==null?null:lot.cost;
        if(lot!=null&&--lot.quantity==0)queue.remove();
        if(cost==null&&livePrice>0)cost=BigDecimal.valueOf(livePrice);
        if(cost==null){unknown++;return null;}
        BigDecimal profit=BigDecimal.valueOf(highAlch).subtract(cost).subtract(BigDecimal.valueOf(Math.max(0,natureRunePrice)));total=total.add(profit);return profit;
    }
    synchronized BigDecimal total(){return total;}
    synchronized long unknown(){return unknown;}
    static String gp(BigDecimal amount){return amount.setScale(2,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();}
}
