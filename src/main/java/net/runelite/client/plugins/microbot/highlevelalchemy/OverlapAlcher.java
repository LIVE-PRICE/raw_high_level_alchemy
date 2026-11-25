package net.runelite.client.plugins.microbot.highlevelalchemy;

import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.tabs.Rs2Tab;
import net.runelite.client.plugins.skillcalculator.skills.MagicAction;
import java.awt.Rectangle;
import java.awt.Point;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Verified two-click alchemy at a shared spell/item coordinate. No randomized click rectangles. */
final class OverlapAlcher {
    static final class RecoverableFailure extends RuntimeException {
        RecoverableFailure(String message) { super(message); }
    }
    private enum Phase { PREPARE, READ_SPELL, READ_INVENTORY, MOVE, READY, SELECTED, RESULT }
    private Phase phase=Phase.PREPARE;
    private int itemId=-1,slot=-1,width,height;
    private long before,beforeXp;
    private long clickedAt,nextCast,phaseAt;
    private Point point;
    private Rectangle preparedSpell;
    boolean busy(){return phase==Phase.SELECTED||phase==Phase.RESULT;}
    boolean interactionPending(){return phase!=Phase.PREPARE&&phase!=Phase.READY;}
    void reset(){phase=Phase.PREPARE;itemId=-1;point=null;}
    private <T> T read(Supplier<T> action){return Microbot.getClientThread().invoke(action);}
    private Rectangle spellBounds(Client client){return read(()->{
        Widget w=client.getWidget(MagicAction.HIGH_LEVEL_ALCHEMY.getWidgetId());
        return w==null||w.isHidden()?null:new Rectangle(w.getBounds());
    });}
    private List<Rectangle> slots(){return read(()->{
        List<Rectangle> result=new ArrayList<>(Collections.nCopies(28,null));
        Widget w=Rs2Inventory.getInventoryWidget();
        if(w!=null && !w.isHidden() && w.getDynamicChildren()!=null)
            for(Widget child:w.getDynamicChildren())if(child!=null&&child.getIndex()>=0&&child.getIndex()<28)
                result.set(child.getIndex(),new Rectangle(child.getBounds()));
        return InventoryGeometry.complete(result);
    });}
    private long[] observation(Client client){return read(()->{
        net.runelite.api.ItemContainer inventory=client.getItemContainer(net.runelite.api.InventoryID.INVENTORY);
        return new long[]{RunCounters.itemQuantity(inventory==null?null:inventory.getItems(),itemId),
            client.getSkillExperience(net.runelite.api.Skill.MAGIC)};
    });}
    private boolean targetInSlot(Client client){return read(()->{
        net.runelite.api.ItemContainer inv=client.getItemContainer(net.runelite.api.InventoryID.INVENTORY);
        return RunCounters.itemInSlot(inv==null?null:inv.getItems(),slot,itemId);
    });}
    private boolean selected(Client client){return read(()->client.isWidgetSelected()&&client.getSelectedWidget()!=null
        &&client.getSelectedWidget().getId()==MagicAction.HIGH_LEVEL_ALCHEMY.getWidgetId());}
    private void cancel(Client client){Microbot.getClientThread().invoke((Runnable)()->client.setWidgetSelected(false));phase=Phase.PREPARE;}
    void recover(Client client){if(selected(client))cancel(client);reset();}
    private RecoverableFailure recoverable(String message){return new RecoverableFailure(message);}
    String step(Client client,int requestedId,long now,BooleanSupplier active,Runnable onAlch) {
        if(!active.getAsBoolean())return "Stopped";
        if(requestedId!=itemId){itemId=requestedId;phase=Phase.PREPARE;}
        if(phase==Phase.RESULT){
            long[] observed=observation(client);
            if(RunCounters.confirmedAlch(before,observed[0],beforeXp,observed[1])){
                phase=targetInSlot(client)?Phase.READY:Phase.PREPARE;phaseAt=now;onAlch.run();return "Alching";
            }
            if(now-phaseAt>8000)throw recoverable("Alchemy result unconfirmed: item="+itemId+", quantity="+before+" -> "+observed[0]+", Magic XP="+beforeXp+" -> "+observed[1]);
            return "Waiting for alchemy result";
        }
        if(phase==Phase.SELECTED){
            // Selecting High Alchemy opens inventory itself. Running a tab script here can
            // clear that selection; wait for the client to display the target instead.
            List<Rectangle> bounds=slots();
            Rectangle targetBounds=slot>=0&&slot<bounds.size()?bounds.get(slot):null;
            int actualId=read(()->{
                net.runelite.api.ItemContainer inv=client.getItemContainer(net.runelite.api.InventoryID.INVENTORY);
                return inv==null||slot<0||slot>=inv.getItems().length?-1:inv.getItems()[slot].getId();
            });
            AlchTargeting.Decision decision=AlchTargeting.decide(selected(client),targetBounds,itemId,actualId,
                point,Microbot.getMouse().getMousePosition(),now-phaseAt);
            if(!active.getAsBoolean())return "Stopped";
            switch(decision) {
                case WAIT:return "Waiting for selected High Alchemy and inventory target";
                case RECOVER:
                    org.slf4j.LoggerFactory.getLogger(getClass()).info("HIGH_LEVEL_ALCHEMY target timeout: item={}, slot={}, actualItem={}",itemId,slot,actualId);
                    throw recoverable("Selected alchemy target unavailable for 6 seconds (slot "+slot+", expected "+itemId+", actual "+actualId+")");
                case ALIGN_POINTER:
                    // Keep the spell selected while restoring the pointer to the same overlap.
                    Microbot.getMouse().move(point.x,point.y);
                    return "Restoring pointer without deselecting High Alchemy";
                case CLICK_ITEM:break;
                default:throw new IllegalStateException("Unknown targeting decision");
            }
            long[] baseline=observation(client);before=baseline[0];beforeXp=baseline[1];
            if(!active.getAsBoolean())return "Stopped";
            Microbot.getMouse().click();phase=Phase.RESULT;phaseAt=now;nextCast=clickedAt+3000;
            return "Alching at the same mouse position";
        }
        if(now<nextCast)return "Alching";
        if(phase==Phase.PREPARE){
            if(selected(client))cancel(client);
            Rs2Tab.switchToMagicTab();phase=Phase.READ_SPELL;phaseAt=now;
            return "Setup: waiting for spell layout";
        }
        if(phase==Phase.READ_SPELL){
            preparedSpell=spellBounds(client);
            if(preparedSpell==null){
                if(now-phaseAt>8000)throw recoverable("High Alchemy widget not visible during setup");
                return "Setup: waiting for High Alchemy widget";
            }
            Rs2Tab.switchToInventoryTab();phase=Phase.READ_INVENTORY;phaseAt=now;
            return "Setup: waiting for inventory layout";
        }
        if(phase==Phase.READ_INVENTORY){
            List<Rectangle> bounds=slots();
            if(bounds.stream().allMatch(Objects::isNull)){
                if(now-phaseAt>8000)throw recoverable("Inventory slot widgets not available during setup");
                return "Setup: waiting for inventory slots";
            }
            slot=AlchemyPolicy.overlapSlot(preparedSpell,bounds);
            if(slot<0)throw recoverable("High Alchemy has no overlap with inventory slots. Spell bounds="+preparedSpell
                +"; inventory bounds="+bounds+". Adjust spell filters/layout, then Start again.");
            Rectangle shared=preparedSpell.intersection(bounds.get(slot));
            point=new Point(shared.x+shared.width/2,shared.y+shared.height/2);
            width=client.getCanvas().getWidth();height=client.getCanvas().getHeight();
            Rs2ItemModel item=Rs2Inventory.get(itemId);if(item==null)throw recoverable("Selected alch stack disappeared during setup");
            if(!active.getAsBoolean())return "Stopped";
            if(item.getSlot()!=slot){
                Rectangle from=item.getSlot()>=0&&item.getSlot()<bounds.size()?bounds.get(item.getSlot()):null;
                Rectangle to=bounds.get(slot);
                if(from==null||to==null)throw recoverable("Inventory drag bounds unavailable");
                // The upstream moveItemToSlot uses (-1,-1) for empty target widgets.
                // Use the same measured grid rectangles as the overlap calculation.
                Microbot.drag(from,to);
            }
            phase=Phase.MOVE;phaseAt=now;return "Positioning alch stack under spell icon";
        }
        if(phase==Phase.MOVE){
            if(Rs2Inventory.getIdForSlot(slot)!=itemId){
                if(now-phaseAt>8000)throw recoverable("Alch stack did not reach inventory slot "+slot);
                return "Waiting for inventory move";
            }
            // The final explicit tab change belongs to setup only, never to the cast loop.
            Rs2Tab.switchToMagicTab();phase=Phase.READY;phaseAt=now;
            org.slf4j.LoggerFactory.getLogger(getClass()).info("HIGH_LEVEL_ALCHEMY overlap ready: item={}, slot={}, point={}",itemId,slot,point);
            return "Setup complete; waiting for spell widget";
        }
        // The game returns to magic after alching. Never force a tab change or call
        // Rs2Magic.canCast here: that helper itself can switch tabs and click widgets.
        if(selected(client)){phase=Phase.SELECTED;phaseAt=now;return "High Alchemy already selected; waiting for item";}
        Rectangle spell=spellBounds(client);
        if(spell==null){
            if(now-phaseAt>8000)throw recoverable("Magic tab did not return after alchemy without forcing a tab switch");
            return "Waiting for game to return to spellbook";
        }
        if(!spell.contains(point)||client.getCanvas().getWidth()!=width||client.getCanvas().getHeight()!=height)
            throw recoverable("Alchemy overlap layout changed; retrying setup to reposition the stack");
        if(!active.getAsBoolean())return "Stopped";
        if(!point.equals(Microbot.getMouse().getMousePosition()))Microbot.getMouse().move(point.x,point.y);
        if(!active.getAsBoolean())return "Stopped";
        Microbot.getMouse().click();clickedAt=now;phaseAt=now;phase=Phase.SELECTED;
        return "Selecting High Level Alchemy at shared coordinate";
    }
}
