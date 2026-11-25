package net.runelite.client.plugins.microbot.highlevelalchemy;

import java.awt.Rectangle;
import java.util.*;

/** Reconstruct empty slot rectangles from a verified, currently rendered 4-column grid. */
final class InventoryGeometry {
    private static boolean valid(Rectangle r){return r!=null&&r.x>=0&&r.y>=0&&r.width>0&&r.height>0;}
    static List<Rectangle> complete(List<Rectangle> raw) {
        List<Rectangle> result=new ArrayList<>(Collections.nCopies(raw.size(),null));
        int anchor=-1,dx=0,dy=0;
        for(int i=0;i<raw.size();i++)if(valid(raw.get(i))){result.set(i,new Rectangle(raw.get(i)));if(anchor<0)anchor=i;}
        if(anchor<0)return result;
        Rectangle a=raw.get(anchor);
        for(int i=0;i<raw.size();i++)if(valid(raw.get(i))) {
            Rectangle r=raw.get(i);int cols=i%4-anchor%4,rows=i/4-anchor/4;
            if(cols!=0&&(r.x-a.x)%cols==0)dx=(r.x-a.x)/cols;
            if(rows!=0&&(r.y-a.y)%rows==0)dy=(r.y-a.y)/rows;
        }
        if(dx<a.width||dy<a.height)return result;
        int x=a.x-(anchor%4)*dx,y=a.y-(anchor/4)*dy;
        if(x<0||y<0)return result;
        // Never infer from a mixed layout, clipped rectangles or unrelated widget children.
        for(int i=0;i<raw.size();i++)if(valid(raw.get(i))) {
            Rectangle r=raw.get(i);
            if(r.x!=x+(i%4)*dx||r.y!=y+(i/4)*dy||r.width!=a.width||r.height!=a.height)return result;
        }
        for(int i=0;i<result.size();i++)if(result.get(i)==null)
            result.set(i,new Rectangle(x+(i%4)*dx,y+(i/4)*dy,a.width,a.height));
        return result;
    }
    private InventoryGeometry(){}
}
