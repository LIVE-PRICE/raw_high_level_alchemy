package net.runelite.client.plugins.microbot.highlevelalchemy;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.*;
import java.awt.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.List;

final class AlchemyProfitOverlay extends Overlay {
    private static final class Drop {
        final BigDecimal profit;final long created=System.currentTimeMillis();int widget=-1;
        Drop(BigDecimal profit){this.profit=profit;}
    }
    private final Client client;private final AlchemyProfit ledger;
    private final List<Drop> drops=new ArrayList<>();
    private volatile boolean shown;
    AlchemyProfitOverlay(Client client,AlchemyProfit ledger){
        this.client=client;this.ledger=ledger;setPosition(OverlayPosition.DYNAMIC);setLayer(OverlayLayer.ABOVE_WIDGETS);
    }
    synchronized void reset(){drops.clear();shown=true;}
    synchronized void add(BigDecimal amount){drops.add(new Drop(amount));if(drops.size()>7)drops.remove(0);}
    private Rectangle magicDrop(Widget parent){
        if(parent==null||parent.isHidden()||parent.getChildren()==null)return null;
        Rectangle text=null;boolean magic=false;
        for(Widget child:parent.getChildren())if(child!=null){
            if(child.getSpriteId()==SpriteID.Staticons.MAGIC)magic=true;
            if(child.getText()!=null&&child.getText().replaceAll("<[^>]*>","").trim().matches("\\+?65"))text=child.getBounds();
        }
        return magic&&text!=null&&!text.isEmpty()?text:null;
    }
    private void text(Graphics2D g,String value,int x,int y,Color color){
        g.setColor(Color.BLACK);g.drawString(value,x+1,y+1);g.setColor(color);g.drawString(value,x,y);
    }
    @Override public synchronized Dimension render(Graphics2D g){
        if(!shown)return null;
        long now=System.currentTimeMillis();drops.removeIf(d->now-d.created>3500);
        Map<Integer,Rectangle> visible=new HashMap<>();
        for(int id=InterfaceID.XpDrops.DROPS_0;id<=InterfaceID.XpDrops.DROPS_6;id++){
            Rectangle r=magicDrop(client.getWidget(id));if(r!=null)visible.put(id,r);
        }
        Set<Integer> assigned=new HashSet<>();for(Drop d:drops)if(d.widget!=-1)assigned.add(d.widget);
        for(Drop d:drops){
            if(d.widget<0){
                int best=-1,y=-1;for(Map.Entry<Integer,Rectangle> e:visible.entrySet())
                    if(!assigned.contains(e.getKey())&&e.getValue().y>y){best=e.getKey();y=e.getValue().y;}
                if(best>=0){d.widget=best;assigned.add(best);}
            }
            Rectangle r=visible.get(d.widget);if(r==null)continue;
            String label=d.profit==null?"cost unknown":(d.profit.signum()>=0?"+":"")+AlchemyProfit.gp(d.profit)+" gp";
            int x=r.x+r.width+8,w=g.getFontMetrics().stringWidth(label);
            if(x+w>client.getCanvasWidth()-4)x=Math.max(4,r.x-w-8);
            text(g,label,x,r.y+g.getFontMetrics().getAscent(),d.profit==null?Color.LIGHT_GRAY:d.profit.signum()<0?Color.RED:Color.GREEN);
        }
        Widget chat=client.getWidget(InterfaceID.Chatbox.CHATAREA);
        if(chat!=null&&!chat.isHidden()){
            Rectangle r=chat.getBounds();String total="Alch profit: "+AlchemyProfit.gp(ledger.total())+" gp";
            String missing=ledger.unknown()>0?ledger.unknown()+" alchs: cost unknown":"Includes item + nature rune costs";
            int width=Math.max(g.getFontMetrics().stringWidth(total),g.getFontMetrics().stringWidth(missing))+12;
            int x=Math.max(r.x,r.x+r.width-width-6),y=r.y+6;
            g.setColor(new Color(0,0,0,170));g.fillRoundRect(x,y,width,38,8,8);
            text(g,total,x+6,y+15,Color.YELLOW);text(g,missing,x+6,y+30,Color.LIGHT_GRAY);
        }
        return null;
    }
}
