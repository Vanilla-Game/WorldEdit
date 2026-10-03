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
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.io.file.SafeFiles;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Owns temporary generation resources and snapshots chunks after all their FEATURES writes finish.
 * Construction, resource registration and disposal must run on the global region thread on Folia.
 *
 * @param <C> the adapter's chunk type
 */
public final class BukkitRegeneration<C> implements BukkitImplAdapter.Regeneration {
    private final String name = "worldeditregentempworld_" + UUID.randomUUID();
    private final Path directory;
    private final List<AutoCloseable> resources = new ArrayList<>();
    private final CompletableFuture<Clipboard> result = new CompletableFuture<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public BukkitRegeneration(Field worldsField) throws IOException {
        this(worlds(worldsField));
    }

    BukkitRegeneration(Map<String, World> worlds) throws IOException {
        directory = Files.createTempDirectory("WorldEditWorldGen");
        registerResource(() -> SafeFiles.tryHardToDeleteDir(directory));
        registerResource(() -> worlds.remove(name));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, World> worlds(Field field) throws IOException {
        try {
            return (Map<String, World>) field.get(Bukkit.getServer());
        } catch (IllegalAccessException e) {
            throw new IOException("Could not access the server's worlds", e);
        }
    }

    public String name() {
        return name;
    }

    public Path directory() {
        return directory;
    }

    /** Register immediately after acquisition; resources are closed in reverse order. */
    public <T extends AutoCloseable> T registerResource(T resource) {
        resources.add(resource);
        return resource;
    }

    public void generate(Region selection, BiConsumer<BlockVector2, Consumer<C>> loadChunk,
                         SnapshotCopier<C> copy) {
        Region region = selection.clone();
        List<CompletableFuture<C>> chunks = new ArrayList<>();
        for (BlockVector2 position : region.getChunks()) {
            CompletableFuture<C> chunk = new CompletableFuture<>();
            chunks.add(chunk);
            loadChunk.accept(position, generated -> {
                if (generated == null) {
                    chunk.completeExceptionally(new IllegalStateException("Failed to generate chunk " + position));
                } else {
                    chunk.complete(generated);
                }
            });
        }
        var _ = CompletableFuture.allOf(chunks.toArray(CompletableFuture[]::new)).thenApply(_ -> {
            // FEATURES can write into neighboring chunks. Read only after every requested chunk finishes.
            checkOpen();
            BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
            try {
                // All futures are complete; the adapter can read them without blocking.
                copy.copy(region, clipboard, chunks);
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

    /** Check before reading each generated block, since disposal can race snapshot creation. */
    public void checkOpen() {
        if (closed.get()) {
            throw new CancellationException("Generation world was closed");
        }
    }

    @FunctionalInterface
    public interface SnapshotCopier<C> {
        void copy(Region region, Clipboard clipboard, List<CompletableFuture<C>> chunks) throws WorldEditException;
    }

    @Override
    public CompletionStage<Clipboard> result() {
        return result;
    }

    @Override
    public void close() throws IOException {
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
            result.cancel(false);
        }
        if (failure != null) {
            throw failure;
        }
    }
}
