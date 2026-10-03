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

package com.sk89q.worldedit.bukkit.adapter;

import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.io.file.SafeFiles;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Owns temporary generation resources and snapshots chunks after all their FEATURES writes finish.
 * Construction, resource registration and disposal must run on the global region thread on Folia.
 *
 * @param <C> the adapter's chunk type
 */
public abstract class AbstractRegeneration<C> implements BukkitImplAdapter.Regeneration {
    private final String name = "worldeditregentempworld_" + UUID.randomUUID();
    private final Path directory;
    private final Map<String, World> worlds;
    private final List<AutoCloseable> resources = new ArrayList<>();
    private final CompletableFuture<Clipboard> result = new CompletableFuture<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    protected AbstractRegeneration(Field worldsField) throws IOException {
        this(worlds(worldsField));
    }

    protected AbstractRegeneration(Map<String, World> worlds) throws IOException {
        this.worlds = worlds;
        directory = Files.createTempDirectory("WorldEditWorldGen");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, World> worlds(Field field) throws IOException {
        try {
            return (Map<String, World>) field.get(Bukkit.getServer());
        } catch (IllegalAccessException e) {
            throw new IOException("Could not access the server's worlds", e);
        }
    }

    protected final String name() {
        return name;
    }

    protected final Path directory() {
        return directory;
    }

    /** Register immediately after acquisition; resources are closed in reverse order. */
    protected final <T extends AutoCloseable> T registerResource(T resource) {
        resources.add(resource);
        return resource;
    }

    protected final void generate(Region selection, Function<BlockVector2, CompletableFuture<C>> loadChunk,
                                  BlockCopier<C> copyBlock) {
        Region region = selection.clone();
        List<BlockVector2> positions = new ArrayList<>(region.getChunks());
        List<CompletableFuture<C>> chunks = positions.stream().map(loadChunk).toList();
        var _ = ChunkSnapshot.create(chunks, generated -> {
            // FEATURES can write into neighboring chunks. Read only after every requested chunk finishes.
            Map<BlockVector2, C> byPosition = new HashMap<>();
            for (int i = 0; i < positions.size(); i++) {
                byPosition.put(positions.get(i), generated.get(i));
            }
            BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
            try {
                for (BlockVector3 block : region) {
                    if (closed.get()) {
                        throw new CancellationException("Generation world was closed");
                    }
                    C chunk = byPosition.get(BlockVector2.at(block.x() >> 4, block.z() >> 4));
                    copyBlock.copy(clipboard, chunk, block);
                }
            } catch (WorldEditException e) {
                throw new CompletionException(e);
            }
            return clipboard;
        }).whenComplete((snapshot, error) -> {
            if (error == null) {
                result.complete(snapshot);
            } else {
                result.completeExceptionally(error);
            }
        });
    }

    @FunctionalInterface
    protected interface BlockCopier<C> {
        void copy(Clipboard clipboard, C chunk, BlockVector3 position) throws WorldEditException;
    }

    @Override
    public final CompletionStage<Clipboard> result() {
        return result;
    }

    @Override
    public final void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        IOException failure = null;
        try {
            for (AutoCloseable resource : resources.reversed()) {
                try {
                    resource.close();
                } catch (Exception e) {
                    if (failure == null) {
                        failure = new IOException("Could not close generation resources", e);
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
        } finally {
            try {
                worlds.remove(name);
            } finally {
                try {
                    SafeFiles.tryHardToDeleteDir(directory);
                } catch (IOException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                } finally {
                    result.cancel(false);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
