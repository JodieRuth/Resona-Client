package com.resona.client;

import net.minecraftforge.client.ClientCommandHandler;

import cpw.mods.fml.common.event.FMLInitializationEvent;

public class ResonaClientProxy extends ResonaCommonProxy {

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        ClientCommandHandler.instance.registerCommand(new ResonaClientCommand());
        ResonaClientEvents clientEvents = new ResonaClientEvents();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(clientEvents);
        cpw.mods.fml.common.FMLCommonHandler.instance()
            .bus()
            .register(clientEvents);
    }
}
