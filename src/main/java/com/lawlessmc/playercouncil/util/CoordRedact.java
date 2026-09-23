package com.lawlessmc.playercouncil.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strip Minecraft coordinates from player-facing and Discord text. */
public final class CoordRedact {

    public static final String PLACEHOLDER = "[coords]";

    private static final Pattern DIM_PAREN = Pattern.compile(
            "(?i)\\(\\s*\\[minecraft:[^\\]]+\\]\\s*~?-?\\d+(?:\\.\\d+)?\\s*,\\s*~?-?\\d+(?:\\.\\d+)?\\s*,\\s*~?-?\\d+(?:\\.\\d+)?\\s*\\)");
    private static final Pattern LABELED_XYZ = Pattern.compile(
            "(?i)\\bx\\s*[=:]\\s*~?-?\\d+(?:\\.\\d+)?(?:\\s*[,/]\\s*|\\s+)y\\s*[=:]\\s*~?-?\\d+(?:\\.\\d+)?(?:\\s*[,/]\\s*|\\s+)z\\s*[=:]\\s*~?-?\\d+(?:\\.\\d+)?");
    private static final Pattern LABELED = Pattern.compile(
            "(?i)\\b(?:xyz|coords?|pos(?:ition)?|loc(?:ation)?)\\s*[:=]?\\s*~?-?\\d+(?:\\.\\d+)?(?:\\s*[,/;]\\s*~?-?\\d+(?:\\.\\d+)?){1,2}");
    private static final Pattern PAREN = Pattern.compile(
            "\\(\\s*~?-?\\d+(?:\\.\\d+)?\\s*,\\s*~?-?\\d+(?:\\.\\d+)?(?:\\s*,\\s*~?-?\\d+(?:\\.\\d+)?)?\\s*\\)");
    private static final Pattern TRIPLE = Pattern.compile(
            "(?<![A-Za-z0-9.])(~?-?\\d{1,8}(?:\\.\\d+)?)(?:\\s*,\\s*|\\s+|\\s*;\\s*|\\s*/\\s*)(~?-?\\d{1,3}(?:\\.\\d+)?)(?:\\s*,\\s*|\\s+|\\s*;\\s*|\\s*/\\s*)(~?-?\\d{1,8}(?:\\.\\d+)?)(?![A-Za-z0-9.])");
    private static final Pattern PAIR = Pattern.compile(
            "(?<![A-Za-z0-9.])(~?-?\\d{1,8}(?:\\.\\d+)?)\\s*,\\s*(~?-?\\d{1,8}(?:\\.\\d+)?)(?![A-Za-z0-9.])");
    private static final Pattern THOUSANDS = Pattern.compile("^\\d{1,3},\\d{3}$");

    private CoordRedact() {}

    public static String apply(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        String out = DIM_PAREN.matcher(s).replaceAll(Matcher.quoteReplacement(PLACEHOLDER));
        out = LABELED_XYZ.matcher(out).replaceAll(Matcher.quoteReplacement(PLACEHOLDER));
        out = LABELED.matcher(out).replaceAll(Matcher.quoteReplacement(PLACEHOLDER));
        out = PAREN.matcher(out).replaceAll(Matcher.quoteReplacement(PLACEHOLDER));
        out = TRIPLE.matcher(out).replaceAll(mr -> {
            try {
                double a = parse(mr.group(1));
                double b = parse(mr.group(2));
                double c = parse(mr.group(3));
                if (looksLikeY(b) || (looksLikeXz(a, c) && Math.abs(b) <= 512)) {
                    return PLACEHOLDER;
                }
            } catch (NumberFormatException ignored) {
            }
            return mr.group(0);
        });
        out = PAIR.matcher(out).replaceAll(mr -> {
            String raw = mr.group(0);
            if (THOUSANDS.matcher(raw.replace(" ", "")).matches() && !raw.startsWith("-")) {
                return raw;
            }
            try {
                if (looksLikeXz(parse(mr.group(1)), parse(mr.group(2)))) {
                    return PLACEHOLDER;
                }
            } catch (NumberFormatException ignored) {
            }
            return raw;
        });
        return out.replaceAll("\\s+", " ").trim();
    }

    private static double parse(String s) {
        return Double.parseDouble(s.replace("~", ""));
    }

    private static boolean looksLikeY(double n) {
        return n >= -128 && n <= 512;
    }

    private static boolean looksLikeXz(double a, double b) {
        return Math.abs(a) >= 16 || Math.abs(b) >= 16 || (Math.abs(a) >= 8 && Math.abs(b) >= 8);
    }
}
