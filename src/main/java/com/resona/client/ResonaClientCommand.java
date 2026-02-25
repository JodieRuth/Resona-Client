package com.resona.client;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;

public class ResonaClientCommand extends CommandBase {

    @Override
    public String getName() {
        return "resona";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/resona connect [port] | /resona chat [text]";
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return true;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args == null || args.length == 0) {
            sender.sendMessage(new TextComponentString(getUsage(sender)));
            return;
        }
        String sub = args[0];
        if ("connect".equalsIgnoreCase(sub)) {
            if (args.length >= 2) {
                try {
                    int port = Integer.parseInt(args[1]);
                    ResonaClientManager.get()
                        .connect(ResonaConfig.externalWsHost, port);
                    sender.sendMessage(
                        new TextComponentString("[Resona] connecting ws://" + ResonaConfig.externalWsHost + ":" + port));
                } catch (NumberFormatException e) {
                    sender.sendMessage(new TextComponentString("[Resona] invalid port"));
                }
            } else {
                ResonaClientManager.get()
                    .connectAutoProbe();
                sender.sendMessage(
                    new TextComponentString("[Resona] probing ports on " + ResonaConfig.externalWsHost));
            }
            return;
        }
        if ("chat".equalsIgnoreCase(sub)) {
            if (args.length >= 2) {
                String text = joinArgs(args, 1);
                if (!ResonaClientManager.get()
                    .sendQuestion(text)) {
                    sender.sendMessage(new TextComponentString("[Resona] not connected"));
                }
            } else {
                sender.sendMessage(new TextComponentString("[Resona] missing text"));
            }
            return;
        }
        sender.sendMessage(new TextComponentString(getUsage(sender)));
    }

    private String joinArgs(String[] args, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (i > start) {
                sb.append(' ');
            }
            sb.append(args[i]);
        }
        return sb.toString();
    }
}
