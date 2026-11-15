package local.webuilder;

import java.util.Locale;

final class AxisInput {
    static final String PROMPT = "输入方向：x+ / x- / y+ / y- / z+ / z-";
    // Preserve the existing direction packet IDs and legacy numeric inputs.
    static int parse(String text) {
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "z-", "1" -> 1;
            case "z+", "2" -> 2;
            case "x-", "3" -> 3;
            case "x+", "4" -> 4;
            case "y+", "5" -> 5;
            case "y-", "6" -> 6;
            default -> throw new NumberFormatException("direction");
        };
    }
}
