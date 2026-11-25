package net.runelite.client.plugins.microbot.highlevelalchemy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AlchemyFailureGuardTest {
    @Test void toleratesSixRecoveriesAndStopsTheSeventhWithinTwoMinutes() {
        AlchemyFailureGuard guard=new AlchemyFailureGuard();
        for(int failure=1;failure<=AlchemyFailureGuard.TOLERATED_FAILURES;failure++){
            AlchemyFailureGuard.Decision decision=guard.failure(failure*1_000L);
            assertEquals(failure,decision.failures);
            assertFalse(decision.stop);
        }
        AlchemyFailureGuard.Decision failSafe=guard.failure(7_000L);
        assertEquals(7,failSafe.failures);
        assertTrue(failSafe.stop);
        assertEquals(10_000L,failSafe.backoffMillis);
    }

    @Test void successfulAlchClearsTheRecoveryStreak() {
        AlchemyFailureGuard guard=new AlchemyFailureGuard();
        guard.failure(1_000L);
        guard.failure(2_000L);
        guard.success();
        AlchemyFailureGuard.Decision next=guard.failure(3_000L);
        assertEquals(1,next.failures);
        assertEquals(1_500L,next.backoffMillis);
        assertFalse(next.stop);
    }

    @Test void oldFailuresExpireOutsideTheRollingWindow() {
        AlchemyFailureGuard guard=new AlchemyFailureGuard();
        guard.failure(0L);
        AlchemyFailureGuard.Decision next=guard.failure(AlchemyFailureGuard.WINDOW_MS+1);
        assertEquals(1,next.failures);
        assertFalse(next.stop);
    }
}
