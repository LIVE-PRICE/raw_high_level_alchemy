package net.runelite.client.plugins.microbot.highlevelalchemy;

import java.text.NumberFormat;
import java.util.Locale;

final class PurchaseComparison {
    static String format(String itemName, long quantity, long spent, long submittedPrice, long highAlch) {
        if (quantity <= 0 || highAlch <= 0) return "";
        long boughtTotal = spent > 0 ? spent : Math.multiplyExact(quantity, submittedPrice);
        long boughtEach = boughtTotal / quantity;
        long highAlchTotal = Math.multiplyExact(quantity, highAlch);
        return "Last Purchase: " + number(quantity) + " × " + itemName
            + "\nPrice Bought: " + number(boughtEach) + " | " + number(boughtTotal)
            + "\nHigh Alch Value: " + number(highAlch) + " | " + number(highAlchTotal);
    }

    private static String number(long value) {
        return NumberFormat.getIntegerInstance(Locale.US).format(value);
    }

    private PurchaseComparison() {}
}
