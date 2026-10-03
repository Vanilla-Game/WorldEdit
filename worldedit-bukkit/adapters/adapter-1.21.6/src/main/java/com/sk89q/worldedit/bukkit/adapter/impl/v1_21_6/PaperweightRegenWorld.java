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

package com.sk89q.worldedit.bukkit.adapter.impl.v1_21_6;

import ca.spottedleaf.concurrentutil.util.Priority;
import com.google.common.collect.ImmutableList;
import com.mojang.serialization.Lifecycle;
import com.sk89q.worldedit.bukkit.adapter.AbstractRegeneration;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.RegenOptions;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

import java.lang.reflect.Field;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

/** Creates the version-specific generation world; the common base owns its lifetime and snapshot. */
final class PaperweightRegenWorld extends AbstractRegeneration<ChunkAccess> {
    private final ServerLevel world;

    PaperweightRegenWorld(World source, RegenOptions options, Field worldsField) throws Exception {
        super(worldsField);
        try {
            ResourceKey<LevelStem> dimension = switch (source.getEnvironment()) {
                case NETHER -> LevelStem.NETHER;
                case THE_END -> LevelStem.END;
                default -> LevelStem.OVERWORLD;
            };
            var storage = registerResource(LevelStorageSource.createDefault(directory()).createAccess(name(), dimension));
            ServerLevel original = ((CraftWorld) source).getHandle();
            PrimaryLevelData levelProperties = (PrimaryLevelData) original.getServer().getWorldData().overworldData();
            WorldOptions originalOptions = levelProperties.worldGenOptions();
            long seed = options.getSeed().orElse(original.getSeed());
            WorldOptions worldOptions = options.getSeed().isPresent()
                ? originalOptions.withSeed(OptionalLong.of(seed)) : originalOptions;
            LevelSettings settings = new LevelSettings(name(), levelProperties.settings.gameType(),
                levelProperties.settings.hardcore(), levelProperties.settings.difficulty(),
                levelProperties.settings.allowCommands(), levelProperties.settings.gameRules(),
                levelProperties.settings.getDataConfiguration());
            @SuppressWarnings("deprecation")
            PrimaryLevelData.SpecialWorldProperty special = levelProperties.isFlatWorld()
                ? PrimaryLevelData.SpecialWorldProperty.FLAT
                : levelProperties.isDebugWorld() ? PrimaryLevelData.SpecialWorldProperty.DEBUG
                : PrimaryLevelData.SpecialWorldProperty.NONE;
            PrimaryLevelData data = new PrimaryLevelData(settings, worldOptions, special, Lifecycle.stable());
            world = new ServerLevel(original.getServer(), original.getServer().executor, storage, data,
                original.dimension(),
                new LevelStem(original.dimensionTypeRegistration(), original.getChunkSource().getGenerator()),
                new NoOpWorldLoadListener(),
                original.isDebug(), seed, ImmutableList.of(), false, original.getRandomSequences(),
                source.getEnvironment(), source.getGenerator(), source.getBiomeProvider());
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

    private static class NoOpWorldLoadListener implements ChunkProgressListener {
        @Override
        public void updateSpawnPos(ChunkPos spawnPos) {
        }

        @Override
        public void onStatusChange(ChunkPos pos, @org.jetbrains.annotations.Nullable ChunkStatus status) {
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }
    }
}
