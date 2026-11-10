package io.github.carlosphenomenal.dr41.execution;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Bounded diagnostic tail of k6's stderr. Line count, line length and total bytes are all capped. */
public final class LogTail {
    private static final int MAX_LINES = 400;
    private static final int MAX_LINE_CHARS = 1000;
    private static final int MAX_TOTAL_CHARS = 64_000;

    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private int chars;

    public synchronized void add(String line) {
        String l = line.length() > MAX_LINE_CHARS ? line.substring(0, MAX_LINE_CHARS) + "…" : line;
        lines.addLast(l);
        chars += l.length();
        while (lines.size() > MAX_LINES || chars > MAX_TOTAL_CHARS) chars -= lines.removeFirst().length();
    }

    public synchronized String snapshot() { return String.join("\n", lines); }

    public synchronized List<String> last(int n) {
        List<String> all = new ArrayList<>(lines);
        return all.subList(Math.max(0, all.size() - n), all.size());
    }
}
