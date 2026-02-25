package com.resona.client;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public class ResonaConfig {

    public static String externalWsHost = "127.0.0.1";
    public static int externalWsPort = 12345;
    public static boolean autoConnect = true;
    public static String mcpHost = "127.0.0.1";
    public static int mcpPort = 22345;

    public static void synchronizeConfiguration(File configFile) {
        Configuration configuration = new Configuration(configFile);

        externalWsHost = configuration.getString("externalWsHost", "resona", externalWsHost, "Python WebSocket host");
        externalWsPort = configuration
            .getInt("externalWsPort", "resona", externalWsPort, 1, 65535, "Python WebSocket port");
        autoConnect = configuration.getBoolean("autoConnect", "resona", autoConnect, "Auto connect on world load");
        mcpHost = configuration.getString("mcpHost", "resona", mcpHost, "Java MCP server host");
        mcpPort = configuration.getInt("mcpPort", "resona", mcpPort, 1, 65535, "Java MCP server port");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
