package org.example.marketanalyzer.logic;

import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.example.marketanalyzer.MarketAnalyzerMod;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PriceParser {

    // ── Pattern 1: labelled price (most specific) ─────────────────────────────
    // Matches: "Price: $12.34K", "Worth: 500", "Buy Now: $1,200", "Cost: 5M"
    private static final Pattern LABELLED = Pattern.compile(
        "(?i)(?:price|worth|buy\\s*now|cost|value|starting\\s*bid|bid|sell\\s*price)\\s*[:\\-]?\\s*\\$?\\s*([\\d,]+(?:\\.\\d+)?)\\s*([kKmMbB]?)"
    );

    // ── Pattern 2: bare dollar amount anywhere on a lore line ─────────────────
    // Matches: "$12,345", "$1.5K", "$ 500"
    private static final Pattern DOLLAR = Pattern.compile(
        "\\$\\s*([\\d,]+(?:\\.\\d+)?)\\s*([kKmMbB]?)"
    );

    // ── Pattern 3: number+suffix standing alone on a line (fallback) ──────────
    // Matches a line that is MOSTLY a number, e.g. "1,200" or "1.5K"
    // Requires at least 2 digits to avoid false positives from level/enchant numbers
    private static final Pattern BARE_NUMBER = Pattern.compile(
        "^[^a-zA-Z$]*?([\\d,]{2,}(?:\\.\\d+)?)\\s*([kKmMbB])\\s*$"
    );

    /**
     * Try every pattern in order of specificity.
     * Returns the first price found, or 0.0 if nothing matches.
     * Also logs ALL lore lines to help debug unknown formats.
     */
    public static double parsePriceFromLore(ItemStack stack, List<Text> tooltip) {
        if (stack == null || stack.isEmpty() || tooltip == null) return 0.0;

        // Log all lore for debugging (first 10 items per scan)
        if (debugLogCount < 10) {
            StringBuilder sb = new StringBuilder("[PriceParser] Lore for \"")
                .append(stack.getName().getString()).append("\":");
            for (Text line : tooltip) sb.append("\n  | ").append(line.getString());
            MarketAnalyzerMod.LOGGER.info(sb.toString());
            debugLogCount++;
        }

        // Try labelled first
        for (Text line : tooltip) {
            double v = tryPattern(LABELLED, line.getString());
            if (v > 0) return v;
        }
        // Then bare dollar
        for (Text line : tooltip) {
            double v = tryPattern(DOLLAR, line.getString());
            if (v > 0) return v;
        }
        // Then bare number with suffix
        for (Text line : tooltip) {
            double v = tryPattern(BARE_NUMBER, line.getString().trim());
            if (v > 0) return v;
        }
        return 0.0;
    }

    private static int debugLogCount = 0;

    /** Called by MarketScanner at the start of each full scan to re-enable debug logging. */
    public static void resetDebugLogCount() {
        debugLogCount = 0;
    }

    private static double tryPattern(Pattern p, String text) {
        // Strip Minecraft §colour codes first
        String clean = text.replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim();
        Matcher m = p.matcher(clean);
        if (m.find()) {
            try {
                double price = Double.parseDouble(m.group(1).replace(",", ""));
                String suffix = m.group(2).toUpperCase();
                switch (suffix) {
                    case "K": price *= 1_000; break;
                    case "M": price *= 1_000_000; break;
                    case "B": price *= 1_000_000_000; break;
                }
                return price;
            } catch (NumberFormatException ignored) {}
        }
        return 0.0;
    }
}
