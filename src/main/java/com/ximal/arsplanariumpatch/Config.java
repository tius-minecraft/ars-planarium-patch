package com.ximal.arsplanariumpatch;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    public static final ModConfigSpec SERVER_SPEC;
    public static final ModConfigSpec.IntValue CELL_STRIDE;
    public static final ModConfigSpec.IntValue REFRESH_DEBOUNCE_TICKS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment(
                "Distance between the origins of two planarium cells, in blocks.",
                "Must be a multiple of 16. Values are rounded down to the nearest multiple of 16.",
                "The value is written into the world the first time a planarium is created;",
                "changing it later only affects new worlds (the stored value is kept).")
                .push("planarium");

        CELL_STRIDE = builder
                .comment("Cell stride in blocks (default 512 = 32 chunks, cells are 34 blocks wide).")
                .defineInRange("cellStride", 512, 64, 4096);

        REFRESH_DEBOUNCE_TICKS = builder
                .comment(
                        "How many ticks to wait after the last block change inside a planarium before",
                        "refreshing its miniature. This coalesces rapid building into one snapshot instead",
                        "of re-reading the cell (and forcing its chunks) on every block. 100 ticks = 5 s.",
                        "Set to 0 to refresh on every change. Leaving the planarium always refreshes immediately.")
                .defineInRange("refreshDebounceTicks", 100, 0, 1200);

        builder.pop();
        SERVER_SPEC = builder.build();
    }

    private Config() {
    }
}
