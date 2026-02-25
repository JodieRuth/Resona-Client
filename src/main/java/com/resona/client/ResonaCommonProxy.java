package com.resona.client;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

public class ResonaCommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        ResonaConfig.synchronizeConfiguration(event.getSuggestedConfigurationFile());
        ResonaClientMod.LOG.info("resona-client init");
    }

    public void init(FMLInitializationEvent event) {
        ResonaServerEvents serverEvents = new ResonaServerEvents();
        MinecraftForge.EVENT_BUS.register(serverEvents);
        FMLCommonHandler.instance()
            .bus()
            .register(serverEvents);
    }

    public void postInit(FMLPostInitializationEvent event) {}

    public void serverStarting(FMLServerStartingEvent event) {}
}
