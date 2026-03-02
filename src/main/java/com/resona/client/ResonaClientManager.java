package com.resona.client;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;

public class ResonaClientManager {

    private static final ResonaClientManager INSTANCE = new ResonaClientManager();

    private ResonaWsClient client;
    private volatile boolean connecting;
    private long lastConnectAttempt;
    private final Queue<Runnable> taskQueue = new ConcurrentLinkedQueue<Runnable>();

    public static ResonaClientManager get() {
        return INSTANCE;
    }

    public void tick() {
        Runnable task;
        while ((task = taskQueue.poll()) != null) {
            try {
                task.run();
            } catch (Exception e) {
                ResonaClientMod.LOG.error("client task error", e);
            }
        }

        if (ResonaConfig.autoConnect && (client == null || !client.isOpen()) && !connecting) {
            long now = System.currentTimeMillis();
            if (now - lastConnectAttempt > 5000) { 
                lastConnectAttempt = now;
                connectAutoProbe();
            }
        }
    }

    public synchronized void connect(final String host, final int port) {
        if (connecting) {
            return;
        }
        connecting = true;
        disconnect();
        new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    URI uri = new URI("ws://" + host + ":" + port);
                    client = new ResonaWsClient(uri, new ResonaWsClient.StatusListener() {

                        @Override
                        public void onStatus(String message) {
                            postChat(message);
                        }
                    });
                    boolean ok = client.connectBlocking(2, TimeUnit.SECONDS);
                    if (!ok) {
                        postChat("[Resona] connect failed: ws://" + host + ":" + port);
                        client = null;
                    }
                } catch (URISyntaxException e) {
                    postChat("[Resona] invalid ws url");
                } catch (InterruptedException e) {
                    postChat("[Resona] connect interrupted");
                    Thread.currentThread()
                        .interrupt();
                } finally {
                    connecting = false;
                }
            }
        }, "ResonaWsConnect").start();
    }

    public void connectAutoProbe() {
        if (connecting) {
            return;
        }
        final List<Integer> ports = new ArrayList<Integer>();
        ports.add(ResonaConfig.externalWsPort);
        for (int i = 0; i < 10; i++) {
            int p = 12345 + i;
            if (p != ResonaConfig.externalWsPort) {
                ports.add(p);
            }
        }
        connectProbeList(ports);
    }

    private void connectProbeList(final List<Integer> ports) {
        if (ports.isEmpty()) {
            return;
        }
        if (connecting) {
            return;
        }
        connecting = true;
        disconnect();
        new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    for (int port : ports) {
                        URI uri = new URI("ws://" + ResonaConfig.externalWsHost + ":" + port);
                        ResonaWsClient attempt = new ResonaWsClient(uri, new ResonaWsClient.StatusListener() {

                            @Override
                            public void onStatus(String message) {
                                postChat(message);
                            }
                        });
                        boolean ok = attempt.connectBlocking(1200, TimeUnit.MILLISECONDS);
                        if (ok) {
                            client = attempt;
                            postChat("[Resona] connected ws://" + ResonaConfig.externalWsHost + ":" + port);
                            return;
                        } else {
                            attempt.close();
                        }
                    }
                    postChat("[Resona] no available port");
                } catch (URISyntaxException e) {
                    postChat("[Resona] invalid ws url");
                } catch (InterruptedException e) {
                    Thread.currentThread()
                        .interrupt();
                } finally {
                    connecting = false;
                }
            }
        }, "ResonaWsProbe").start();
    }

    public boolean sendQuestion(String text) {
        ResonaWsClient current = client;
        if (current == null || !current.isOpen()) {
            return false;
        }
        return current.sendQuestion(text);
    }

    public synchronized void disconnect() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                ResonaClientMod.LOG.debug("ws close error", e);
            }
            client = null;
        }
    }

    public void connectOnWorldLoad() {
        if (ResonaConfig.autoConnect) {
            connectAutoProbe();
        }
    }

    public void postChatMessage(String message) {
        postChat(message);
    }

    private void postChat(final String message) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        runOnClientThread(new Runnable() {

            @Override
            public void run() {
                if (mc.thePlayer != null) {
                    mc.thePlayer.addChatMessage(new ChatComponentText(message));
                }
            }
        });
    }

    private void runOnClientThread(Runnable task) {
        taskQueue.add(task);
    }
}
