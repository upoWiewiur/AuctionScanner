package org.example.marketanalyzer.logic;

import org.example.marketanalyzer.data.MarketDataStore;
import org.example.marketanalyzer.data.MarketItem;
import org.example.marketanalyzer.data.PricePoint;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/**
 * Analyses historical price data to forecast price patterns.
 * Builds a 24-slot hourly heatmap showing when items are cheapest/most expensive.
 */
public class MarketForecaster {

    public static final class HourlyStats {
        public final int hour;          // 0-23
        public double avgPrice;
        public int dataPoints;

        public HourlyStats(int hour) {
            this.hour = hour;
            this.avgPrice = 0;
            this.dataPoints = 0;
        }
    }

    public static final class Forecast {
        public final String itemId;
        public final HourlyStats[] hourly;  // index = hour of day (0-23)
        public final int cheapestHour;
        public final int expensiveHour;
        public final double cheapestAvg;
        public final double expensiveAvg;

        public Forecast(String itemId, HourlyStats[] hourly,
                        int cheapestHour, int expensiveHour,
                        double cheapestAvg, double expensiveAvg) {
            this.itemId = itemId;
            this.hourly = hourly;
            this.cheapestHour = cheapestHour;
            this.expensiveHour = expensiveHour;
            this.cheapestAvg = cheapestAvg;
            this.expensiveAvg = expensiveAvg;
        }

        /** Human-readable summary for in-game chat or GUI. */
        public String getSummary() {
            return String.format("Najtaniej o %02d:00 (avg $%.0f) | Najdrożej o %02d:00 (avg $%.0f)",
                cheapestHour, cheapestAvg, expensiveHour, expensiveAvg);
        }
    }

    /**
     * Builds a price forecast for a specific item based on all stored price points.
     *
     * @param itemId  registry ID (e.g. "minecraft:carrot")
     * @return        Forecast, or null if insufficient data (< 5 points)
     */
    public static Forecast forecast(String itemId) {
        MarketItem item = MarketDataStore.getItem(itemId);
        if (item == null || item.getHistory().size() < 5) return null;

        // Accumulate sums per hour slot
        double[] sums   = new double[24];
        int[]    counts = new int[24];

        Calendar cal = Calendar.getInstance();
        for (PricePoint pt : item.getHistory()) {
            cal.setTimeInMillis(pt.getTimestamp());
            int h = cal.get(Calendar.HOUR_OF_DAY);
            sums[h]   += pt.getPrice();
            counts[h] += 1;
        }

        HourlyStats[] hourly = new HourlyStats[24];
        for (int h = 0; h < 24; h++) {
            hourly[h] = new HourlyStats(h);
            if (counts[h] > 0) {
                hourly[h].avgPrice   = sums[h] / counts[h];
                hourly[h].dataPoints = counts[h];
            }
        }

        int cheapestH = 0, expensiveH = 0;
        double cheapestAvg = Double.MAX_VALUE, expensiveAvg = -1;

        for (int h = 0; h < 24; h++) {
            if (counts[h] == 0) continue;
            double avg = hourly[h].avgPrice;
            if (avg < cheapestAvg)   { cheapestAvg = avg;  cheapestH = h; }
            if (avg > expensiveAvg)  { expensiveAvg = avg; expensiveH = h; }
        }

        if (cheapestAvg == Double.MAX_VALUE) return null; // no data with counts > 0

        return new Forecast(itemId, hourly, cheapestH, expensiveH, cheapestAvg, expensiveAvg);
    }

    /**
     * Returns a map of itemId → Forecast for all tracked items with enough history.
     */
    public static Map<String, Forecast> forecastAll() {
        Map<String, Forecast> results = new HashMap<>();
        for (String id : MarketDataStore.getAllData().keySet()) {
            Forecast f = forecast(id);
            if (f != null) results.put(id, f);
        }
        return results;
    }
}
