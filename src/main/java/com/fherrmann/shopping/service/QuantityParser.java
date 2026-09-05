package com.fherrmann.shopping.service;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest eine Menge aus dem Namen heraus, wie man sie tippt: "gemischtes Hack
 * 200g" wird zu "gemischtes Hack" und "200 g", "2 Zwiebeln" zu "Zwiebeln" und
 * "2 Stk", "Milch 1l" zu "Milch" und "1 l".
 *
 * <p>Eine blosse Zahl zaehlt nur als Stueckzahl, wenn sie ganz und klein ist:
 * "Mehl Type 405" hat keine 405 Stueck, und "Milch 1,5" meint eher den
 * Fettgehalt. Die Einheiten stehen als Aufzaehlung im Muster - sonst hielte
 * "2 gemischte Brote" das Wort "gemischte" fuer eine Einheit.
 *
 * <p>Die App macht dasselbe noch einmal fuer sich ({@code ShoppingQuantity}),
 * damit ein Eintrag ohne Netz schon richtig dasteht; hier ist die Fassung,
 * die fuer jeden Client gilt, auch fuer das Web.
 */
public final class QuantityParser {

    /** Name ohne Menge, Menge in der Schreibweise der Clients ("200 g", "2 Stk") oder {@code null}. */
    public record Split(String name, String quantity) {
    }

    private static final String UNITS = "stk|stück|stueck|stck|st|x|g|gr|gramm|kg|kilo|kilogramm|ml|milliliter|"
            + "l|liter|ltr|pck|pk|pkg|pack|packung|packungen|päckchen|paeckchen";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern TRAILING = Pattern.compile("^(.+?)\\s+(\\d+(?:[.,]\\d+)?)\\s*(" + UNITS + ")?$", FLAGS);
    private static final Pattern LEADING = Pattern.compile("^(\\d+(?:[.,]\\d+)?)\\s*(" + UNITS + ")?\\s+(.+)$", FLAGS);
    private static final Pattern ONLY = Pattern.compile("^(\\d+(?:[.,]\\d+)?)\\s*(" + UNITS + ")?\\.?$", FLAGS);

    private QuantityParser() {
    }

    /**
     * Name und Menge, wie sie gespeichert werden: steckt die Menge im Namen und
     * ist keine angegeben, wird sie herausgeloest; eine angegebene Menge wird
     * in die Schreibweise der Clients gebracht ("500g" wird "500 g").
     */
    public static Split resolve(String name, String quantity) {
        if (quantity != null && !quantity.isBlank()) {
            return new Split(name, normalize(quantity));
        }
        return split(name);
    }

    static Split split(String name) {
        String trimmed = name == null ? "" : name.strip();
        Matcher trailing = TRAILING.matcher(trimmed);
        if (trailing.matches()) {
            String quantity = quantity(trailing.group(2), trailing.group(3));
            if (quantity != null) {
                return new Split(trailing.group(1).strip(), quantity);
            }
        }
        Matcher leading = LEADING.matcher(trimmed);
        if (leading.matches()) {
            String quantity = quantity(leading.group(1), leading.group(2));
            if (quantity != null) {
                return new Split(leading.group(3).strip(), quantity);
            }
        }
        return new Split(trimmed, null);
    }

    /** "500g" wird "500 g", "2" wird "2 Stk"; was sich nicht lesen laesst ("eine Handvoll"), bleibt. */
    static String normalize(String quantity) {
        String trimmed = quantity.strip();
        Matcher only = ONLY.matcher(trimmed);
        if (!only.matches()) {
            return trimmed;
        }
        String unit = canonicalUnit(only.group(2));
        return unit == null ? trimmed : only.group(1) + " " + unit;
    }

    private static String quantity(String amount, String unitText) {
        String unit = canonicalUnit(unitText);
        if (unit == null) {
            return null;
        }
        if (unitText == null || unitText.isEmpty()) {
            if (amount.contains(",") || amount.contains(".") || amount.length() > 2) {
                return null;
            }
        }
        return amount + " " + unit;
    }

    private static String canonicalUnit(String raw) {
        String u = raw == null ? "" : raw.toLowerCase(Locale.ROOT).replace(".", "");
        return switch (u) {
            case "", "stk", "stück", "stueck", "stck", "st", "x" -> "Stk";
            case "g", "gr", "gramm" -> "g";
            case "kg", "kilo", "kilogramm" -> "kg";
            case "ml", "milliliter" -> "ml";
            case "l", "liter", "ltr" -> "l";
            case "pck", "pk", "pkg", "pack", "packung", "packungen", "päckchen", "paeckchen" -> "Pck";
            default -> null;
        };
    }
}
