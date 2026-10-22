package com.ximal.arsplanariumpatch;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Persistent mapping between planarium names, their stable ids, their cell in the shared dimension
 * and their spawn point.
 *
 * <p>Stored in the overworld data storage as {@code ars_planarium_layout}.</p>
 */
public class PlanariumLayoutData extends SavedData {
    public static final String DATA_NAME = "ars_planarium_layout";
    private static final int VERSION = 1;

    private int stride = 0;
    private final Map<String, Entry> byName = new HashMap<>();
    private final Map<ResourceLocation, Entry> byId = new HashMap<>();
    private final Map<Long, Entry> byCell = new HashMap<>();

    public static SavedData.Factory<PlanariumLayoutData> factory() {
        return new SavedData.Factory<>(PlanariumLayoutData::new, PlanariumLayoutData::load, null);
    }

    public static PlanariumLayoutData from(MinecraftServer server) {
        return from(server.overworld());
    }

    public static PlanariumLayoutData from(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    public static PlanariumLayoutData load(CompoundTag tag, HolderLookup.Provider registries) {
        PlanariumLayoutData data = new PlanariumLayoutData();
        data.stride = tag.getInt("stride");
        ListTag list = tag.getList("entries", CompoundTag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            try {
                ResourceLocation id = ResourceLocation.parse(entryTag.getString("id"));
                Entry entry = new Entry(
                        entryTag.getString("name"),
                        id,
                        entryTag.getLong("cell"),
                        entryTag.getInt("spawnX"),
                        entryTag.getInt("spawnY"),
                        entryTag.getInt("spawnZ"),
                        entryTag.getBoolean("legacy"));
                data.byName.put(entry.name(), entry);
                data.byId.put(entry.id(), entry);
                data.byCell.put(entry.cell(), entry);
            } catch (Exception e) {
                ArsPlanariumPatch.LOGGER.warn("Skipping invalid planarium layout entry: {}", entryTag, e);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", VERSION);
        tag.putInt("stride", stride);
        ListTag list = new ListTag();
        for (Entry entry : byName.values()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("name", entry.name());
            entryTag.putString("id", entry.id().toString());
            entryTag.putLong("cell", entry.cell());
            entryTag.putInt("spawnX", entry.spawnX());
            entryTag.putInt("spawnY", entry.spawnY());
            entryTag.putInt("spawnZ", entry.spawnZ());
            entryTag.putBoolean("legacy", entry.legacy());
            list.add(entryTag);
        }
        tag.put("entries", list);
        return tag;
    }

    public int stride() {
        return stride;
    }

    /**
     * Stores the world's stride. Never changes once set.
     */
    public void ensureStride(int newStride) {
        if (newStride <= 0) {
            return;
        }
        if (stride <= 0) {
            stride = newStride;
            setDirty();
        }
    }

    public Collection<Entry> entries() {
        return Collections.unmodifiableCollection(byName.values());
    }

    @Nullable
    public Entry byName(String name) {
        return byName.get(name);
    }

    @Nullable
    public Entry byId(ResourceLocation id) {
        return byId.get(id);
    }

    @Nullable
    public Entry byCell(long cell) {
        return byCell.get(cell);
    }

    /**
     * Returns the entry for a planarium, allocating a deterministic cell if this is the first
     * time we see it.
     */
    public Entry getOrCreate(String name, ResourceLocation id) {
        if (stride <= 0) {
            throw new IllegalStateException("Planarium layout stride was not initialized");
        }

        Entry entry = byName.get(name);
        if (entry != null) {
            return entry;
        }

        entry = byId.get(id);
        if (entry != null) {
            // Data inconsistency: the id exists but under a different name. Keep the cell and id.
            byName.put(name, entry);
            setDirty();
            return entry;
        }

        long cell = allocateCell(name);
        entry = new Entry(name, id, cell,
                PlanariumDimension.DEFAULT_SPAWN.getX(),
                PlanariumDimension.DEFAULT_SPAWN.getY(),
                PlanariumDimension.DEFAULT_SPAWN.getZ(),
                false);
        byName.put(name, entry);
        byId.put(id, entry);
        byCell.put(cell, entry);
        setDirty();
        ArsPlanariumPatch.LOGGER.info("Allocated planarium cell {} for '{}' (id {})", cell, name, id);
        return entry;
    }

    private long allocateCell(String name) {
        PlanariumCell hashed = PlanariumCell.unpack(PlanariumCell.hashCell(name));
        int x = hashed.x();
        int z = hashed.z();
        for (int attempt = 0; attempt < 1_000_000; attempt++) {
            long packed = PlanariumCell.pack(x, z);
            if (!byCell.containsKey(packed)) {
                return packed;
            }
            if (++x > PlanariumCell.HASH_MASK) {
                x = 0;
                if (++z > PlanariumCell.HASH_MASK) {
                    z = 0;
                }
            }
        }
        throw new IllegalStateException("Unable to allocate a planarium cell for '" + name + "'");
    }

    public Entry setSpawn(Entry entry, BlockPos localSpawn) {
        Entry updated = entry.withSpawn(localSpawn);
        replace(entry, updated);
        return updated;
    }

    public Entry setLegacy(Entry entry, boolean legacy) {
        Entry updated = entry.withLegacy(legacy);
        replace(entry, updated);
        return updated;
    }

    private void replace(Entry oldEntry, Entry newEntry) {
        byName.put(newEntry.name(), newEntry);
        byId.put(newEntry.id(), newEntry);
        byCell.put(newEntry.cell(), newEntry);
        setDirty();
    }

    /**
     * One planarium: stable id, cell index (packed), local spawn and legacy-migration state.
     */
    public record Entry(String name, ResourceLocation id, long cell,
                        int spawnX, int spawnY, int spawnZ, boolean legacy) {

        public BlockPos spawnLocal() {
            return new BlockPos(spawnX, spawnY, spawnZ);
        }

        public BlockPos spawnAbsolute(int stride) {
            return PlanariumDimension.localToAbsolute(spawnLocal(), PlanariumDimension.base(cell, stride));
        }

        public ResourceKey<Level> dimensionKey() {
            return ResourceKey.create(Registries.DIMENSION, id);
        }

        public Entry withSpawn(BlockPos spawn) {
            return new Entry(name, id, cell, spawn.getX(), spawn.getY(), spawn.getZ(), legacy);
        }

        public Entry withLegacy(boolean newLegacy) {
            return new Entry(name, id, cell, spawnX, spawnY, spawnZ, newLegacy);
        }
    }
}
