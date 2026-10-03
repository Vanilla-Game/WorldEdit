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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Waits for every chunk's FEATURES writes before reading any generated blocks. */
final class ChunkSnapshot {
    private ChunkSnapshot() {
    }

    static <C, S> CompletableFuture<S> create(List<CompletableFuture<C>> chunks, Function<List<C>, S> snapshot) {
        return CompletableFuture.allOf(chunks.toArray(CompletableFuture[]::new)).thenApply(_ ->
            // All futures are complete here; joining them never blocks a region thread.
            snapshot.apply(chunks.stream().map(CompletableFuture::join).toList())
        );
    }
}
