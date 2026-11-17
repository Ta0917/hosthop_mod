package dev.hosthop;

import net.neoforged.fml.common.Mod;

/** Bootstrap only. This build must not be used to synchronize a world. */
@Mod(HostHop.MOD_ID)
public final class HostHop {
    public static final String MOD_ID = "hosthop";

    public HostHop() {
        if (net.neoforged.fml.loading.FMLEnvironment.getDist().isClient()) dev.hosthop.client.ModStartup.run();
    }
}
