package com.fherrmann.shopping.recipe;

import com.fherrmann.shopping.dto.DishRequest;
import com.fherrmann.shopping.model.Ingredient;
import com.fherrmann.shopping.model.Nutrition;

import java.util.List;

/**
 * Ein Rezept, das der Rezept-Agent vorgeschlagen hat - schon in der Form, in der es
 * gespeichert wuerde: Mengen als Text ("250 g"), Naehrwerte je Portion. Haengt an der
 * Antwort im Chat, bis jemand "Speichern" drueckt.
 *
 * <p>Wie beim Rezept selbst nur Wrapper-Typen: der Verlauf liegt in einer Datei, und
 * ein Feld, das spaeter dazukommt, darf aeltere Zeilen nicht unlesbar machen.
 */
public record RecipeDraft(String name, Integer portions, Integer minutes, List<Ingredient> ingredients,
                          List<String> steps, Nutrition nutrition, Double gramsPerPortion) {

    public RecipeDraft {
        ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /** Derselbe Inhalt als Rumpf zum Anlegen. */
    public DishRequest toRequest() {
        return new DishRequest(name,
                ingredients.stream()
                        .map(i -> new DishRequest.IngredientRequest(i.name(), i.quantity(), i.pantry())).toList(),
                portions, minutes, steps,
                nutrition == null ? null : new DishRequest.NutritionRequest(nutrition.kcal(), nutrition.proteinG(),
                        nutrition.carbsG(), nutrition.fatG()),
                gramsPerPortion);
    }
}
