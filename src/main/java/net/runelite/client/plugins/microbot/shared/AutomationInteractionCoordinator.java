package net.runelite.client.plugins.microbot.shared;

/**
 * JVM-wide cooperative lease for plugins which share the game UI and mouse.
 *
 * External plugin JARs may use separate class loaders, so the state deliberately lives in
 * system properties and the mutex is an interned String shared by every class loader.
 */
public final class AutomationInteractionCoordinator {
    public static final String HIGH_LEVEL_ALCHEMY = "HIGH_LEVEL_ALCHEMY";
    public static final String F2P_FLIPPER = "F2P_FLIPPER";
    private static final String PREFIX = "microbot.automation.coordinator.v1.";
    private static final String OWNER = PREFIX + "owner";
    private static final String UNTIL = PREFIX + "until";
    private static final String PRIORITY = PREFIX + "priority";
    private static final String ACTIVE_PREFIX = PREFIX + "active.";
    private static final String SLOT_PREFIX = PREFIX + "slot.";
    private static final Object MUTEX = (PREFIX + "mutex").intern();

    private AutomationInteractionCoordinator() {}

    public static void setAutomationActive(String owner, boolean active) {
        synchronized (MUTEX) {
            if (active) System.setProperty(ACTIVE_PREFIX + owner, "true");
            else System.clearProperty(ACTIVE_PREFIX + owner);
            if (!active) {
                if (owner.equals(System.getProperty(PRIORITY))) System.clearProperty(PRIORITY);
                if (owner.equals(System.getProperty(OWNER))) clearLease();
            }
        }
    }

    public static boolean isAutomationActive(String owner) {
        return "true".equals(System.getProperty(ACTIVE_PREFIX + owner));
    }

    /** Requests the next free lease. Used by the flipper so repeated alch casts cannot starve a GE action. */
    public static void requestPriority(String owner) {
        synchronized (MUTEX) {
            System.setProperty(PRIORITY, owner);
        }
    }

    public static void clearPriority(String owner) {
        synchronized (MUTEX) {
            if (owner.equals(System.getProperty(PRIORITY))) System.clearProperty(PRIORITY);
        }
    }

    public static boolean tryAcquire(String owner, long now, long leaseMillis) {
        synchronized (MUTEX) {
            String current = System.getProperty(OWNER, "");
            long until = longProperty(UNTIL);
            if (!current.isEmpty() && !current.equals(owner) && until > now) return false;
            String priority = System.getProperty(PRIORITY, "");
            if ((current.isEmpty() || until <= now) && !priority.isEmpty() && !priority.equals(owner)) return false;
            System.setProperty(OWNER, owner);
            System.setProperty(UNTIL, Long.toString(safeUntil(now, leaseMillis)));
            return true;
        }
    }

    public static boolean renew(String owner, long now, long leaseMillis) {
        synchronized (MUTEX) {
            if (!owner.equals(System.getProperty(OWNER))) return false;
            System.setProperty(UNTIL, Long.toString(safeUntil(now, leaseMillis)));
            return true;
        }
    }

    public static void release(String owner) {
        synchronized (MUTEX) {
            if (owner.equals(System.getProperty(OWNER))) clearLease();
        }
    }

    public static String owner(long now) {
        synchronized (MUTEX) {
            long until = longProperty(UNTIL);
            if (until <= now) {
                clearLease();
                return "";
            }
            return System.getProperty(OWNER, "");
        }
    }

    public static void claimSlot(String owner, int slot) {
        if (slot < 0) return;
        synchronized (MUTEX) {
            System.setProperty(SLOT_PREFIX + slot, owner);
        }
    }

    public static String slotOwner(int slot) {
        return slot < 0 ? "" : System.getProperty(SLOT_PREFIX + slot, "");
    }

    public static void releaseSlot(String owner, int slot) {
        if (slot < 0) return;
        synchronized (MUTEX) {
            String key = SLOT_PREFIX + slot;
            if (owner.equals(System.getProperty(key))) System.clearProperty(key);
        }
    }

    static void clearForTests() {
        synchronized (MUTEX) {
            clearLease();
            System.clearProperty(PRIORITY);
            System.clearProperty(ACTIVE_PREFIX + HIGH_LEVEL_ALCHEMY);
            System.clearProperty(ACTIVE_PREFIX + F2P_FLIPPER);
            for (int slot = 0; slot < 8; slot++) System.clearProperty(SLOT_PREFIX + slot);
        }
    }

    private static long safeUntil(long now, long leaseMillis) {
        long duration = Math.max(1, leaseMillis);
        return now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration;
    }

    private static long longProperty(String key) {
        try {
            return Long.parseLong(System.getProperty(key, "0"));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static void clearLease() {
        System.clearProperty(OWNER);
        System.clearProperty(UNTIL);
    }
}
