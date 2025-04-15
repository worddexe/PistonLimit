package me.worddexe.pistonLimit;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Location;

import java.util.HashMap;
import java.util.Map;

public class PistonLimit extends JavaPlugin implements Listener {

    private final Map<String, Integer> normalPistonCount = new HashMap<>();
    private final Map<String, Integer> stickyPistonCount = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        preloadPistonCounts();
        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("PistonLimit enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("PistonLimit disabled.");
    }

    private void preloadPistonCounts() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                scanChunkForPistons(chunk);
            }
        }
    }

    private void scanChunkForPistons(Chunk chunk) {
        String key = getChunkKey(chunk);
        int normalCount = 0;
        int stickyCount = 0;

        int worldMaxHeight = chunk.getWorld().getMaxHeight();
        for (int bx = 0; bx < 16; bx++) {
            for (int bz = 0; bz < 16; bz++) {
                for (int by = 0; by < worldMaxHeight; by++) {
                    Block block = chunk.getBlock(bx, by, bz);
                    Material type = block.getType();
                    if (type == Material.PISTON) {
                        normalCount++;
                    } else if (type == Material.STICKY_PISTON) {
                        stickyCount++;
                    }
                }
            }
        }

        normalPistonCount.put(key, normalCount);
        stickyPistonCount.put(key, stickyCount);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        scanChunkForPistons(event.getChunk());
    }

    private String getChunkKey(Chunk chunk) {
        return chunk.getWorld().getName() + ":" + chunk.getX() + ":" + chunk.getZ();
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        Material type = block.getType();
        if (type != Material.PISTON && type != Material.STICKY_PISTON) return;

        Player player = event.getPlayer();
        if (player.hasPermission("pistonlimit.bypass")) {
            updateCountOnPlace(block, type);
            return;
        }

        FileConfiguration config = getConfig();
        int limit, regionSize;
        Map<String, Integer> countMap;

        if (type == Material.PISTON) {
            limit = config.getInt("piston-limit", 10);
            regionSize = config.getInt("piston-radius", 4);
            countMap = normalPistonCount;
        } else {
            limit = config.getInt("sticky-piston-limit", 10);
            regionSize = config.getInt("sticky-radius", 4);
            countMap = stickyPistonCount;
        }

        if (limit <= 0) {
            updateCountOnPlace(block, type);
            return;
        }

        int total = countPistonsInRegion(block.getChunk(), regionSize, countMap);
        if (total >= limit) {
            player.sendMessage("§cPiston limit exceeded in this region (" + limit + ").");
            event.setCancelled(true);
        } else {
            updateCountOnPlace(block, type);
        }
    }

    private int countPistonsInRegion(Chunk originChunk, int regionSize, Map<String, Integer> countMap) {
        int total = 0;
        int halfSize = regionSize / 2;
        int startX = originChunk.getX() - halfSize;
        int startZ = originChunk.getZ() - halfSize;

        for (int cx = startX; cx < startX + regionSize; cx++) {
            for (int cz = startZ; cz < startZ + regionSize; cz++) {
                String key = originChunk.getWorld().getName() + ":" + cx + ":" + cz;
                total += countMap.getOrDefault(key, 0);
            }
        }
        return total;
    }

    private void updateCountOnPlace(Block block, Material type) {
        String key = getChunkKey(block.getChunk());
        if (type == Material.PISTON) {
            normalPistonCount.put(key, normalPistonCount.getOrDefault(key, 0) + 1);
        } else {
            stickyPistonCount.put(key, stickyPistonCount.getOrDefault(key, 0) + 1);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Material type = block.getType();
        String key = getChunkKey(block.getChunk());

        if (type == Material.PISTON) {
            normalPistonCount.put(key, Math.max(normalPistonCount.getOrDefault(key, 0) - 1, 0));
        } else if (type == Material.STICKY_PISTON) {
            stickyPistonCount.put(key, Math.max(stickyPistonCount.getOrDefault(key, 0) - 1, 0));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        checkAndCancelPistonActivation(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        checkAndCancelPistonActivation(event.getBlock(), event);
    }

    private void checkAndCancelPistonActivation(Block piston, Cancellable event) {
        Material type = piston.getType();
        FileConfiguration config = getConfig();

        int limit, regionSize;
        Map<String, Integer> countMap;

        if (type == Material.PISTON) {
            limit = config.getInt("piston-limit", 10);
            regionSize = config.getInt("piston-radius", 4);
            countMap = normalPistonCount;
        } else if (type == Material.STICKY_PISTON) {
            limit = config.getInt("sticky-piston-limit", 10);
            regionSize = config.getInt("sticky-radius", 4);
            countMap = stickyPistonCount;
        } else {
            return;
        }

        if (limit <= 0) return;

        Player nearestPlayer = findNearestPlayer(piston.getLocation());
        if (nearestPlayer != null && nearestPlayer.hasPermission("pistonlimit.bypass")) {
            return;
        }

        int total = countPistonsInRegion(piston.getChunk(), regionSize, countMap);
        if (total > limit) {
            event.setCancelled(true);
            notifyPlayers(piston, limit);
        }
    }

    private Player findNearestPlayer(Location location) {
        Player nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        World targetWorld = location.getWorld();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().equals(targetWorld)) {
                continue;
            }

            double distance = player.getLocation().distanceSquared(location);
            if (distance < nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private void notifyPlayers(Block piston, int limit) {
        String message = "§cPiston activation blocked: Region limit exceeded (" + limit + ").";
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().equals(piston.getWorld()) &&
                    player.getLocation().distanceSquared(piston.getLocation()) <= 100) {
                player.sendMessage(message);
            }
        }
    }
}