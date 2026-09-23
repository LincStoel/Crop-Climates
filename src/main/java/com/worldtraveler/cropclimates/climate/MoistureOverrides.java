package com.worldtraveler.cropclimates.climate;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Pure precedence rules for biome moisture overrides - no world or registry
 * access, so {@code MoistureOverridesTest} can pin it directly.
 *
 * <p>For one biome, among every entry that matches it:
 * <ol>
 *   <li>an entry naming the biome's exact id beats any tag match;</li>
 *   <li>within that tier, the higher {@code priority} wins;</li>
 *   <li>on equal priority, the entry read later (later datapack, then later
 *       in its file) wins - and if a losing tied entry had a different value,
 *       the result is flagged as a conflict.</li>
 * </ol>
 */
public final class MoistureOverrides {

    /**
     * @param order global read position - higher means read later
     */
    public record Entry(Set<ResourceLocation> biomes, Set<ResourceLocation> tags, double moisture, int priority,
                        int order, String source) {
    }

    /** {@code conflictsWith} lists the sources of equal-priority entries that disagreed and lost. */
    public record Result(double moisture, Entry winner, List<Entry> conflictsWith) {
        public boolean conflicted() {
            return !conflictsWith.isEmpty();
        }
    }

    private final List<Entry> entries;

    public MoistureOverrides(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Returns {@code null} when no entry matches the biome. */
    public Result resolve(ResourceLocation biomeId, Predicate<ResourceLocation> inTag) {
        List<Entry> exact = new ArrayList<>();
        List<Entry> tagged = new ArrayList<>();
        for (Entry entry : entries) {
            if (biomeId != null && entry.biomes().contains(biomeId)) {
                exact.add(entry);
            } else if (entry.tags().stream().anyMatch(inTag)) {
                tagged.add(entry);
            }
        }
        List<Entry> tier = exact.isEmpty() ? tagged : exact;
        if (tier.isEmpty()) {
            return null;
        }

        Entry winner = null;
        for (Entry entry : tier) {
            if (winner == null
                    || entry.priority() > winner.priority()
                    || (entry.priority() == winner.priority() && entry.order() > winner.order())) {
                winner = entry;
            }
        }

        List<Entry> conflicts = new ArrayList<>();
        for (Entry entry : tier) {
            if (entry != winner && entry.priority() == winner.priority() && entry.moisture() != winner.moisture()) {
                conflicts.add(entry);
            }
        }
        return new Result(winner.moisture(), winner, conflicts);
    }
}
