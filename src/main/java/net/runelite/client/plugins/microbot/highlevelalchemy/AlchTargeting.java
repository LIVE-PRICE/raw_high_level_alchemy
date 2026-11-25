package net.runelite.client.plugins.microbot.highlevelalchemy;

import java.awt.Point;
import java.awt.Rectangle;

/** Selection stays active through delayed tab rendering and pointer correction. */
final class AlchTargeting {
    enum Decision { WAIT, RECOVER, ALIGN_POINTER, CLICK_ITEM }
    static Decision decide(boolean selected,Rectangle bounds,int expectedId,int actualId,
                           Point planned,Point actual,long elapsed) {
        boolean valid=selected && bounds!=null && !bounds.isEmpty() && planned!=null
            && bounds.contains(planned) && expectedId==actualId;
        if(!valid)return elapsed>6000?Decision.RECOVER:Decision.WAIT;
        if(actual!=null&&bounds.contains(actual))return Decision.CLICK_ITEM;
        return elapsed>6000?Decision.RECOVER:Decision.ALIGN_POINTER;
    }
    private AlchTargeting(){}
}
