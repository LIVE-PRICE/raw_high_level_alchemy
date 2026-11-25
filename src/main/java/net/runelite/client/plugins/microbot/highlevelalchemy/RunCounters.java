package net.runelite.client.plugins.microbot.highlevelalchemy;

/** Per-run diagnostic counters, safely shared by the worker and Swing panel. */
final class RunCounters {
    enum Action { BANK_OPENS, GE_OPENS, COLLECTIONS, BUY_OFFERS, ALCHS }
    private final long[] counts=new long[Action.values().length];
    synchronized void reset(){java.util.Arrays.fill(counts,0);}
    synchronized void increment(Action action){counts[action.ordinal()]++;}
    synchronized long get(Action action){return counts[action.ordinal()];}
    synchronized String summary() {
        java.util.StringJoiner result=new java.util.StringJoiner(" | ");
        for(Action action:Action.values())result.add(action+"="+counts[action.ordinal()]);
        return result.toString();
    }
    synchronized String panel(){return summary().replace(" | ","\n");}
    static boolean opened(boolean before,boolean after){return !before&&after;}
    static long itemQuantity(net.runelite.api.Item[] inventory,int id) {
        long total=0;
        if(inventory!=null)for(net.runelite.api.Item item:inventory)
            if(item!=null&&item.getId()==id&&item.getQuantity()>0)total+=item.getQuantity();
        return total;
    }
    static boolean itemInSlot(net.runelite.api.Item[] inventory,int slot,int id) {
        return inventory!=null && slot>=0 && slot<inventory.length && inventory[slot]!=null
            && inventory[slot].getId()==id && inventory[slot].getQuantity()>0;
    }
    static boolean confirmedAlch(long beforeCount,long afterCount,long beforeXp,long afterXp) {
        return beforeCount>0 && afterCount==beforeCount-1 && (long)afterXp-beforeXp>=65;
    }
}
