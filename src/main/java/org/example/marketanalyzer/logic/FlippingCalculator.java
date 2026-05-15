package org.example.marketanalyzer.logic;

import org.example.marketanalyzer.data.MarketDataStore;
import org.example.marketanalyzer.data.MarketItem;
import org.example.marketanalyzer.data.TrackedItemsConfig;

/**
 * Calculates net profit from flipping items on the DonutSMP Auction House.
 * Takes into account the server-configured AH tax (default 5%).
 */
public class FlippingCalculator {

    public static final class FlipResult {
        public final double buyPrice;
        public final double sellPrice;
        public final double grossProfit;
        public final double taxAmount;
        public final double netProfit;
        public final double roi; // Return On Investment in %
        public final boolean isProfitable;

        private FlipResult(double buy, double sell, double taxRate) {
            this.buyPrice    = buy;
            this.sellPrice   = sell;
            this.grossProfit = sell - buy;
            this.taxAmount   = sell * taxRate;
            this.netProfit   = grossProfit - taxAmount;
            this.roi         = buy > 0 ? (netProfit / buy) * 100.0 : 0.0;
            this.isProfitable = netProfit > 0;
        }

        @Override
        public String toString() {
            return String.format("Buy: $%.0f | Sell: $%.0f | Net: $%.0f | ROI: %.1f%%",
                buyPrice, sellPrice, netProfit, roi);
        }
    }

    /**
     * Calculate flip result for arbitrary buy/sell prices.
     *
     * @param buyPrice   price you buy the item for
     * @param sellPrice  price you intend to sell for
     * @return           FlipResult with all calculated values
     */
    public static FlipResult calculate(double buyPrice, double sellPrice) {
        double taxRate = TrackedItemsConfig.getAhTaxPercent() / 100.0;
        return new FlipResult(buyPrice, sellPrice, taxRate);
    }

    /**
     * Calculate the optimal sell price for an item based on its market history.
     * Sell at 3% below 7-day average (undercuts competition while staying profitable).
     *
     * @param itemId  registry ID (e.g. "minecraft:carrot")
     * @return        suggested sell price, or -1 if no data
     */
    public static double suggestSellPrice(String itemId) {
        MarketItem item = MarketDataStore.getItem(itemId);
        if (item == null) return -1;
        double avg = item.getAverageRecentPrice(7 * 24 * 3600_000L);
        return avg > 0 ? avg * 0.97 : -1;
    }

    /**
     * Auto flip helper: given the current best price (from scan), calculates
     * expected flip result using the suggested sell price.
     *
     * @param itemId     registry ID
     * @param buyPrice   current lowest market price
     * @return           FlipResult or null if insufficient data
     */
    public static FlipResult autoFlip(String itemId, double buyPrice) {
        double sell = suggestSellPrice(itemId);
        if (sell <= 0) return null;
        return calculate(buyPrice, sell);
    }
}
