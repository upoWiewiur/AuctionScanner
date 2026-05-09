package org.example.marketanalyzer.data;

import java.util.ArrayList;
import java.util.List;

public class MarketItem {
    private String itemName;
    private List<PricePoint> history;

    /** No-arg constructor required by Gson for deserialization. */
    public MarketItem() {
        this.history = new ArrayList<>();
    }

    public MarketItem(String itemName) {
        this.itemName = itemName;
        this.history = new ArrayList<>();
    }

    public String getItemName() {
        return itemName;
    }

    public List<PricePoint> getHistory() {
        return history;
    }

    public void addPrice(double price, int volume) {
        this.history.add(new PricePoint(System.currentTimeMillis(), price, volume));
    }

    public double getLowestRecentPrice(long timeWindowMs) {
        long cutoff = System.currentTimeMillis() - timeWindowMs;
        double lowest = Double.MAX_VALUE;
        boolean found = false;
        
        for (PricePoint pt : history) {
            if (pt.getTimestamp() >= cutoff) {
                if (pt.getPrice() < lowest) {
                    lowest = pt.getPrice();
                    found = true;
                }
            }
        }
        
        return found ? lowest : 0.0;
    }

    public double getAverageRecentPrice(long timeWindowMs) {
        long cutoff = System.currentTimeMillis() - timeWindowMs;
        List<Double> recentPrices = new ArrayList<>();
        
        for (PricePoint pt : history) {
            if (pt.getTimestamp() >= cutoff) {
                recentPrices.add(pt.getPrice());
            }
        }
        
        if (recentPrices.isEmpty()) return 0.0;
        if (recentPrices.size() <= 2) {
            double sum = 0;
            for (double p : recentPrices) sum += p;
            return sum / recentPrices.size();
        }

        recentPrices.sort(Double::compareTo);

        // Odrzuć 10% skrajnych wyników z góry i dołu (ochrona przed trollami)
        int trimCount = (int) (recentPrices.size() * 0.10);
        
        double sum = 0.0;
        int count = 0;
        for (int i = trimCount; i < recentPrices.size() - trimCount; i++) {
            sum += recentPrices.get(i);
            count++;
        }
        
        return count > 0 ? sum / count : 0.0;
    }

    public void pruneOldData(long maxAgeMs) {
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        history.removeIf(pt -> pt.getTimestamp() < cutoff);
    }
}
