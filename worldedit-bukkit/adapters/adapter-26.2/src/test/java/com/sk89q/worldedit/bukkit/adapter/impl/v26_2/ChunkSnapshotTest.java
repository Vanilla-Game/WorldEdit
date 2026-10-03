/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.bukkit.adapter.impl.v26_2;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkSnapshotTest {
    @Test
    void includesNeighborFeaturesWrittenAfterTheFirstChunkCompletes() {
        var first = new CompletableFuture<AtomicInteger>();
        var neighbor = new CompletableFuture<AtomicInteger>();
        var firstBlock = new AtomicInteger(1);
        var snapshot = ChunkSnapshot.create(List.of(first, neighbor),
            chunks -> chunks.stream().map(AtomicInteger::get).toList());

        first.complete(firstBlock);
        assertFalse(snapshot.isDone());
        // FEATURES has a write radius of one chunk: the neighbor adds a feature to the first chunk.
        firstBlock.set(2);
        neighbor.complete(new AtomicInteger(3));
        assertEquals(List.of(2, 3), snapshot.join());
    }

    @Test
    void doesNotReadChunksWhenGenerationFails() {
        var first = new CompletableFuture<Integer>();
        var neighbor = new CompletableFuture<Integer>();
        AtomicInteger reads = new AtomicInteger();
        var snapshot = ChunkSnapshot.create(List.of(first, neighbor), chunks -> {
            reads.incrementAndGet();
            return chunks;
        });
        first.complete(1);
        neighbor.completeExceptionally(new IllegalStateException("generation failed"));
        assertTrue(snapshot.isCompletedExceptionally());
        assertEquals(0, reads.get());
    }
}
