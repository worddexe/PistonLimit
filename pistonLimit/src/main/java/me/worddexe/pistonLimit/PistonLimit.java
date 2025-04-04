package me.worddexe.pistonLimit;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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
                int normalCount = 0;
                int stickyCount = 0;
                int worldMaxHeight = world.getMaxHeight();
                int chunkX = chunk.getX();
                int chunkZ = chunk.getZ();
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
                String key = getChunkKey(world.getName(), chunkX, chunkZ);
                if (normalCount > 0) {
                    normalPistonCount.put(key, normalCount);
                }
                if (stickyCount > 0) {
                    stickyPistonCount.put(key, stickyCount);
                }
            }
        }
    }

    private String getChunkKey(String worldName, int chunkX, int chunkZ) {
        return worldName + ":" + chunkX + ":" + chunkZ;
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        Material type = block.getType();
        if (type != Material.PISTON && type != Material.STICKY_PISTON) {
            return;
        }
        Player player = event.getPlayer();
        if (player.hasPermission("pistonlimit.bypass")) {
            updateCountOnPlace(block, type);
            return;
        }

        FileConfiguration config = getConfig();
        int limit;
        int regionSize;
        if (type == Material.PISTON) {
            limit = config.getInt("piston-limit", 10);
            regionSize = config.getInt("piston-radius", 4);
        } else {
            limit = config.getInt("sticky-piston-limit", 10);
            regionSize = config.getInt("sticky-radius", 4);
        }

        if (limit == 0) {
            updateCountOnPlace(block, type);
            return;
        }

        Chunk currentChunk = block.getChunk();
        int originX = currentChunk.getX();
        int originZ = currentChunk.getZ();
        int half = regionSize / 2;
        int startChunkX = originX - half;
        int startChunkZ = originZ - half;
        int endChunkX = startChunkX + regionSize - 1;
        int endChunkZ = startChunkZ + regionSize - 1;

        int totalCount = 0;
        String worldName = block.getWorld().getName();
        Map<String, Integer> mapToUse = (type == Material.PISTON) ? normalPistonCount : stickyPistonCount;

        for (int cx = startChunkX; cx <= endChunkX; cx++) {
            for (int cz = startChunkZ; cz <= endChunkZ; cz++) {
                String key = getChunkKey(worldName, cx, cz);
                totalCount += mapToUse.getOrDefault(key, 0);
            }
        }

        if (totalCount >= limit) {
            player.sendMessage("§cYou have reached the piston limit in this region.");
            event.setCancelled(true);
            return;
        }

        updateCountOnPlace(block, type);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Material type = block.getType();
        if (type != Material.PISTON && type != Material.STICKY_PISTON) {
            return;
        }
        Chunk chunk = block.getChunk();
        String key = getChunkKey(block.getWorld().getName(), chunk.getX(), chunk.getZ());
        if (type == Material.PISTON) {
            normalPistonCount.put(key, Math.max(normalPistonCount.getOrDefault(key, 1) - 1, 0));
        } else if (type == Material.STICKY_PISTON) {
            stickyPistonCount.put(key, Math.max(stickyPistonCount.getOrDefault(key, 1) - 1, 0));
        }
    }

    private void updateCountOnPlace(Block block, Material type) {
        Chunk chunk = block.getChunk();
        String key = getChunkKey(block.getWorld().getName(), chunk.getX(), chunk.getZ());
        if (type == Material.PISTON) {
            normalPistonCount.put(key, normalPistonCount.getOrDefault(key, 0) + 1);
        } else if (type == Material.STICKY_PISTON) {
            stickyPistonCount.put(key, stickyPistonCount.getOrDefault(key, 0) + 1);
        }
    }
}
