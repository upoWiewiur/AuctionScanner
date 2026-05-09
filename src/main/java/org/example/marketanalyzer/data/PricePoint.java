package org.example.marketanalyzer.data;

public class PricePoint {
    private long timestamp;
    private double price;
    private int volume;

    // For Gson
    public PricePoint() {
        this.volume = 1; // Default for old data
    }

    public PricePoint(long timestamp, double price, int volume) {
        this.timestamp = timestamp;
        this.price = price;
        this.volume = volume;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public double getPrice() {
        return price;
    }

    public int getVolume() {
        return volume;
    }
}
