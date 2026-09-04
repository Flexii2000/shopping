package com.fherrmann.shopping.service;

import com.fherrmann.shopping.model.Category;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Raet die Kategorie eines Eintrags aus seinem Namen.
 *
 * <p>Vier Stufen, die erste, die trifft, gewinnt:
 * <ol>
 * <li><b>Gelernt</b>: was jemand von Hand gewaehlt hat ({@code learned} in der
 *     Datei), je normalisiertem Namen. Schlaegt alles andere.</li>
 * <li><b>Woerterbuch</b> ({@code categories.txt} im Jar): erst der ganze Name,
 *     dann seine Einzahl, dann als Bestandteil eines Kompositums - laengster
 *     Eintrag zuerst, damit "Kokosmilch" bei Vorrat landet und nicht ueber
 *     "milch" bei den Milchprodukten. Davor eine Ausnahme: "TK" oder
 *     "Tiefkuehl" im Namen heisst Tiefkuehl, egal was dahinter steht.</li>
 * <li><b>Endungen</b> als Rueckfall: "...kaese", "...saft", "...brot".</li>
 * <li>Sonst Sonstiges.</li>
 * </ol>
 *
 * <p>Normalisiert wird vorher: Kleinschreibung, Umlaute als ae/oe/ue/ss (so
 * passen beide Schreibweisen), Satzzeichen weg, Mengen am Anfang und Ende weg
 * ("2 Zwiebeln", "Milch 1l", "500g Hack"), Fuellwoerter wie "bio" weg. Beim
 * Vergleich werden Plural-Endungen grob abgeschnitten (-n, -en, -e, -s, -er).
 */
@Component
public class Categorizer {

    static final String DICTIONARY = "/categories.txt";

    /** Mengenangabe: Zahl, optional Einheit - "2", "500g", "1,5 l", "10er", "3x". */
    private static final String QUANTITY =
            "\\d+(?:[.,]\\d+)?\\s*(?:x|g|kg|mg|ml|cl|l|liter|gramm|kilo|stk|stueck|st|pck|pack|packung|packungen|"
            + "dose|dosen|fl|flasche|flaschen|tuete|tueten|becher|riegel|scheiben|er|paar|bund)?";
    private static final Pattern LEADING_QUANTITY = Pattern.compile("^(?:" + QUANTITY + ")(?:\\s+|$)");
    private static final Pattern TRAILING_QUANTITY = Pattern.compile("(?:^|\\s+)(?:" + QUANTITY + ")$");
    private static final Pattern FILLER = Pattern.compile("^(?:bio|frisch|frische|frischer|frisches|ein|eine|einen|etwas|paar|neue|neuer|neues)\\s+");
    private static final Pattern NOT_A_LETTER = Pattern.compile("[^a-z0-9 ]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final String[] PLURAL_ENDINGS = {"nen", "en", "n", "e", "s", "er"};

    /** Endungen als letzter Rueckfall - Reihenfolge zaehlt, der erste Treffer gewinnt. */
    private static final Map<String, Category> SUFFIXES = new LinkedHashMap<>();

    static {
        String[][] rules = {
                {"kaese", "dairy"}, {"joghurt", "dairy"}, {"jogurt", "dairy"}, {"quark", "dairy"}, {"sahne", "dairy"},
                {"milch", "dairy"}, {"butter", "dairy"}, {"broetchen", "bakery"}, {"brot", "bakery"},
                {"kuchen", "bakery"}, {"toast", "bakery"}, {"wurst", "meat"}, {"fleisch", "meat"}, {"filet", "meat"},
                {"fisch", "meat"}, {"schinken", "meat"}, {"schnitzel", "meat"}, {"steak", "meat"}, {"saft", "beverages"},
                {"schorle", "beverages"}, {"limo", "beverages"}, {"limonade", "beverages"}, {"tee", "beverages"},
                {"kaffee", "beverages"}, {"bier", "beverages"}, {"wein", "beverages"}, {"wasser", "beverages"},
                {"cola", "beverages"}, {"chips", "snacks"}, {"schokolade", "snacks"}, {"kekse", "snacks"},
                {"keks", "snacks"}, {"riegel", "snacks"}, {"nuesse", "snacks"}, {"bonbons", "snacks"},
                {"gummi", "snacks"}, {"gewuerz", "spices"}, {"sauce", "spices"}, {"sosse", "spices"},
                {"dressing", "spices"}, {"senf", "spices"}, {"essig", "spices"}, {"oel", "staples"},
                {"mehl", "staples"}, {"nudeln", "staples"}, {"reis", "staples"}, {"flocken", "staples"},
                {"muesli", "staples"}, {"zucker", "staples"}, {"dose", "canned"}, {"konserve", "canned"}, {"suppe", "canned"},
                {"pizza", "frozen"}, {"eis", "frozen"}, {"shampoo", "drugstore"}, {"creme", "drugstore"},
                {"seife", "drugstore"}, {"spray", "drugstore"}, {"lotion", "drugstore"}, {"gel", "drugstore"},
                {"reiniger", "household"}, {"tuecher", "household"}, {"beutel", "household"}, {"papier", "household"},
                {"rolle", "household"}, {"schwamm", "household"}, {"lappen", "household"}, {"mittel", "household"},
                {"tabs", "household"}, {"kerze", "household"}, {"kerzen", "household"},
        };
        for (String[] rule : rules) {
            SUFFIXES.put(rule[0], Category.orOther(rule[1]));
        }
    }

    private final Map<String, Category> exact = new HashMap<>();
    /** Alle Eintraege, laengster Schluessel zuerst - fuer die Suche im Kompositum. */
    private final List<Map.Entry<String, Category>> byLength;

    public Categorizer() {
        this(loadDictionary());
    }

    Categorizer(Map<String, Category> dictionary) {
        dictionary.forEach((name, category) -> exact.put(normalize(name), category));
        byLength = new ArrayList<>(exact.entrySet());
        byLength.sort(Comparator.comparingInt((Map.Entry<String, Category> e) -> e.getKey().length()).reversed()
                .thenComparing(Map.Entry::getKey));
    }

    private static Map<String, Category> loadDictionary() {
        Map<String, Category> result = new LinkedHashMap<>();
        try (InputStream in = Categorizer.class.getResourceAsStream(DICTIONARY)) {
            if (in == null) {
                throw new IllegalStateException(DICTIONARY + " fehlt im Jar.");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                String text = line.strip();
                if (text.isEmpty() || text.startsWith("#")) {
                    continue;
                }
                int eq = text.indexOf('=');
                if (eq <= 0) {
                    throw new IllegalStateException(DICTIONARY + ":" + number + ": erwartet 'name = key'");
                }
                String name = text.substring(0, eq).strip();
                int at = number;
                Category category = Category.fromKey(text.substring(eq + 1).strip())
                        .orElseThrow(() -> new IllegalStateException(DICTIONARY + ":" + at + ": unbekannte Kategorie"));
                result.put(name, category);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    /** Der Name, wie er verglichen und gelernt wird. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.toLowerCase(Locale.GERMAN).trim()
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        // Akzente weg (crème fraîche -> creme fraiche) - nach den Umlauten,
        // sonst wuerde aus ae ein a.
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        s = NOT_A_LETTER.matcher(s).replaceAll(" ");
        s = SPACES.matcher(s).replaceAll(" ").trim();
        String before;
        do {
            before = s;
            s = LEADING_QUANTITY.matcher(s).replaceFirst("").trim();
            s = TRAILING_QUANTITY.matcher(s).replaceFirst("").trim();
            s = FILLER.matcher(s).replaceFirst("").trim();
        } while (!s.equals(before) && !s.isEmpty());
        return s.isEmpty() ? before : s;
    }

    public Category classify(String rawName, Map<String, String> learned) {
        String name = normalize(rawName);
        if (name.isEmpty()) {
            return Category.OTHER;
        }
        // 1. Gelernt - auch fuer die Einzahl, damit "Tomaten" trifft, was
        //    fuer "Tomate" gewaehlt wurde.
        for (String variant : singularVariants(name)) {
            String key = learned.get(variant);
            if (key != null) {
                Optional<Category> category = Category.fromKey(key);
                if (category.isPresent()) {
                    return category.get();
                }
            }
        }
        // 2. Tiefkuehl schlaegt jedes Woerterbuch: "TK-Erbsen" sind keine
        //    Erbsen aus der Gemueseabteilung.
        if (name.startsWith("tk ") || name.equals("tk") || name.contains("tiefkuehl") || name.contains("gefroren")) {
            return Category.FROZEN;
        }
        // 3. Woerterbuch: ganzer Name, dann Einzahl, dann jedes Wort.
        for (String variant : singularVariants(name)) {
            Category category = exact.get(variant);
            if (category != null) {
                return category;
            }
        }
        String[] words = name.split(" ");
        if (words.length > 1) {
            for (String word : words) {
                for (String variant : singularVariants(word)) {
                    Category category = exact.get(variant);
                    if (category != null) {
                        return category;
                    }
                }
            }
        }
        // 4. Bestandteil eines Kompositums. Erst das Grundwort am Ende
        //    (Rhabarber-SAFT ist ein Getraenk, Kuerbiskern-BROT ein Brot), dann
        //    der Anfang; je laengster Eintrag zuerst, und nur Eintraege ab
        //    vier Zeichen, sonst traefe "eis" auf "reis".
        for (Map.Entry<String, Category> entry : byLength) {
            String key = entry.getKey();
            if (key.length() < 4) {
                break;
            }
            for (String word : words) {
                for (String variant : singularVariants(word)) {
                    if (variant.length() > key.length() && variant.endsWith(key)) {
                        return entry.getValue();
                    }
                }
            }
        }
        // Der Anfang nur bei laengeren Eintraegen: "Brie" steckt auch in
        //    "Briefmarken", "Brot" in "Brotzeitbox".
        for (Map.Entry<String, Category> entry : byLength) {
            String key = entry.getKey();
            if (key.length() < 6) {
                break;
            }
            for (String word : words) {
                if (word.length() > key.length() && word.startsWith(key)) {
                    return entry.getValue();
                }
            }
        }
        // 5. Endungen als letzter Rueckfall.
        for (String word : words) {
            for (Map.Entry<String, Category> rule : SUFFIXES.entrySet()) {
                if (word.endsWith(rule.getKey()) && word.length() >= rule.getKey().length()) {
                    return rule.getValue();
                }
            }
        }
        return Category.OTHER;
    }

    /** Das Wort selbst, dann grob um Plural-Endungen gekuerzt - nur, wenn genug uebrig bleibt. */
    static List<String> singularVariants(String word) {
        List<String> result = new ArrayList<>();
        result.add(word);
        for (String ending : PLURAL_ENDINGS) {
            if (word.endsWith(ending) && word.length() - ending.length() >= 3) {
                String cut = word.substring(0, word.length() - ending.length());
                if (!result.contains(cut)) {
                    result.add(cut);
                }
            }
        }
        return result;
    }

    int dictionarySize() {
        return exact.size();
    }
}
