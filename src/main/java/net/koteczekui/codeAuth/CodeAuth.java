package net.koteczekui.codeAuth;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class CodeAuth extends JavaPlugin implements Listener {

    private final Set<UUID> unauthenticated = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<UUID, BukkitTask> activeTasks = new ConcurrentHashMap<>();
    private ConfigLoader msgLoader;
    private File codesFile;
    private FileConfiguration codesConfig;
    private Logger logger;

    @Override
    public void onEnable() {
        this.logger = getLogger();
        logger.info("[STARTUP] --- Initializing CodeAuth ---");

        if (!getDataFolder().exists()) {
            boolean created = getDataFolder().mkdirs();
            logger.info("[STARTUP] Plugin folder created: " + created);
        }

        msgLoader = new ConfigLoader(this);
        msgLoader.load();
        logger.info("[STARTUP] Messages from msg.yml loaded successfully.");

        codesFile = new File(getDataFolder(), "codes.yml");
        if (!codesFile.exists()) {
            logger.info("[STARTUP] codes.yml not found. Generating default from resources...");
            saveResource("codes.yml", false);
        }
        codesConfig = YamlConfiguration.loadConfiguration(codesFile);
        logger.info("[STARTUP] Loaded " + codesConfig.getStringList("codes").size() + " active codes.");

        getServer().getPluginManager().registerEvents(this, this);
        logger.info("[STARTUP] Event priority listeners registered (HIGHEST/LOWEST).");
        logger.info("[STARTUP] --- CodeAuth is FULLY OPERATIONAL ---");
    }

    @Override
    public void onDisable() {
        logger.info("[SHUTDOWN] System stopping. Cleaning up " + activeTasks.size() + " tasks.");
        activeTasks.values().forEach(BukkitTask::cancel);
        activeTasks.clear();
        unauthenticated.clear();
        logger.info("[SHUTDOWN] All players session locks released.");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        reloadConfig();
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        unauthenticated.add(uuid);
        logger.info("[JOIN] " + player.getName() + " (UUID: " + uuid + ") connected. Authentication LOCK applied.");

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (unauthenticated.contains(uuid)) {
                reloadConfig();
                player.sendMessage(color(msgLoader.getMsg("prompt")));
                logger.info("[PROMPT] Reminder sent to " + player.getName());
            }
        }, 0L, 100L);

        activeTasks.put(uuid, task);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent event) {
        reloadConfig();
        Player player = event.getPlayer();
        if (unauthenticated.contains(player.getUniqueId())) {
            boolean allow = codesConfig.getBoolean("behaviour.allow-movement", true);
            if (allow) return;

            Location from = event.getFrom();
            Location to = event.getTo();
            if (to == null) return;

            if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
                double dist = from.distanceSquared(to);
                if (dist > 0.25) {
                    logger.severe("[ALERT] " + player.getName() + " triggered movement kick! Dist: " + dist);
                    kickWithStyles(player, msgLoader.getMsg("security-kick"));
                    return;
                }
                Location loc = from.clone();
                loc.setYaw(to.getYaw());
                loc.setPitch(to.getPitch());
                event.setTo(loc);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        reloadConfig();
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!unauthenticated.contains(uuid)) return;

        event.setCancelled(true);
        String code = event.getMessage().trim();
        logger.info("[AUTH-TRY] Player " + player.getName() + " submitted a code.");

        if (processCode(code)) {
            unauthenticated.remove(uuid);
            BukkitTask task = activeTasks.remove(uuid);
            if (task != null) task.cancel();

            player.sendMessage(color(msgLoader.getMsg("success")));
            logger.info("[AUTH-PASS] " + player.getName() + " entered valid code. Access GRANTED.");
        } else {
            player.sendMessage(color(msgLoader.getMsg("incorrect")));
            logger.warning("[AUTH-DENY] " + player.getName() + " entered INVALID code: " + code);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        reloadConfig();
        if (unauthenticated.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            logger.warning("[BLOCK] Blocked command " + event.getMessage() + " from " + event.getPlayer().getName());
            event.getPlayer().sendMessage(color(msgLoader.getMsg("no-commands")));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        reloadConfig();
        if (unauthenticated.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            logger.info("[BLOCK] Blocked interaction from " + event.getPlayer().getName());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        reloadConfig();
        if (unauthenticated.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            logger.info("[BLOCK] Blocked inventory access for " + event.getPlayer().getName());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        reloadConfig();
        if (unauthenticated.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            logger.info("[BLOCK] Blocked item drop from " + event.getPlayer().getName());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        reloadConfig();
        if (unauthenticated.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            logger.info("[BLOCK] Blocked block break from " + event.getPlayer().getName());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        reloadConfig();
        if (unauthenticated.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            logger.info("[BLOCK] Blocked block place from " + event.getPlayer().getName());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        reloadConfig();
        if (unauthenticated.contains(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        reloadConfig();
        if (unauthenticated.contains(event.getDamager().getUniqueId())) {
            event.setCancelled(true);
            logger.warning("[BLOCK] Blocked combat attempt from " + event.getDamager().getName());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        reloadConfig();
        UUID uuid = event.getPlayer().getUniqueId();
        logger.info("[QUIT] " + event.getPlayer().getName() + " left. Cleaning cache.");
        unauthenticated.remove(uuid);
        BukkitTask task = activeTasks.remove(uuid);
        if (task != null) task.cancel();
    }

    private void kickWithStyles(Player player, String message) {
        logger.info("[KICK] Kicking " + player.getName() + " for: " + message);
        player.kickPlayer(color(message));
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private synchronized boolean processCode(String targetCode) {
        List<String> codes = codesConfig.getStringList("codes");
        logger.info("[I/O] Scanning codes.yml for match...");

        if (codes.contains(targetCode)) {
            codes.remove(targetCode);
            codesConfig.set("codes", codes);
            try {
                codesConfig.save(codesFile);
                logger.info("[I/O] Match found. Code removed and file SAVED.");
            } catch (IOException e) {
                logger.severe("[I/O ERROR] Could not save codes.yml! " + e.getMessage());
            }
            return true;
        }
        logger.info("[I/O] No match found in database.");
        return false;
    }
}
