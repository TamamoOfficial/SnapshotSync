package com.snapsync.core;

import java.util.Collections;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * What changed between two snapshots.
 *
 * <p>Reported in logs and in {@code /snapsync status} so an admin can see that a timed build
 * did real work - or that it correctly did nothing.
 */
public record ChangeSummary(
        SortedSet<String> added,
        SortedSet<String> modified,
        SortedSet<String> removed
) {

    public ChangeSummary {
        added = Collections.unmodifiableSortedSet(new TreeSet<>(added));
        modified = Collections.unmodifiableSortedSet(new TreeSet<>(modified));
        removed = Collections.unmodifiableSortedSet(new TreeSet<>(removed));
    }

    public static ChangeSummary empty() {
        return new ChangeSummary(new TreeSet<>(), new TreeSet<>(), new TreeSet<>());
    }

    public boolean isEmpty() {
        return added.isEmpty() && modified.isEmpty() && removed.isEmpty();
    }

    public int total() {
        return added.size() + modified.size() + removed.size();
    }

    /** One-line form for the log. */
    public String describe() {
        if (isEmpty()) {
            return "no changes";
        }
        return "+" + added.size() + " ~" + modified.size() + " -" + removed.size();
    }
}
