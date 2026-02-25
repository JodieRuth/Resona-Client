package com.resona.client;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.block.Block;
import net.minecraft.command.ICommandManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.WorldServer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

public class ResonaMcpServer {

    private static final ResonaMcpServer INSTANCE = new ResonaMcpServer();
    private static final Gson GSON = new Gson();

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final Queue<Runnable> taskQueue = new ConcurrentLinkedQueue<Runnable>();
    private volatile int boundPort = -1;
    private long lastBeaconAt = 0L;
    private final long beaconIntervalMs = 2000L;

    public static ResonaMcpServer get() {
        return INSTANCE;
    }

    public void tick() {
        Runnable task;
        while ((task = taskQueue.poll()) != null) {
            try {
                task.run();
            } catch (Exception e) {
                ResonaClientMod.LOG.error("mcp server task error", e);
            }
        }
        if (running && boundPort > 0) {
            long now = System.currentTimeMillis();
            if (now - lastBeaconAt >= beaconIntervalMs) {
                lastBeaconAt = now;
                broadcastBeacon();
                writeBeaconFile();
            }
        }
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        acceptThread = new Thread(new Runnable() {

            @Override
            public void run() {
                acceptLoop();
            }
        }, "ResonaMcpAccept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                ResonaClientMod.LOG.warn("mcp server close error", e);
            }
        }
        serverSocket = null;
        boundPort = -1;
    }

    private void acceptLoop() {
        int startPort = ResonaConfig.mcpPort;
        int endPort = startPort + 10;
        boolean success = false;

        for (int port = startPort; port <= endPort; port++) {
            try {
                serverSocket = new ServerSocket(port, 50, InetAddress.getByName(ResonaConfig.mcpHost));
                boundPort = serverSocket.getLocalPort();
                ResonaClientMod.LOG.info("[MinecraftMCP] Server listening on " + ResonaConfig.mcpHost + ":" + port);
                success = true;
                break;
            } catch (IOException e) {
                ResonaClientMod.LOG.warn("[MinecraftMCP] Port " + port + " is busy, trying next...");
            }
        }

        if (!success) {
            ResonaClientMod.LOG.error(
                "[MinecraftMCP] Failed to start server: No available ports in range " + startPort + "-" + endPort);
            running = false;
            return;
        }

        while (running) {
            try {
                Socket socket = serverSocket.accept();
                Thread clientThread = new Thread(new ClientHandler(socket), "ResonaMcpClient");
                clientThread.setDaemon(true);
                clientThread.start();
            } catch (IOException e) {
                if (running) {
                    ResonaClientMod.LOG.warn("mcp accept error", e);
                }
            }
        }
    }

    private void broadcastBeacon() {
        if (boundPort <= 0) {
            return;
        }
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            String host = ResonaConfig.mcpHost;
            if ("0.0.0.0".equals(host) || "::".equals(host)) {
                host = "127.0.0.1";
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("type", "resona_mcp");
            payload.addProperty("host", host);
            payload.addProperty("port", boundPort);
            byte[] data = GSON.toJson(payload)
                .getBytes("UTF-8");
            DatagramPacket packet = new DatagramPacket(
                data,
                data.length,
                InetAddress.getByName("255.255.255.255"),
                50124);
            socket.send(packet);
        } catch (Exception e) {
            ResonaClientMod.LOG.warn("[MinecraftMCP] beacon send failed", e);
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    private void writeBeaconFile() {
        if (boundPort <= 0) {
            return;
        }
        File configDir = resolveConfigDir();
        if (configDir == null) {
            return;
        }
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        File beaconFile = new File(configDir, "resona-mcp.json");
        try {
            String host = ResonaConfig.mcpHost;
            if ("0.0.0.0".equals(host) || "::".equals(host)) {
                host = "127.0.0.1";
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("type", "resona_mcp");
            payload.addProperty("host", host);
            payload.addProperty("port", boundPort);
            payload.addProperty("updated_at", System.currentTimeMillis());
            FileOutputStream out = new FileOutputStream(beaconFile, false);
            try {
                out.write(
                    GSON.toJson(payload)
                        .getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Exception e) {
            ResonaClientMod.LOG.warn("[MinecraftMCP] beacon file write failed", e);
        }
    }

    private File resolveConfigDir() {
        MinecraftServer server = getServer();
        if (server != null) {
            try {
                return server.getFile("config");
            } catch (Exception e) {}
        }
        return new File("config");
    }

    private class ClientHandler implements Runnable {

        private final Socket socket;

        private ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
                String line;
                while (running && (line = in.readLine()) != null) {
                    JsonObject response = handleLine(line);
                    out.write(GSON.toJson(response));
                    out.newLine();
                    out.flush();
                }
            } catch (IOException e) {
                ResonaClientMod.LOG.warn("mcp client error", e);
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    ResonaClientMod.LOG.warn("mcp socket close error", e);
                }
            }
        }
    }

    private JsonObject handleLine(String line) {
        JsonObject response = new JsonObject();
        JsonObject request = null;
        try {
            request = GSON.fromJson(line, JsonObject.class);
        } catch (Exception e) {
            response.addProperty("status", "error");
            response.addProperty("message", "invalid json");
            attachServerInfo(response);
            return response;
        }
        if (request != null && request.has("id")) {
            response.add("id", request.get("id"));
        }
        final String action = getString(request, "action");
        response.addProperty("action", action);
        final JsonObject finalRequest = request;
        try {
            JsonObject data = runOnServer(new Callable<JsonObject>() {

                @Override
                public JsonObject call() {
                    return handleAction(action, finalRequest);
                }
            });
            response.addProperty("status", "ok");
            response.add("data", data == null ? JsonNull.INSTANCE : data);
        } catch (Exception e) {
            response.addProperty("status", "error");
            response.addProperty("message", e.getMessage() == null ? "error" : e.getMessage());
        }
        attachServerInfo(response);
        return response;
    }

    public JsonObject handleWsRequest(final JsonObject request) {
        JsonObject response = new JsonObject();
        if (request == null) {
            response.addProperty("status", "error");
            response.addProperty("message", "invalid json");
            attachServerInfo(response);
            return response;
        }
        if (request.has("id")) {
            response.add("id", request.get("id"));
        }
        final String action = getString(request, "action");
        response.addProperty("action", action);
        final JsonObject finalRequest = request;
        try {
            JsonObject data = runOnServer(new Callable<JsonObject>() {

                @Override
                public JsonObject call() {
                    return handleAction(action, finalRequest);
                }
            });
            response.addProperty("status", "ok");
            response.add("data", data == null ? JsonNull.INSTANCE : data);
        } catch (Exception e) {
            response.addProperty("status", "error");
            response.addProperty("message", e.getMessage() == null ? "error" : e.getMessage());
        }
        attachServerInfo(response);
        return response;
    }

    private JsonObject handleAction(String action, JsonObject request) {
        if ("list_players".equals(action)) {
            return handleListPlayers();
        }
        if ("nearby_tile_entities".equals(action)) {
            return handleNearbyTileEntities(request);
        }
        if ("ray_trace".equals(action)) {
            return handleRayTrace(request);
        }
        if ("player_inventory".equals(action)) {
            return handlePlayerInventory(request);
        }
        if ("block_info".equals(action)) {
            return handleBlockInfo(request);
        }
        if ("search_items".equals(action)) {
            return handleSearchItems(request);
        }
        if ("search_containers_with_item".equals(action)) {
            return handleSearchContainers(request);
        }
        if ("explode".equals(action)) {
            return handleExplode(request);
        }
        if ("run_command".equals(action)) {
            return handleRunCommand(request);
        }
        if ("lookup_item".equals(action)) {
            return handleLookupItem(request);
        }
        if ("exit_game".equals(action)) {
            return handleExitGame();
        }
        JsonObject data = new JsonObject();
        data.addProperty("message", "unknown action");
        return data;
    }

    private JsonObject handleListPlayers() {
        MinecraftServer server = getServer();
        JsonArray arr = new JsonArray();
        if (server != null) {
            for (EntityPlayerMP p : server.getPlayerList().getPlayers()) {
                JsonObject item = new JsonObject();
                item.addProperty(
                    "uuid",
                    p.getUniqueID()
                        .toString());
                item.addProperty("name", p.getName());
                item.addProperty("x", p.posX);
                item.addProperty("y", p.posY);
                item.addProperty("z", p.posZ);
                item.addProperty("dimension", p.dimension);
                arr.add(item);
            }
        }
        JsonObject data = new JsonObject();
        data.add("players", arr);
        return data;
    }

    private JsonObject handleNearbyTileEntities(JsonObject request) {
        TargetPos target = resolveTarget(request);
        int radius = clampInt(getInt(request, "radius", 10), 1, 15);
        JsonArray arr = new JsonArray();
        if (target.world != null) {
            int max = 25;
            double r2 = radius * radius;
            for (TileEntity te : target.world.loadedTileEntityList) {
                BlockPos pos = te.getPos();
                double dx = pos.getX() - target.x;
                double dy = pos.getY() - target.y;
                double dz = pos.getZ() - target.z;
                if (dx * dx + dy * dy + dz * dz <= r2) {
                    JsonObject item = tileEntityInfo(target.world, te);
                    arr.add(item);
                    if (arr.size() >= max) {
                        break;
                    }
                }
            }
        }
        JsonObject data = new JsonObject();
        data.add("tiles", arr);
        return data;
    }

    private JsonObject handleRayTrace(JsonObject request) {
        EntityPlayerMP player = findPlayer(getString(request, "player"));
        double distance = clampDouble(getDouble(request, "distance", 50), 1, 50);
        JsonObject data = new JsonObject();
        if (player == null) {
            data.addProperty("message", "player not found");
            return data;
        }
        RayTraceResult hit = player.rayTrace(distance, 1.0f);
        if (hit == null) {
            data.add("hit", JsonNull.INSTANCE);
            return data;
        }
        if (hit.typeOfHit == RayTraceResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            WorldServer world = player.getServerWorld();
            Block block = world.getBlockState(pos)
                .getBlock();
            JsonObject blockInfo = blockInfo(world, pos, block);
            data.add("hit", blockInfo);
        } else if (hit.typeOfHit == RayTraceResult.Type.ENTITY) {
            Entity entity = hit.entityHit;
            JsonObject ent = entityInfo(entity);
            data.add("hit", ent);
        } else {
            data.add("hit", JsonNull.INSTANCE);
        }
        return data;
    }

    private JsonObject handlePlayerInventory(JsonObject request) {
        EntityPlayerMP player = findPlayer(getString(request, "player"));
        JsonArray items = new JsonArray();
        if (player != null) {
            for (int i = 0; i < player.inventory.mainInventory.size(); i++) {
                ItemStack stack = player.inventory.mainInventory.get(i);
                if (!stack.isEmpty()) {
                    items.add(itemInfo(stack, i));
                }
            }
            for (int i = 0; i < player.inventory.armorInventory.size(); i++) {
                ItemStack stack = player.inventory.armorInventory.get(i);
                if (!stack.isEmpty()) {
                    items.add(itemInfo(stack, 36 + i));
                }
            }
        }
        JsonObject data = new JsonObject();
        data.add("items", items);
        return data;
    }

    private JsonObject handleBlockInfo(JsonObject request) {
        int x = getInt(request, "x", 0);
        int y = getInt(request, "y", 0);
        int z = getInt(request, "z", 0);
        int dim = getInt(request, "dimension", 0);
        WorldServer world = getWorld(dim);
        JsonObject data = new JsonObject();
        if (world != null) {
            BlockPos pos = new BlockPos(x, y, z);
            Block block = world.getBlockState(pos)
                .getBlock();
            JsonObject info = blockInfo(world, pos, block);
            data.add("block", info);
        } else {
            data.add("block", JsonNull.INSTANCE);
        }
        return data;
    }

    private JsonObject handleSearchItems(JsonObject request) {
        String query = getString(request, "query");
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        JsonArray arr = new JsonArray();
        for (ResourceLocation key : ForgeRegistries.ITEMS.getKeys()) {
            if (key == null) {
                continue;
            }
            Item item = ForgeRegistries.ITEMS.getValue(key);
            if (item == null) {
                continue;
            }
            String name = "";
            String unloc = "";
            try {
                name = getItemDisplayName(item);
            } catch (Exception e) {
                name = "";
            }
            try {
                unloc = item.getTranslationKey();
            } catch (Exception e) {
                unloc = "";
            }
            String keyStr = key.toString();
            if (containsIgnoreCase(keyStr, q) || containsIgnoreCase(name, q) || containsIgnoreCase(unloc, q)) {
                JsonObject obj = new JsonObject();
                obj.addProperty("key", keyStr);
                obj.addProperty("name", name);
                arr.add(obj);
            }
        }
        JsonObject data = new JsonObject();
        data.add("items", arr);
        return data;
    }

    private JsonObject handleSearchContainers(JsonObject request) {
        EntityPlayerMP player = findPlayer(getString(request, "player"));
        int radius = clampInt(getInt(request, "radius", 15), 1, 15);
        String query = getString(request, "query");
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        JsonArray arr = new JsonArray();
        if (player != null) {
            int max = 25;
            double r2 = radius * radius;
            WorldServer world = player.getServerWorld();
            for (TileEntity te : world.loadedTileEntityList) {
                if (!(te instanceof IInventory)) {
                    continue;
                }
                BlockPos pos = te.getPos();
                double dx = pos.getX() - player.posX;
                double dy = pos.getY() - player.posY;
                double dz = pos.getZ() - player.posZ;
                if (dx * dx + dy * dy + dz * dz > r2) {
                    continue;
                }
                IInventory inv = (IInventory) te;
                JsonArray matches = new JsonArray();
                for (int i = 0; i < inv.getSizeInventory(); i++) {
                    ItemStack stack = inv.getStackInSlot(i);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    if (itemMatches(stack, q)) {
                        JsonObject item = itemInfo(stack, i);
                        matches.add(item);
                    }
                }
                if (matches.size() > 0) {
                    JsonObject container = tileEntityInfo(world, te);
                    container.add("items", matches);
                    arr.add(container);
                    if (arr.size() >= max) {
                        break;
                    }
                }
            }
        }
        JsonObject data = new JsonObject();
        data.add("containers", arr);
        return data;
    }

    private JsonObject handleExplode(JsonObject request) {
        int x = getInt(request, "x", 0);
        int y = getInt(request, "y", 0);
        int z = getInt(request, "z", 0);
        int dim = getInt(request, "dimension", 0);
        float strength = (float) clampDouble(getDouble(request, "strength", 2.0), 0.1, 100.0);
        WorldServer world = getWorld(dim);
        JsonObject data = new JsonObject();
        if (world != null) {
            world.newExplosion(null, x, y, z, strength, true, true);
            data.addProperty("success", true);
        } else {
            data.addProperty("success", false);
        }
        return data;
    }

    private JsonObject handleRunCommand(JsonObject request) {
        String command = getString(request, "command");
        JsonObject data = new JsonObject();
        MinecraftServer server = getServer();
        if (server != null && command != null
            && command.trim()
                .length() > 0) {
            ICommandManager manager = server.getCommandManager();
            int result = manager.executeCommand(server, command);
            data.addProperty("result", result);
        } else {
            data.addProperty("result", -1);
        }
        return data;
    }

    private JsonObject handleLookupItem(JsonObject request) {
        String mcVersion = getString(request, "mc_version");
        if (mcVersion.isEmpty()) {
            mcVersion = "1.12.2";
        }

        JsonArray itemsToLookup = new JsonArray();
        if (request.has("items") && request.get("items")
            .isJsonArray()) {
            itemsToLookup = request.get("items")
                .getAsJsonArray();
        } else if (request.has("identifier")) {
            JsonObject single = new JsonObject();
            single.addProperty("identifier", getString(request, "identifier"));
            single.addProperty("damage", getInt(request, "damage", 0));
            itemsToLookup.add(single);
        }

        JsonArray results = new JsonArray();
        for (int i = 0; i < itemsToLookup.size(); i++) {
            JsonElement el = itemsToLookup.get(i);
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject itemReq = el.getAsJsonObject();
            String identifier = getString(itemReq, "identifier");
            int damage = getInt(itemReq, "damage", 0);

            Item item = null;
            int resolvedDamage = damage;

            if (identifier != null && !identifier.trim()
                .isEmpty()) {
                String trimmed = identifier.trim();

                if ("1.7.10".equals(mcVersion)) {
                    try {
                        int id = Integer.parseInt(trimmed);
                        item = Item.getItemById(id);
                    } catch (NumberFormatException e) {
                        item = Item.getByNameOrId(trimmed);
                    }
                } else if ("1.12.2".equals(mcVersion)) {
                    int lastColon = trimmed.lastIndexOf(':');
                    int firstColon = trimmed.indexOf(':');
                    if (lastColon > firstColon && lastColon > -1) {
                        String tail = trimmed.substring(lastColon + 1);
                        try {
                            resolvedDamage = Integer.parseInt(tail);
                            item = Item.getByNameOrId(trimmed.substring(0, lastColon));
                        } catch (NumberFormatException e) {
                            item = Item.getByNameOrId(trimmed);
                        }
                    } else {
                        item = Item.getByNameOrId(trimmed);
                    }
                } else {
                    item = Item.getByNameOrId(trimmed);
                }
            }

            if (item != null) {
                ItemStack stack = new ItemStack(item, 1, resolvedDamage);
                JsonObject obj = new JsonObject();
                obj.addProperty("key", item.getRegistryName().toString());
                obj.addProperty("unlocalized", item.getTranslationKey());
                obj.addProperty("name", stack.getDisplayName());
                obj.addProperty("damage", resolvedDamage);
                obj.addProperty("mod", getItemModId(item));
                results.add(obj);
            }
        }

        JsonObject data = new JsonObject();
        data.add("items", results);
        return data;
    }

    private JsonObject handleExitGame() {
        new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread()
                        .interrupt();
                }
                FMLCommonHandler.instance()
                    .exitJava(0, false);
            }
        }, "ResonaExit").start();
        JsonObject data = new JsonObject();
        data.addProperty("scheduled", true);
        data.addProperty("method", "safe_exit");
        return data;
    }

    private TargetPos resolveTarget(JsonObject request) {
        EntityPlayerMP player = findPlayer(getString(request, "player"));
        if (player != null) {
            return new TargetPos(player.getServerWorld(), player.posX, player.posY, player.posZ);
        }
        int x = getInt(request, "x", 0);
        int y = getInt(request, "y", 0);
        int z = getInt(request, "z", 0);
        int dim = getInt(request, "dimension", 0);
        return new TargetPos(getWorld(dim), x, y, z);
    }

    private JsonObject tileEntityInfo(WorldServer world, TileEntity te) {
        BlockPos pos = te.getPos();
        Block block = world.getBlockState(pos)
            .getBlock();
        JsonObject obj = blockInfo(world, pos, block);
        NBTTagCompound nbt = new NBTTagCompound();
        te.writeToNBT(nbt);
        obj.addProperty("nbt", nbt.toString());
        if (te instanceof IInventory) {
            IInventory inv = (IInventory) te;
            JsonArray items = new JsonArray();
            for (int i = 0; i < inv.getSizeInventory(); i++) {
                ItemStack stack = inv.getStackInSlot(i);
                if (!stack.isEmpty()) {
                    items.add(itemInfo(stack, i));
                }
            }
            obj.add("items", items);
        }
        return obj;
    }

    private JsonObject blockInfo(WorldServer world, BlockPos pos, Block block) {
        JsonObject obj = new JsonObject();
        if (block == null) {
            obj.add("block", JsonNull.INSTANCE);
            return obj;
        }
        obj.addProperty("x", pos.getX());
        obj.addProperty("y", pos.getY());
        obj.addProperty("z", pos.getZ());
        obj.addProperty("key", block.getTranslationKey());
        obj.addProperty("name", block.getLocalizedName());
        obj.addProperty("mod", getBlockModId(block));
        TileEntity te = world.getTileEntity(pos);
        if (te != null) {
            NBTTagCompound nbt = new NBTTagCompound();
            te.writeToNBT(nbt);
            obj.addProperty("nbt", nbt.toString());
        } else {
            obj.addProperty("nbt", "");
        }
        return obj;
    }

    private JsonObject entityInfo(Entity entity) {
        JsonObject obj = new JsonObject();
        String entityId = EntityList.getEntityString(entity);
        obj.addProperty("type", entityId);
        obj.addProperty("name", entity.getName());
        obj.addProperty(
            "uuid",
            entity.getUniqueID()
                .toString());
        obj.addProperty("x", entity.posX);
        obj.addProperty("y", entity.posY);
        obj.addProperty("z", entity.posZ);
        if (entity instanceof EntityLivingBase) {
            obj.addProperty("health", ((EntityLivingBase) entity).getHealth());
        }
        if (entityId != null && entityId.contains(":")) {
            obj.addProperty("mod", entityId.split(":")[0]);
        } else {
            obj.addProperty("mod", "minecraft");
        }
        return obj;
    }

    private JsonObject itemInfo(ItemStack stack, int slot) {
        JsonObject obj = new JsonObject();
        Item item = stack.getItem();
        String key = item.getRegistryName().toString();
        obj.addProperty("key", key);
        obj.addProperty("name", stack.getDisplayName());
        obj.addProperty("count", stack.getCount());
        obj.addProperty("slot", slot);
        obj.addProperty("mod", getItemModId(item));
        NBTTagCompound nbt = new NBTTagCompound();
        stack.writeToNBT(nbt);
        obj.addProperty("nbt", nbt.toString());
        return obj;
    }

    private String getItemKey(Item item) {
        if (item == null) {
            return "";
        }
        ResourceLocation key = item.getRegistryName();
        return key == null ? item.getTranslationKey() : key.toString();
    }

    private String getItemModId(Item item) {
        ResourceLocation key = item.getRegistryName();
        return key == null ? "minecraft" : key.getNamespace();
    }

    private String getBlockModId(Block block) {
        ResourceLocation key = block.getRegistryName();
        return key == null ? "minecraft" : key.getNamespace();
    }

    private String getItemDisplayName(Item item) {
        if (item == null) {
            return "";
        }
        ItemStack stack = new ItemStack(item);
        return stack.getDisplayName();
    }

    private boolean itemMatches(ItemStack stack, String query) {
        if (stack.isEmpty()) {
            return false;
        }
        String key = getItemKey(stack.getItem());
        String name = stack.getDisplayName();
        return containsIgnoreCase(key, query) || containsIgnoreCase(name, query)
            || containsIgnoreCase(
                stack.getItem()
                    .getTranslationKey(),
                query);
    }

    private boolean containsIgnoreCase(String text, String query) {
        if (query == null || query.isEmpty()) {
            return false;
        }
        if (text == null) {
            return false;
        }
        return text.toLowerCase(Locale.ROOT)
            .contains(query);
    }

    private String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) {
            return "";
        }
        JsonElement el = obj.get(key);
        return el.isJsonNull() ? "" : el.getAsString();
    }

    private int getInt(JsonObject obj, String key, int def) {
        if (obj == null || !obj.has(key)) {
            return def;
        }
        try {
            return obj.get(key)
                .getAsInt();
        } catch (Exception e) {
            return def;
        }
    }

    private double getDouble(JsonObject obj, String key, double def) {
        if (obj == null || !obj.has(key)) {
            return def;
        }
        try {
            return obj.get(key)
                .getAsDouble();
        } catch (Exception e) {
            return def;
        }
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private EntityPlayerMP findPlayer(String nameOrUuid) {
        MinecraftServer server = getServer();
        if (server == null || nameOrUuid == null || nameOrUuid.isEmpty()) {
            return null;
        }
        for (EntityPlayerMP p : server.getPlayerList().getPlayers()) {
            if (p.getName()
                .equalsIgnoreCase(nameOrUuid)) {
                return p;
            }
            if (p.getUniqueID()
                .toString()
                .equalsIgnoreCase(nameOrUuid)) {
                return p;
            }
        }
        return null;
    }

    private WorldServer getWorld(int dimension) {
        MinecraftServer server = getServer();
        if (server == null) {
            return null;
        }
        return server.getWorld(dimension);
    }

    private MinecraftServer getServer() {
        return FMLCommonHandler.instance()
            .getMinecraftServerInstance();
    }

    private void attachServerInfo(JsonObject response) {
        MinecraftServer server = getServer();
        if (server != null) {
            response.addProperty("mc_version", server.getMinecraftVersion());
        } else {
            response.addProperty("mc_version", "unknown");
        }
        response.addProperty("window_title", getWindowTitle());
    }

    private String getWindowTitle() {
        if (!FMLCommonHandler.instance()
            .getSide()
            .isClient()) {
            return "Minecraft";
        }
        try {
            return org.lwjgl.opengl.Display.getTitle();
        } catch (Exception e) {
            return "Minecraft";
        }
    }

    private <T> T runOnServer(Callable<T> task) {
        MinecraftServer server = getServer();
        if (server == null) {
            return null;
        }
        final AtomicReference<T> result = new AtomicReference<T>();
        final AtomicReference<Exception> error = new AtomicReference<Exception>();
        final CountDownLatch latch = new CountDownLatch(1);
        Runnable runner = new Runnable() {

            @Override
            public void run() {
                try {
                    result.set(task.call());
                } catch (Exception e) {
                    error.set(e);
                } finally {
                    latch.countDown();
                }
            }
        };
        taskQueue.add(runner);
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
        }
        if (error.get() != null) {
            throw new RuntimeException(error.get());
        }
        return result.get();
    }

    private static class TargetPos {

        private final WorldServer world;
        private final double x;
        private final double y;
        private final double z;

        private TargetPos(WorldServer world, double x, double y, double z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
