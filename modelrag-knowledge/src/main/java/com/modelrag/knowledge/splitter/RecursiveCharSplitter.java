package com.modelrag.knowledge.splitter;

import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import java.io.Reader;
import java.util.function.Consumer;

/** Structure-aware bounded splitter with a conservative CJK/Latin token estimate. */
public final class RecursiveCharSplitter implements ChunkSplitter {
    @Override
    public List<String> split(String text, int size, int overlap) {
        List<String> output = new ArrayList<>();
        forEachWindow(text, size, overlap, Integer.MAX_VALUE, output::addAll);
        return output;
    }

    /** Emits bounded windows without materializing the complete chunk list. */
    public void forEachWindow(String text, int size, int overlap, int windowSize,
            Consumer<List<String>> consumer) {
        if (size < 1 || overlap < 0 || overlap >= size || windowSize < 1 || consumer == null) {
            throw new IllegalArgumentException("invalid chunk parameters");
        }
        String normalized = text == null ? "" : text.trim();
        List<String> window = new ArrayList<>(Math.min(windowSize, 64));
        for (int start = 0; start < normalized.length();) {
            int target = advanceByTokens(normalized, start, size);
            int end = fenceAwareEnd(normalized, start, target, normalized.length());
            String part = normalized.substring(start, end).trim();
            if (!part.isEmpty()) {
                window.add(part);
                if (window.size() >= windowSize) {
                    consumer.accept(List.copyOf(window));
                    window.clear();
                }
            }
            if (end == normalized.length()) break;
            int next = Math.max(start + 1, rewindByTokens(normalized, end, overlap));
            while (next < normalized.length() && Character.isWhitespace(normalized.charAt(next))) next++;
            start = next;
        }
        if (!window.isEmpty()) consumer.accept(List.copyOf(window));
    }

    /** Reads and emits bounded windows without materializing the complete document text. */
    public void forEachWindow(Reader reader, int size, int overlap, int windowSize,
            Consumer<List<String>> consumer) throws IOException {
        if (reader == null) throw new IllegalArgumentException("reader 不能为空");
        if (size < 1 || overlap < 0 || overlap >= size || windowSize < 1 || consumer == null) {
            throw new IllegalArgumentException("invalid chunk parameters");
        }
        StringBuilder pending = new StringBuilder(8192);
        char[] input = new char[8192];
        boolean eof = false;
        List<String> window = new ArrayList<>(Math.min(windowSize, 64));
        while (!pending.isEmpty() || !eof) {
            while (!eof && tokenEstimate(pending) < size) {
                int read = reader.read(input);
                if (read < 0) {
                    eof = true;
                } else if (read > 0) {
                    pending.append(input, 0, read);
                }
            }
            while (!eof && insideFence(pending, advanceByTokens(pending.toString(), 0, size))) {
                int read = reader.read(input);
                if (read < 0) eof = true;
                else if (read > 0) pending.append(input, 0, read);
                if (!insideFence(pending, pending.length())) break;
            }
            if (pending.isEmpty()) break;

            String snapshot = pending.toString();
            int target = advanceByTokens(snapshot, 0, size);
            int end = fenceAwareEnd(snapshot, 0, target, snapshot.length());
            String part = snapshot.substring(0, end).trim();
            if (!part.isEmpty()) {
                window.add(part);
                if (window.size() >= windowSize) {
                    consumer.accept(List.copyOf(window));
                    window.clear();
                }
            }
            if (end == snapshot.length() && eof) {
                pending.setLength(0);
                break;
            }
            int next = Math.max(1, rewindByTokens(snapshot, end, overlap));
            while (next < snapshot.length() && Character.isWhitespace(snapshot.charAt(next))) next++;
            pending.delete(0, next);
        }
        if (!window.isEmpty()) consumer.accept(List.copyOf(window));
    }

    private int nearestBoundary(String text, int start, int target) {
        int capped = Math.min(target, text.length());
        for (int index = capped; index > start; index--) {
            char previous = text.charAt(index - 1);
            if (previous == '\n' || previous == '。' || previous == '！' || previous == '？'
                    || previous == '.' || previous == '!' || previous == '?' || Character.isWhitespace(previous)) {
                return index;
            }
        }
        return Math.max(start + 1, capped);
    }

    private int fenceAwareEnd(String text, int start, int target, int availableEnd) {
        if (!insideFence(text.subSequence(start, availableEnd), Math.min(target - start, availableEnd - start))) {
            return nearestBoundary(text, start, target);
        }
        int closing = text.indexOf("```", Math.max(start, target));
        if (closing < 0) closing = text.indexOf("~~~", Math.max(start, target));
        if (closing < 0) return availableEnd;
        int lineEnd = text.indexOf('\n', closing + 3);
        return lineEnd < 0 ? availableEnd : lineEnd + 1;
    }

    private boolean insideFence(CharSequence text, int end) {
        boolean open = false;
        int offset = 0;
        while (offset < Math.min(end, text.length())) {
            int lineEnd = offset;
            while (lineEnd < Math.min(end, text.length()) && text.charAt(lineEnd) != '\n') lineEnd++;
            String line = text.subSequence(offset, lineEnd).toString().trim();
            if (line.startsWith("```") || line.startsWith("~~~")) open = !open;
            offset = lineEnd + 1;
        }
        return open;
    }

    private int advanceByTokens(String text, int start, int tokens) {
        int index = start;
        double used = 0;
        while (index < text.length() && used < tokens) used += tokenWeight(text.charAt(index++));
        return index;
    }

    private double tokenEstimate(CharSequence text) {
        double used = 0;
        for (int index = 0; index < text.length(); index++) used += tokenWeight(text.charAt(index));
        return used;
    }

    private int rewindByTokens(String text, int end, int tokens) {
        int index = end;
        double used = 0;
        while (index > 0 && used < tokens) used += tokenWeight(text.charAt(--index));
        return index;
    }

    private double tokenWeight(char value) {
        return Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN ? 1 : .25;
    }
}
