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

import ca.spottedleaf.concurrentutil.util.Priority;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.adapter.BukkitImplAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.io.file.SafeFiles;
import com.sk89q.worldedit.world.RegenOptions;
import io.papermc.paper.world.PaperWorldLoader;
import io.papermc.paper.world.saveddata.PaperWorldPDC;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer;

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

/** Owns a temporary generation world and snapshots its chunks before disposal. */
final class PaperweightRegenWorld implements BukkitImplAdapter.Regeneration {
    private final Path directory;
    private final String name = "worldeditregentempworld_" + UUID.randomUUID();
    private final Field worldsField;
    private final LevelStorageSource.LevelStorageAccess storage;
    private final ServerLevel world;
    private CompletableFuture<Clipboard> result = new CompletableFuture<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    PaperweightRegenWorld(World source, Field worldsField) throws Exception {
        this.worldsField = worldsField;
        directory = Files.createTempDirectory("WorldEditWorldGen");
        try {
            storage = LevelStorageSource.createDefault(directory).createAccess(name);
            try {
                ServerLevel original = ((CraftWorld) source).getHandle();
                ResourceKey<LevelStem> dimension = switch (source.getEnvironment()) {
                    case NETHER -> LevelStem.NETHER;
                    case THE_END -> LevelStem.END;
                    default -> LevelStem.OVERWORLD;
                };
                var data = new PaperWorldLoader.LoadedWorldData(name, UUID.randomUUID(),
                    new PaperWorldPDC((CraftPersistentDataContainer) source.getPersistentDataContainer()),
                    original.serverLevelData);
                world = new ServerLevel(original.getServer(), Util.backgroundExecutor(), storage,
                    original.worldGenSettings, original.dimension(),
                    new LevelStem(original.dimensionTypeRegistration(), original.getChunkSource().getGenerator()),
                    original.isDebug(), original.getSeed(), List.of(), false, dimension, source.getEnvironment(),
                    source.getGenerator(), source.getBiomeProvider(),
                    new SavedDataStorage(storage.getDimensionPath(original.dimension()),
                        original.getServer().getFixerUpper(), original.registryAccess()), data);
            } catch (Exception e) {
                storage.close();
                throw e;
            }
        } catch (Exception e) {
            removeWorld();
            SafeFiles.tryHardToDeleteDir(directory);
            throw e;
        }
    }

    ServerLevel world() {
        return world;
    }

    void generate(PaperweightAdapter adapter, Region region, RegenOptions options) {
        List<CompletableFuture<ChunkAccess>> chunks = new ArrayList<>();
        for (BlockVector2 position : region.getChunks()) {
            CompletableFuture<ChunkAccess> generated = new CompletableFuture<>();
            chunks.add(generated);
            world.moonrise$getChunkTaskScheduler().scheduleChunkLoad(position.x(), position.z(),
                ChunkStatus.FEATURES, true, Priority.NORMAL, chunk -> {
                    if (chunk == null) {
                        generated.completeExceptionally(new IllegalStateException("Failed to generate chunk " + position));
                    } else {
                        generated.complete(chunk);
                    }
                });
        }
        result = ChunkSnapshot.create(chunks, generated -> {
            // FEATURES can write into neighboring chunks. Snapshot only after every requested chunk finishes.
            Map<ChunkPos, ChunkAccess> byPosition = new HashMap<>();
            for (ChunkAccess chunk : generated) {
                byPosition.put(chunk.getPos(), chunk);
            }
            BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
            try {
                for (BlockVector3 block : region) {
                    if (closed.get()) {
                        throw new CancellationException("Generation world was closed");
                    }
                    ChunkAccess chunk = byPosition.get(ChunkPos.containing(new BlockPos(block.x(), block.y(), block.z())));
                    adapter.copyGeneratedBlock(clipboard, world, chunk, block, options);
                }
            } catch (WorldEditException e) {
                throw new CompletionException(e);
            }
            return clipboard;
        });
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
        try {
            // On Folia this halts generation and must run on the global region thread.
            world.getChunkSource().close(false);
        } finally {
            try {
                storage.close();
            } finally {
                try {
                    removeWorld();
                } finally {
                    SafeFiles.tryHardToDeleteDir(directory);
                    result.cancel(false);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void removeWorld() {
        try {
            Map<String, World> worlds = (Map<String, World>) worldsField.get(Bukkit.getServer());
            worlds.remove(name);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not unregister the generation world", e);
        }
    }
}
