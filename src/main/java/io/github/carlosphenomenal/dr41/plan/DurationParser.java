package io.github.carlosphenomenal.dr41.plan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses k6-style durations such as "30s", "5m", "1h30m", "2m30s" into seconds. */
public final class DurationParser {
    private static final Pattern P = Pattern.compile("(?:(\\d{1,4})h)?(?:(\\d{1,6})m)?(?:(\\d{1,8})s)?");

    private DurationParser() {}

    public static long parseSeconds(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("duration is required");
        Matcher m = P.matcher(text.trim());
        if (!m.matches() || (m.group(1) == null && m.group(2) == null && m.group(3) == null)) {
            throw new IllegalArgumentException("'" + text + "' is not a valid duration (examples: 30s, 5m, 1h30m)");
        }
        long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
        long min = m.group(2) == null ? 0 : Long.parseLong(m.group(2));
        long s = m.group(3) == null ? 0 : Long.parseLong(m.group(3));
        return h * 3600 + min * 60 + s;
    }
}
