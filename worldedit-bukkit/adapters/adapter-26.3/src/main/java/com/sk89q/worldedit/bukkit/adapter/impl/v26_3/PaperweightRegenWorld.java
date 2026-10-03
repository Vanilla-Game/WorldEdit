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

package com.sk89q.worldedit.bukkit.adapter.impl.v26_3;

import ca.spottedleaf.concurrentutil.util.Priority;
import com.sk89q.worldedit.bukkit.adapter.AbstractRegeneration;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.RegenOptions;
import io.papermc.paper.world.PaperWorldLoader;
import io.papermc.paper.world.saveddata.PaperWorldPDC;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Creates the version-specific generation world; the common base owns its lifetime and snapshot. */
final class PaperweightRegenWorld extends AbstractRegeneration<ChunkAccess> {
    private final ServerLevel world;

    PaperweightRegenWorld(World source, Field worldsField) throws Exception {
        super(worldsField);
        try {
            ResourceKey<LevelStem> dimension = switch (source.getEnvironment()) {
                case NETHER -> LevelStem.NETHER;
                case THE_END -> LevelStem.END;
                default -> LevelStem.OVERWORLD;
            };
            var storage = registerResource(LevelStorageSource.createDefault(directory()).createAccess(name()));
            ServerLevel original = ((CraftWorld) source).getHandle();
            var data = new PaperWorldLoader.LoadedWorldData(name(), UUID.randomUUID(),
                new PaperWorldPDC((CraftPersistentDataContainer) source.getPersistentDataContainer()),
                original.serverLevelData);
            world = new ServerLevel(original.getServer(), Util.backgroundExecutor(), storage,
                original.worldGenSettings, original.dimension(),
                new LevelStem(original.dimensionTypeRegistration(), original.getChunkSource().getGenerator()),
                original.isDebug(), original.getSeed(), List.of(), false, dimension, source.getEnvironment(),
                source.getGenerator(), source.getBiomeProvider(),
                new SavedDataStorage(storage.getDimensionPath(original.dimension()),
                    original.getServer().getFixerUpper(), original.registryAccess()), data);
            // Stop chunk workers before closing their storage and unregistering the temporary world.
            registerResource(() -> world.getChunkSource().close(false));
        } catch (Exception e) {
            try {
                close();
            } catch (Exception closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    ServerLevel world() {
        return world;
    }

    void generate(PaperweightAdapter adapter, Region region, RegenOptions options) {
        generate(region, position -> {
            CompletableFuture<ChunkAccess> generated = new CompletableFuture<>();
            world.moonrise$getChunkTaskScheduler().scheduleChunkLoad(position.x(), position.z(),
                ChunkStatus.FEATURES, true, Priority.NORMAL, chunk -> {
                    if (chunk == null) {
                        generated.completeExceptionally(new IllegalStateException("Failed to generate chunk " + position));
                    } else {
                        generated.complete(chunk);
                    }
                });
            return generated;
        }, (clipboard, chunk, position) -> adapter.copyGeneratedBlock(clipboard, world, chunk, position, options));
    }
}
