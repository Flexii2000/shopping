package com.fherrmann.shopping.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest eine Menge aus dem, was Menschen tippen: "gemischtes Hack 200g" wird
 * zu "gemischtes Hack" und "200 g", "zwei Zwiebeln" zu "Zwiebeln" und "2 Stk",
 * "Klopapier 2" zu "Klopapier" und "2 Stk", "ein halbes Kilo Hack" zu "Hack"
 * und "0,5 kg", "Tomaten 3 Dosen" zu "Tomaten" und "3 Dosen".
 *
 * <p>Ziffern und Zahlwoerter, Einheiten ausgeschrieben oder abgekuerzt, mit
 * oder ohne Leerzeichen. Eine blosse Zahl zaehlt nur als Stueckzahl, wenn sie
 * ganz und klein ist: "Mehl Type 405" hat keine 405 Stueck, und "Milch 1,5"
 * meint eher den Fettgehalt. "ein paar Aepfel" ist keine Menge, auch wenn
 * "Paar" eine Einheit ist.
 *
 * <p>Die App macht dasselbe noch einmal fuer sich ({@code ShoppingQuantity}),
 * damit ein Eintrag ohne Netz schon richtig dasteht; hier ist die Fassung,
 * die fuer jeden Client gilt, auch fuer das Web. Beide schreiben dieselbe
 * Form: Zahl, Leerzeichen, Einheit - Abkuerzung oder Wort in Einzahl bzw.
 * Mehrzahl ("1 Dose", "2 Dosen").
 */
public final class QuantityParser {

    /** Name ohne Menge, Menge in der Schreibweise der Clients ("200 g", "2 Stk") oder {@code null}. */
    public record Split(String name, String quantity) {
    }

    /** Eine gelesene Menge: Zahl als Text mit Komma, Einheit als Einzahl. */
    record Quantity(String amount, String unit) {
        String text() {
            return amount + " " + form(unit, amount);
        }
    }

    private record Phrase(Quantity quantity, int used) {
    }

    private static final Pattern DIGITS = Pattern.compile("\\d+(?:[.,]\\d+)?");
    private static final Pattern GLUED = Pattern.compile("(\\d+(?:[.,]\\d+)?)([^\\d\\s.,]+\\.?)");

    private static final Map<String, String> UNITS = new HashMap<>();
    private static final Map<String, String> PLURALS = new HashMap<>();
    private static final Map<String, String> NUMBERS = new HashMap<>();

    static {
        unit("Stk", "", "stk", "st", "stck", "stueck", "stuecke", "x", "mal");
        unit("g", "g", "gr", "gramm");
        unit("kg", "kg", "kilo", "kilos", "kilogramm");
        unit("ml", "ml", "milliliter");
        unit("cl", "cl", "zentiliter");
        unit("l", "l", "liter", "ltr");
        unit("Pck", "pck", "pk", "pkg", "pkt", "pack", "packung", "packungen", "paeckchen", "packerl");
        unit("Dose", "dose", "dosen", "ds");
        unit("Flasche", "flasche", "flaschen", "fl");
        unit("Tüte", "tuete", "tueten");
        unit("Beutel", "beutel");
        unit("Becher", "becher");
        unit("Glas", "glas", "glaeser");
        unit("Tafel", "tafel", "tafeln");
        unit("Rolle", "rolle", "rollen");
        unit("Kiste", "kiste", "kisten");
        unit("Kasten", "kasten", "kaesten");
        unit("Scheibe", "scheibe", "scheiben");
        unit("Bund", "bund");
        unit("Paar", "paar");
        unit("Stange", "stange", "stangen");
        unit("Kopf", "kopf", "koepfe");
        unit("Netz", "netz", "netze");
        unit("Schale", "schale", "schalen");
        unit("Karton", "karton", "kartons");
        unit("Pfund", "pfund", "pfd");
        unit("Tube", "tube", "tuben");
        unit("Schachtel", "schachtel", "schachteln");
        unit("Portion", "portion", "portionen");
        unit("EL", "el", "essloeffel");
        unit("TL", "tl", "teeloeffel");
        unit("Prise", "prise", "prisen");
        unit("Riegel", "riegel");

        PLURALS.put("Dose", "Dosen");
        PLURALS.put("Flasche", "Flaschen");
        PLURALS.put("Tüte", "Tüten");
        PLURALS.put("Glas", "Gläser");
        PLURALS.put("Tafel", "Tafeln");
        PLURALS.put("Rolle", "Rollen");
        PLURALS.put("Kiste", "Kisten");
        PLURALS.put("Kasten", "Kästen");
        PLURALS.put("Scheibe", "Scheiben");
        PLURALS.put("Stange", "Stangen");
        PLURALS.put("Kopf", "Köpfe");
        PLURALS.put("Netz", "Netze");
        PLURALS.put("Schale", "Schalen");
        PLURALS.put("Karton", "Kartons");
        PLURALS.put("Tube", "Tuben");
        PLURALS.put("Schachtel", "Schachteln");
        PLURALS.put("Portion", "Portionen");
        PLURALS.put("Prise", "Prisen");

        number("0,5", "1/2", "½", "halb", "halbe", "halber", "halbes", "halben");
        number("1", "ein", "eine", "einen", "einem", "einer", "eins");
        number("1,5", "anderthalb", "eineinhalb", "1½");
        number("2", "zwei", "zwo");
        number("2,5", "zweieinhalb");
        number("3", "drei");
        number("4", "vier");
        number("5", "fuenf");
        number("6", "sechs");
        number("7", "sieben");
        number("8", "acht");
        number("9", "neun");
        number("10", "zehn");
        number("11", "elf");
        number("12", "zwoelf", "dutzend");
    }

    private QuantityParser() {
    }

    private static void unit(String canonical, String... names) {
        for (String name : names) {
            UNITS.put(name, canonical);
        }
    }

    private static void number(String value, String... words) {
        for (String word : words) {
            NUMBERS.put(word, value);
        }
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
        List<String> tokens = words(name);
        String plain = String.join(" ", tokens);
        if (tokens.size() < 2) {
            return new Split(plain, null);
        }
        // Hinten vor vorn: "Tomaten 3 Dosen" - der fruehste Anfang, der genau
        // bis zum Ende reicht.
        for (int start = 1; start < tokens.size(); start++) {
            Phrase phrase = phrase(tokens, start);
            if (phrase != null && start + phrase.used() == tokens.size()) {
                return new Split(String.join(" ", tokens.subList(0, start)), phrase.quantity().text());
            }
        }
        Phrase leading = phrase(tokens, 0);
        if (leading != null && leading.used() < tokens.size()) {
            return new Split(String.join(" ", tokens.subList(leading.used(), tokens.size())), leading.quantity().text());
        }
        return new Split(plain, null);
    }

    /** "500g" wird "500 g", "2" wird "2 Stk", "drei Dosen" wird "3 Dosen"; was sich nicht lesen laesst, bleibt. */
    static String normalize(String quantity) {
        String trimmed = quantity.strip();
        List<String> tokens = words(trimmed);
        Phrase phrase = tokens.isEmpty() ? null : phrase(tokens, 0);
        return phrase != null && phrase.used() == tokens.size() ? phrase.quantity().text() : trimmed;
    }

    private static List<String> words(String text) {
        List<String> result = new ArrayList<>();
        if (text == null) {
            return result;
        }
        for (String word : text.strip().split("\\s+")) {
            if (!word.isEmpty()) {
                result.add(word);
            }
        }
        return result;
    }

    /**
     * Liest ab {@code start} eine Mengenangabe: Zahl (Ziffern oder Wort, auch
     * mit der Einheit zusammengeschrieben wie "200g"), optional "halb",
     * optional Einheit.
     */
    private static Phrase phrase(List<String> tokens, int start) {
        String first = tokens.get(start);
        Matcher glued = GLUED.matcher(first);
        if (glued.matches()) {
            String unit = unit(glued.group(2));
            return unit == null ? null : new Phrase(new Quantity(glued.group(1), unit), 1);
        }
        // Ein Einheitenwort ohne Zahl heisst eins: "Liter Cola", "Packung
        // Nudeln", "Dose Tomaten". Nur ausgeschriebene Woerter - ein "l" oder
        // "g" allein ist eher ein Tippfehler als eine Menge.
        if (first.length() >= 3 && first.chars().allMatch(Character::isLetter)) {
            String unit = unit(first);
            if (unit != null) {
                return new Phrase(new Quantity("1", unit), 1);
            }
        }
        String amount = number(first);
        if (amount == null) {
            return null;
        }
        int index = start + 1;
        if (index < tokens.size() && isHalf(tokens.get(index))) {
            if (!amount.equals("1")) {
                return null;
            }
            amount = "0,5";
            index++;
        }
        if (index < tokens.size() && tokens.get(index).toLowerCase(Locale.ROOT).equals("paar")
                && first.toLowerCase(Locale.ROOT).startsWith("ein")) {
            return null;
        }
        if (index < tokens.size()) {
            String unit = unit(tokens.get(index));
            if (unit != null) {
                return new Phrase(new Quantity(amount, unit), index + 1 - start);
            }
        }
        if (!amount.matches("\\d{1,2}")) {
            return null;
        }
        return new Phrase(new Quantity(amount, "Stk"), index - start);
    }

    private static boolean isHalf(String word) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "halb", "halbe", "halber", "halbes", "halben" -> true;
            default -> false;
        };
    }

    private static String number(String word) {
        if (DIGITS.matcher(word).matches()) {
            return word;
        }
        return NUMBERS.get(fold(word));
    }

    private static String unit(String raw) {
        return UNITS.get(fold(raw));
    }

    /** Klein, ohne Punkt, Umlaute als ae/oe/ue - so, wie die Tabellen geschrieben sind. */
    private static String fold(String raw) {
        return raw.toLowerCase(Locale.ROOT).replace(".", "")
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
    }

    private static String form(String unit, String amount) {
        if (amount.equals("1")) {
            return unit;
        }
        return PLURALS.getOrDefault(unit, unit);
    }
}
