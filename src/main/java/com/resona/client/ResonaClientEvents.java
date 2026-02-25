package com.resona.client;

import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class ResonaClientEvents {

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ResonaClientManager.get()
                .tick();
        }
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (event.getWorld() == null || !event.getWorld().isRemote) {
            return;
        }
        ResonaClientManager.get()
            .connectOnWorldLoad();
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld() == null || !event.getWorld().isRemote) {
            return;
        }
        ResonaClientManager.get()
            .disconnect();
    }
}
