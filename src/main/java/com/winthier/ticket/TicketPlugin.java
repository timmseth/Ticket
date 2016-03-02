package com.winthier.ticket;

import com.avaje.ebean.Expr;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.persistence.PersistenceException;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class TicketPlugin extends JavaPlugin implements Listener {
    private ConfigurationSection usageMessages;
    private final ReminderTask reminderTask = new ReminderTask(this);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        try {
            getDatabase().find(Ticket.class).findRowCount();
            getDatabase().find(Comment.class).findRowCount();
        } catch (PersistenceException ex) {
            System.out.println("Installing database for " + getDescription().getName() + " due to first time usage");
            installDDL();
        }
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("ticket").setUsage(Util.format(getCommand("ticket").getUsage()));
        reminderTask.start();
    }

    @Override
    public void onDisable() {
        reminderTask.stop();
    }

    private String getUsageMessage(String key) {
        if (usageMessages == null) {
            usageMessages = YamlConfiguration.loadConfiguration(getResource("usage.yml"));
        }
        return usageMessages.getString(key);
    }

    private void sendUsageMessage(CommandSender sender, String key) {
        if (sender.hasPermission("ticket." + key)) Util.sendMessage(sender, "&3Usage: " + getUsageMessage(key));
    }

    private void sendUsageMessage(CommandSender sender) {
        Util.sendMessage(sender, "&3Ticket usage:");
        for (String key : Arrays.asList("new", "view", "comment", "close", "reopen", "port", "assign", "reload")) {
            if (sender.hasPermission("ticket." + key)) Util.sendMessage(sender, " " + getUsageMessage(key));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String args[]) {
        try {
            if (args.length == 0) {
                if (sender.hasPermission("ticket.moderation")) {
                    listOpenTickets(sender);
                }
                listOwnedTickets(sender);
            } else if ("New".equalsIgnoreCase(args[0])) {
                newTicket(sender, Arrays.<String>copyOfRange(args, 1, args.length));
            } else if ("View".equalsIgnoreCase(args[0])) {
                viewTicket(sender, Arrays.<String>copyOfRange(args, 1, args.length));
            } else if ("Comment".equalsIgnoreCase(args[0])) {
                commentTicket(sender, Arrays.<String>copyOfRange(args, 1, args.length));
            } else if ("Close".equalsIgnoreCase(args[0])) {
                closeTicket(sender, Arrays.<String>copyOfRange(args, 1, args.length));
            } else if ("Reopen".equalsIgnoreCase(args[0])) {
                reopenTicket(sender, Arrays.<String>copyOfRange(args, 1, args.length));
            } else if ("Port".equalsIgnoreCase(args[0])) {
                portTicket(sender, Arrays.<String>copyOfRange(args, 1, args.length));
            } else if ("Assign".equalsIgnoreCase(args[0])) {
                assignTicket(sender, Arrays.<String>copyOfRange(args, 1, args.length));
            } else if ("Reload".equalsIgnoreCase(args[0])) {
                reload(sender, Arrays.<String>copyOfRange(args, 1, args.length));
            } else if ("Reminder".equalsIgnoreCase(args[0])) {
                reminder(sender, Arrays.<String>copyOfRange(args, 1, args.length));
            } else {
                sendUsageMessage(sender);
            }
        } catch (UsageException ue) {
            sendUsageMessage(sender, ue.getKey());
        } catch (CommandException ce) {
            Util.sendMessage(sender, "&c%s", ce.getMessage());
        }
        return true;
    }

    private Ticket ticketById(int id) {
        List<Ticket> tickets = getDatabase().find(Ticket.class).where().idEq(id).findList();
        if (tickets.isEmpty()) {
            throw new CommandException(String.format("Ticket [%d] not found.", id));
        }
        return tickets.get(0);
    }

    private Ticket ticketById(String arg) {
        // Fetch ticket.
        int id = -1;
        try {
            id = Integer.parseInt(arg);
        } catch (NumberFormatException nfe) {}
        if (id < 0) {
            throw new CommandException(String.format("Ticket ID expected, got: %s", arg));
        }
        return ticketById(id);
    }

    private void assertCommand(boolean condition, String message, Object... args) {
        if (!condition) throw new CommandException(String.format(message, args));
    }

    private void assertPermission(CommandSender sender, String permission) {
        assertCommand(sender.hasPermission(permission), "No permission.");
    }

    private void assertPlayer(CommandSender sender) {
        assertCommand(sender instanceof Player, "Player expected.");
    }

    private void portServer(Player player, String serverName) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);
        try {
            out.writeUTF("Connect");
            out.writeUTF(serverName);
        } catch (IOException ex) {
            // Impossible
        }
        player.sendPluginMessage(this, "BungeeCord", b.toByteArray());
    }

    private String compileMessage(String[] args, int beginIndex) {
        StringBuilder sb = new StringBuilder(args[beginIndex]);
        for (int i = beginIndex + 1; i < args.length; ++i) {
            sb.append(" ").append(args[i]);
        }
        return sb.toString();
    }

    private void listOwnedTickets(CommandSender sender) {
        List<Ticket> tickets = getDatabase().find(Ticket.class).where().ieq("ownerName", sender.getName()).findList();
        List<Ticket> opens = new ArrayList<Ticket>();
        for (Ticket ticket : tickets) {
            if (ticket.isOpen() || ticket.isUpdated()) opens.add(ticket);
        }
        if (opens.isEmpty()) {
            if (sender instanceof Player) {
                Util.tellRaw((Player)sender, Arrays.asList(
                                 Util.format("&3Need staff assistance? Click here: "),
                                 Util.commandSuggestButton("&3[&a\u270E &bNew Ticket&3]", "&3Click here to make a new ticket.\n&3Leave a message in chat.", "/ticket new ")));
            } else {
                Util.sendMessage(sender, "&3You have no open tickets. Type &b/Ticket ?&3 to view your options.");
            }
        } else {
            Util.sendMessage(sender, "&3You have %d open ticket(s). Click below to view.", opens.size());
            for (Ticket ticket : opens) {
                ticket.sendShortInfo(sender, false);
            }
        }
    }

    private void listOpenTickets(CommandSender sender) {
        List<Ticket> tickets = getDatabase().find(Ticket.class).where().eq("open", true).findList();
        if (tickets.isEmpty()) {
            Util.sendMessage(sender, "&3No open tickets.");
        } else {
            Util.sendMessage(sender, "&3%d open ticket(s).", tickets.size());
            for (Ticket ticket : tickets) {
                ticket.sendShortInfo(sender, true);
            }
        }
    }

    private void viewTicket(CommandSender sender, String[] args) {
        assertPermission(sender, "ticket.view");
        if (args.length != 1) throw new UsageException("view");
        Ticket ticket = ticketById(args[0]);
        if (!ticket.isOwner(sender)) assertPermission(sender, "ticket.view.any");
        List<Comment> comments = getDatabase().find(Comment.class).where().eq("ticketId", ticket.getId()).order().asc("id").findList();
        StringBuilder sb = new StringBuilder(ticket.getInfo());
        if (!comments.isEmpty()) {
            sb.append(Util.format("\n&3 Comments: &b%d", comments.size()));
            for (Comment comment : comments) {
                sb.append("\n ").append(comment.getInfo());
            }
        }
        sender.sendMessage(sb.toString());
        if (ticket.isOwner(sender)) {
            ticket.setUpdated(false);
            getDatabase().update(ticket, new HashSet<String>(Arrays.<String>asList("updated")));
        }
        // Display options
        ticket.sendOptions(sender);
    }

    private void newTicket(CommandSender sender, String[] args) {
        assertPermission(sender, "ticket.new");
        assertPlayer(sender);
        Player player = (Player)sender;
        if (args.length == 0) throw new UsageException("new");
        assertCommand(args.length >= 3, "Write a message with at least 3 words.");
        int ticketCount = getDatabase().find(Ticket.class).where().ieq("ownerName", player.getName()).eq("open", true).findRowCount();
        assertCommand(ticketCount < getMaxOpenTickets(), "You already have %d open tickets.", ticketCount);
        Ticket ticket = new Ticket(getServerName(), player, compileMessage(args, 0));
        getDatabase().save(ticket);
        if (sender instanceof Player) {
            int id = ticket.getId();
            Util.tellRaw((Player)sender, Arrays.asList(
                             Util.format("&bTicket "),
                             Util.commandRunButton("&3[&a\u21F2 &b"+id+"&3]", "&3Click to view the ticket", "/ticket view "+id),
                             Util.format("&b created: &7%s", ticket.getMessage())
                             ));
        } else {
            Util.sendMessage(sender, "&bTicket &3[&b%d&3]&b created: &7%s", ticket.getId(), ticket.getMessage());
        }
        notify(sender, "&e%s created ticket [%d]: %s", ticket.getOwnerName(), ticket.getId(), ticket.getMessage());
    }

    private void commentTicket(CommandSender sender, String[] args) {
        assertPermission(sender, "ticket.comment");
        if (args.length < 1) throw new UsageException("comment");
        Ticket ticket = ticketById(args[0]);
        assertCommand(args.length >= 4, "Write a comment with at least 3 words.");
        String message = compileMessage(args, 1);
        if (!ticket.isOwner(sender)) assertPermission(sender, "ticket.comment.any");
        Comment comment = new Comment(ticket.getId(), sender, message);
        getDatabase().save(comment);
        Util.sendMessage(sender, "&bCommented on ticket &3[&b%d&3]&b: &7%s", ticket.getId(), comment.getComment());
        if (!ticket.isOwner(sender)) {
            if (!ticket.notifyOwner("&3%s commented on your ticket [&b%d&3]: &7%s", comment.getCommenterName(), ticket.getId(), comment.getComment())) {
                ticket.setUpdated(true);
                getDatabase().update(ticket, new HashSet<String>(Arrays.<String>asList("updated")));
            }
        }
        notify(sender, "&e%s commented on ticket [%d]: %s", comment.getCommenterName(), comment.getTicketId(), comment.getComment());
    }

    private void closeTicket(CommandSender sender, String[] args) {
        assertPermission(sender, "ticket.close");
        if (args.length < 1) throw new UsageException("close");
        Ticket ticket = ticketById(args[0]);
        if (!ticket.isOwner(sender)) assertPermission(sender, "ticket.close.any");
        assertCommand(ticket.isOpen(), "Ticket [%d] is already closed.", ticket.getId());
        String message, cMessage;
        if (args.length > 1) {
            cMessage = compileMessage(args, 1);
            message = "Closed: " + cMessage;
        } else {
            cMessage = "";
            message = "Closed";
        }
        Comment comment = new Comment(ticket.getId(), sender, message);
        getDatabase().save(comment);
        ticket.setOpen(false);
        Util.sendMessage(sender, "&bTicket &3[&b%d&3]&b closed: &7%s", ticket.getId(), cMessage);
        if (!ticket.isOwner(sender)) {
            if (!ticket.notifyOwner("&3%s closed your ticket [&b%d&3]: &7%s", comment.getCommenterName(), ticket.getId(), cMessage)) {
                ticket.setUpdated(true);
            }
        }
        getDatabase().update(ticket, new HashSet<String>(Arrays.<String>asList("open", "updated")));
        notify(sender, "&e%s closed ticket [%d]: %s", comment.getCommenterName(), comment.getTicketId(), cMessage);
    }

    private void reopenTicket(CommandSender sender, String[] args) {
        assertPermission(sender, "ticket.reopen");
        if (args.length < 1) throw new UsageException("reopen");
        Ticket ticket = ticketById(args[0]);
        if (!ticket.isOwner(sender)) assertPermission(sender, "ticket.reopen.any");
        assertCommand(!ticket.isOpen(), "Ticket [%d] is not closed.", ticket.getId());
        String message, cMessage;
        if (args.length > 1) {
            cMessage = compileMessage(args, 1);
            message = "Reopened: " + cMessage;
        } else {
            cMessage = "";
            message = "Reopened";
        }
        Comment comment = new Comment(ticket.getId(), sender, message);
        getDatabase().save(comment);
        ticket.setOpen(true);
        Util.sendMessage(sender, "&bTicket &3[&b%d&3]&b reopened: &7%s", ticket.getId(), cMessage);
        if (!ticket.isOwner(sender)) {
            if (!ticket.notifyOwner("&3%s reopened your ticket [&b%d&3]: &7%s", comment.getCommenterName(), ticket.getId(), cMessage)) {
                ticket.setUpdated(true);
            }
        }
        getDatabase().update(ticket, new HashSet<String>(Arrays.<String>asList("open", "updated")));
        notify(sender, "&e%s reopened ticket [%d]: %s", comment.getCommenterName(), comment.getTicketId(), cMessage);
    }

    private void portTicket(CommandSender sender, String[] args) {
        assertPermission(sender, "ticket.port");
        assertPlayer(sender);
        Player player = (Player)sender;
        if (args.length != 1) throw new UsageException("port");
        Ticket ticket = ticketById(args[0]);
        // Try server.
        if (getPortServer() && !getServerName().equalsIgnoreCase(ticket.getServerName())) {
            Util.sendMessage(player, "&bTicket &3[&b%d&3]&b is on server %s...", ticket.getId(), ticket.getServerName());
            portServer(player, ticket.getServerName());
            return;
        }
        // Try location.
        Location location = ticket.getLocation();
        if (location == null) throw new CommandException("Ticket location not found.");
        player.teleport(location);
        Util.sendMessage(player, "&bPorted to ticket &3[&b%d&3]&b.", ticket.getId());
        //Util.sendMessage(player, ticket.getInfo());
        // Assign
        if (!ticket.isAssigned()) {
            ticket.setAssignee(sender);
            getDatabase().update(ticket, new HashSet<String>(Arrays.<String>asList("assigneeName")));
            ticket.notifyOwner("&3%s was assigned to your ticket.", ticket.getAssigneeName());
            notify(sender, "&e%s was assigned to ticket [%d].", ticket.getAssigneeName(), ticket.getId());
        }
    }

    private void assignTicket(CommandSender sender, String[] args) {
        assertPermission(sender, "ticket.assign");
        if (args.length < 2) throw new UsageException("assign");
        Ticket ticket = ticketById(args[0]);
        ticket.setAssigneeName(compileMessage(args, 1));
        getDatabase().update(ticket, new HashSet<String>(Arrays.<String>asList("assigneeName")));
        Util.sendMessage(sender, "&bAssigned %s to ticket &3[&b%d&3]&b.", ticket.getAssigneeName(), ticket.getId());
        notify(sender, "&e%s assigned %s to ticket [%d].", sender.getName(), ticket.getAssigneeName(), ticket.getId());
    }

    private void reload(CommandSender sender, String[] args) {
        assertPermission(sender, "ticket.reload");
        if (args.length > 0) throw new UsageException("reload");
        reloadConfig();
        usageMessages = null;
        reminderTask.restart();
        Util.sendMessage(sender, "&bTicket configuration reloaded.");
    }

    private void reminder(CommandSender sender, String[] args) {
        assertPermission(sender, "ticket.reminder");
        if (args.length > 0) throw new UsageException("reminder");
        Util.sendMessage(sender, "&bTriggering reminder...");
        reminder();
        reminderTask.restart();
    }

    public void reminder() {
        // Remind moderators
        int tickets = getDatabase().find(Ticket.class).where().eq("open", true).eq("assignee_name", null).findRowCount();
        if (tickets == 0) {
            // Do nothing.
        } else if (tickets > 1) {
            notify(null, "&eThere are %d open tickets. Please attend to them.", tickets);
        } else {
            notify(null, "&eThere is an open ticket. Please attend to it.");
        }
        // Remind players
        Set<Player> players = new HashSet<Player>();
        for (Ticket ticket : getDatabase().find(Ticket.class).where().eq("updated", true).findList()) {
            Player player = ticket.getOwner();
            if (player != null) players.add(player);
        }
        for (Player player : players) {
            // Util.tellraw(player, Util.format("['&3There are ticket updates for you. ',{text:'&3[&bClick here&3]',hoverEvent:{action:show_text,value:'&3Click here for more info'},clickEvent:{action:run_command,value:'/ticket'}},'&3 for more info.']"));
            Util.tellRaw(player, Arrays.asList(
                             Util.format("&3There are ticket updates for you. "),
                             Util.commandRunButton("&3[&bClick here&3]", "&3Click here for more info", "/ticket"),
                             Util.format("&3 for more info.")
                             ));
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("ticket.moderation")) {
            int tickets = getDatabase().find(Ticket.class).where().eq("open", true).eq("assignee_name", null).findRowCount();
            if (tickets == 0) {
                return;
            } else if (tickets > 1) {
                Util.sendMessage(player, "&eThere are %d open tickets. Please attend to them.", tickets);
            } else {
                Util.sendMessage(player, "&eThere is an open ticket. Please attend to it.");
            }
        }
        int tickets = getDatabase().find(Ticket.class).where().ieq("ownerName", player.getName()).eq("updated", true).findRowCount();
        if (tickets > 0) {
            // Util.tellraw(player, Util.format("['&3There are ticket updates for you. ',{text:'&3[&bClick here&3]',hoverEvent:{action:show_text,value:'&3Click here for more info'},clickEvent:{action:run_command,value:'/ticket'}},'&3 for more info.']"));
            Util.tellRaw(player, Arrays.asList(
                             Util.format("&3There are ticket updates for you. "),
                             Util.commandRunButton("&3[&bClick here&3]", "&3Click here for more info", "/ticket"),
                             Util.format("&3 for more info.")
                             ));
        }
    }

    @Override
    public List<Class<?>> getDatabaseClasses() {
        List<Class<?>> list = new ArrayList<Class<?>>();
        list.add(Ticket.class);
        list.add(Comment.class);
        return list;
    }

    public void notify(CommandSender except, String message, Object... args) {
        message = Util.format(message, args);
        getLogger().info(ChatColor.stripColor(message));
        for (Player player : getServer().getOnlinePlayers()) {
            if (!player.equals(except) && player.hasPermission("ticket.notify")) {
                player.sendMessage(message);
            }
        }
    }

    public String getServerName() {
        return getConfig().getString("ServerName");
    }

    public int getMaxOpenTickets() {
        return getConfig().getInt("MaxOpenTickets");
    }

    public boolean getPortServer() {
        return getConfig().getBoolean("PortServer");
    }
}