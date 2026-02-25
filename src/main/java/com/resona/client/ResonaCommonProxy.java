package com.resona.client;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

public class ResonaCommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        ResonaConfig.synchronizeConfiguration(event.getSuggestedConfigurationFile());
        ResonaClientMod.LOG.info("resona-client init");
    }

    public void init(FMLInitializationEvent event) {
        ResonaServerEvents serverEvents = new ResonaServerEvents();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(serverEvents);
        cpw.mods.fml.common.FMLCommonHandler.instance()
            .bus()
            .register(serverEvents);
    }

    public void postInit(FMLPostInitializationEvent event) {}

    public void serverStarting(FMLServerStartingEvent event) {}
}
