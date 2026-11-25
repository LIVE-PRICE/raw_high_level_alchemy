package net.runelite.client.plugins.microbot.highlevelalchemy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AlchemyOfferTrackerTest {
    @Test void restoresOnlyMatchingOfferFingerprints() {
        AlchemyOfferTracker tracker=new AlchemyOfferTracker();
        tracker.remember(2,561,1000,130);
        AlchemyOfferTracker restored=new AlchemyOfferTracker();
        restored.restore(tracker.serialize());
        assertTrue(restored.matches(2,561,1000,130));
        assertFalse(restored.matches(2,561,1001,130));
        assertFalse(restored.matches(2,561,1000,131));
        assertFalse(restored.matches(1,561,1000,130));
        restored.forget(2);
        assertEquals("",restored.serialize());
    }

    @Test void progressRestartsSupplyOfferDeadline() {
        AlchemyOfferTracker tracker=new AlchemyOfferTracker();
        assertFalse(tracker.stalled(0,0,0));
        assertFalse(tracker.stalled(0,0,119999));
        assertTrue(tracker.stalled(0,0,120000));
        assertFalse(tracker.stalled(0,10,120001));
        assertFalse(tracker.stalled(0,10,240000));
        assertTrue(tracker.stalled(0,10,240001));
        tracker.forget(0);
        assertFalse(tracker.stalled(0,0,300000));
    }

    @Test void restoringStartsFreshProgressClocksAndIgnoresInvalidRows() {
        AlchemyOfferTracker tracker=new AlchemyOfferTracker();
        tracker.stalled(0,0,0);
        tracker.restore("bad;0:561:100:130;9:561:100:130;1:bad:1:1;");
        assertEquals("0:561:100:130;",tracker.serialize());
        assertFalse(tracker.stalled(0,0,900000));
    }

    @Test void pointerAlignmentCannotRetryForever() {
        java.awt.Rectangle slot=new java.awt.Rectangle(100,100,30,30);
        java.awt.Point planned=new java.awt.Point(115,115);
        assertEquals(AlchTargeting.Decision.ALIGN_POINTER,
            AlchTargeting.decide(true,slot,1127,1127,planned,new java.awt.Point(0,0),6000));
        assertEquals(AlchTargeting.Decision.RECOVER,
            AlchTargeting.decide(true,slot,1127,1127,planned,new java.awt.Point(0,0),6001));
        assertEquals(AlchTargeting.Decision.CLICK_ITEM,
            AlchTargeting.decide(true,slot,1127,1127,planned,planned,6001));
    }
}
