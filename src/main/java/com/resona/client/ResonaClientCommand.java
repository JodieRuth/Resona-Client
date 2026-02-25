package com.resona.client;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

public class ResonaClientCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "resona";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/resona connect [port] | /resona chat [text]";
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args == null || args.length == 0) {
            sender.addChatMessage(new ChatComponentText(getCommandUsage(sender)));
            return;
        }
        String sub = args[0];
        if ("connect".equalsIgnoreCase(sub)) {
            if (args.length >= 2) {
                try {
                    int port = Integer.parseInt(args[1]);
                    ResonaClientManager.get()
                        .connect(ResonaConfig.externalWsHost, port);
                    sender.addChatMessage(
                        new ChatComponentText("[Resona] connecting ws://" + ResonaConfig.externalWsHost + ":" + port));
                } catch (NumberFormatException e) {
                    sender.addChatMessage(new ChatComponentText("[Resona] invalid port"));
                }
            } else {
                ResonaClientManager.get()
                    .connectAutoProbe();
                sender
                    .addChatMessage(new ChatComponentText("[Resona] probing ports on " + ResonaConfig.externalWsHost));
            }
            return;
        }
        if ("chat".equalsIgnoreCase(sub)) {
            if (args.length >= 2) {
                String text = joinArgs(args, 1);
                if (!ResonaClientManager.get()
                    .sendQuestion(text)) {
                    sender.addChatMessage(new ChatComponentText("[Resona] not connected"));
                }
            } else {
                sender.addChatMessage(new ChatComponentText("[Resona] missing text"));
            }
            return;
        }
        sender.addChatMessage(new ChatComponentText(getCommandUsage(sender)));
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
