package com.Lino.filterHopper;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Hopper;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class FilterHopper extends JavaPlugin implements Listener {

    private NamespacedKey filterKey;
    private final Map<Location, Set<FilterItem>> hopperFilters = new ConcurrentHashMap<>();
    private final Map<UUID, Location> openGuis = new ConcurrentHashMap<>();
    private final Map<Location, Long> lastProcessTime = new ConcurrentHashMap<>();

    private File dataFile;
    private FileConfiguration dataConfig;
    private BukkitTask saveTask;

    // Cache per ottimizzare la creazione degli item
    private ItemStack cachedFilterHopper;

    // Configurazione
    private FileConfiguration config;
    private String filterHopperName;
    private List<String> filterHopperLore;
    private String filterGuiTitle;
    private int processRateLimit;
    private int maxFilterItems;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfiguration();

        filterKey = new NamespacedKey(this, "filter_hopper");
        getServer().getPluginManager().registerEvents(this, this);

        setupDataFile();
        loadData();

        createCachedFilterHopper();

        long saveInterval = config.getLong("save-interval", 300) * 20L;
        saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::saveData, saveInterval, saveInterval);

        getLogger().info("FilterHopper v2.0 enabled successfully!");
    }

    private void loadConfiguration() {
        config = getConfig();

        filterHopperName = colorize(config.getString("filter-hopper.name", "&6Filter Hopper"));
        filterHopperLore = new ArrayList<>();
        for (String line : config.getStringList("filter-hopper.lore")) {
            filterHopperLore.add(colorize(line));
        }
        filterGuiTitle = colorize(config.getString("messages.filter-gui-title", "Filter: select items to filter"));
        processRateLimit = config.getInt("process-rate-limit", 50);
        maxFilterItems = Math.max(1, Math.min(9, config.getInt("max-filter-items", 9)));
    }

    private String colorize(String text) {
        return text.replace('&', '§');
    }

    @Override
    public void onDisable() {
        if (saveTask != null) {
            saveTask.cancel();
        }
        saveData();
        getLogger().info("FilterHopper v2.0 disabled - data saved successfully!");
    }

    private void setupDataFile() {
        dataFile = new File(getDataFolder(), "filters.yml");
        if (!dataFile.exists()) {
            dataFile.getParentFile().mkdirs();
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void createCachedFilterHopper() {
        cachedFilterHopper = new ItemStack(Material.HOPPER);
        ItemMeta meta = cachedFilterHopper.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(filterHopperName);
            meta.setLore(filterHopperLore);
            meta.getPersistentDataContainer().set(filterKey, PersistentDataType.BYTE, (byte) 1);
            cachedFilterHopper.setItemMeta(meta);
        }
    }

    private ItemStack createFilterHopper(int amount) {
        ItemStack item = cachedFilterHopper.clone();
        item.setAmount(amount);
        return item;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("filterhopper")) {
            return false;
        }

        if (!sender.hasPermission("filterhopper.admin")) {
            sender.sendMessage(colorize(config.getString("messages.no-permission", "&cYou don't have permission!")));
            return true;
        }

        if (args.length >= 3 && args[0].equalsIgnoreCase("give")) {
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(colorize(config.getString("messages.player-not-found", "&cPlayer not found!")));
                return true;
            }

            int amount = 1;
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
            } catch (NumberFormatException e) {
                sender.sendMessage(colorize(config.getString("messages.invalid-amount", "&cInvalid amount!")));
                return true;
            }

            target.getInventory().addItem(createFilterHopper(amount));
            String message = config.getString("messages.give-success", "&aGiven %amount% Filter Hopper(s) to %player%")
                    .replace("%amount%", String.valueOf(amount))
                    .replace("%player%", target.getName());
            sender.sendMessage(colorize(message));
            return true;
        }

        sender.sendMessage("§eUsage: /filterhopper give <player> <amount>");
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.isCancelled()) return;

        ItemStack item = event.getItemInHand();
        if (item.getType() != Material.HOPPER || !item.hasItemMeta()) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(filterKey, PersistentDataType.BYTE)) return;

        Player player = event.getPlayer();
        if (!player.hasPermission("filterhopper.place")) {
            event.setCancelled(true);
            player.sendMessage(colorize(config.getString("messages.no-permission", "&cYou don't have permission!")));
            return;
        }

        Block block = event.getBlock();
        if (!(block.getState() instanceof Hopper)) return;

        Hopper hopper = (Hopper) block.getState();
        hopper.getPersistentDataContainer().set(filterKey, PersistentDataType.BYTE, (byte) 1);
        hopper.update();

        Location loc = new Location(block.getLocation());
        hopperFilters.put(loc, ConcurrentHashMap.newKeySet());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) return;

        Block block = event.getBlock();
        if (block.getType() != Material.HOPPER || !(block.getState() instanceof Hopper)) return;

        Hopper hopper = (Hopper) block.getState();
        if (!hopper.getPersistentDataContainer().has(filterKey, PersistentDataType.BYTE)) return;

        Player player = event.getPlayer();
        if (!player.hasPermission("filterhopper.break")) {
            event.setCancelled(true);
            player.sendMessage(colorize(config.getString("messages.no-permission", "&cYou don't have permission!")));
            return;
        }

        event.setDropItems(false);
        block.getWorld().dropItemNaturally(block.getLocation(), createFilterHopper(1));

        Location loc = new Location(block.getLocation());
        hopperFilters.remove(loc);
        lastProcessTime.remove(loc);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (!player.hasPermission("filterhopper.use")) return;

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() == Material.HOPPER || player.getInventory().getItemInOffHand().getType() == Material.HOPPER) {
            return;
        }

        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.HOPPER) return;

        if (!(block.getState() instanceof Hopper)) return;

        Hopper hopper = (Hopper) block.getState();
        if (!hopper.getPersistentDataContainer().has(filterKey, PersistentDataType.BYTE)) return;

        event.setCancelled(true);

        Inventory gui = Bukkit.createInventory(null, maxFilterItems, filterGuiTitle);
        Location loc = new Location(block.getLocation());
        Set<FilterItem> filter = hopperFilters.getOrDefault(loc, ConcurrentHashMap.newKeySet());

        for (FilterItem filterItem : filter) {
            gui.addItem(filterItem.toItemStack());
        }

        player.openInventory(gui);
        openGuis.put(player.getUniqueId(), loc);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;

        Player player = (Player) event.getWhoClicked();
        Location loc = openGuis.get(player.getUniqueId());
        if (loc == null) return;

        if (!event.getView().getTitle().equals(filterGuiTitle)) return;

        event.setCancelled(true);

        if (event.getClickedInventory() == player.getInventory()) {
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) return;

            Inventory gui = event.getView().getTopInventory();
            FilterItem filterItem = new FilterItem(clicked);

            // Controlla se l'item è già presente
            for (int i = 0; i < gui.getSize(); i++) {
                ItemStack item = gui.getItem(i);
                if (item != null && filterItem.isSimilar(item)) {
                    return;
                }
            }

            // Aggiungi l'item al primo slot vuoto
            for (int i = 0; i < gui.getSize(); i++) {
                if (gui.getItem(i) == null) {
                    gui.setItem(i, clicked.clone());
                    break;
                }
            }
        } else if (event.getClickedInventory() == event.getView().getTopInventory()) {
            event.getView().getTopInventory().setItem(event.getSlot(), null);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;

        Player player = (Player) event.getPlayer();
        Location loc = openGuis.remove(player.getUniqueId());
        if (loc == null) return;

        Set<FilterItem> filter = ConcurrentHashMap.newKeySet();
        for (ItemStack item : event.getInventory().getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                filter.add(new FilterItem(item));
            }
        }

        if (filter.isEmpty()) {
            hopperFilters.remove(loc);
        } else {
            hopperFilters.put(loc, filter);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (event.isCancelled()) return;

        if (!(event.getDestination().getHolder() instanceof Hopper)) return;

        Block block = event.getDestination().getLocation().getBlock();
        if (!(block.getState() instanceof Hopper)) return;

        Hopper hopper = (Hopper) block.getState();
        if (!hopper.getPersistentDataContainer().has(filterKey, PersistentDataType.BYTE)) return;

        Location loc = new Location(block.getLocation());

        // Rate limiting per evitare processing eccessivo
        Long lastTime = lastProcessTime.get(loc);
        long currentTime = System.currentTimeMillis();
        if (lastTime != null && currentTime - lastTime < processRateLimit) {
            return;
        }
        lastProcessTime.put(loc, currentTime);

        Set<FilterItem> filter = hopperFilters.get(loc);
        if (filter == null || filter.isEmpty()) return;

        ItemStack movingItem = event.getItem();
        boolean allowed = false;

        for (FilterItem filterItem : filter) {
            if (filterItem.matches(movingItem)) {
                allowed = true;
                break;
            }
        }

        if (!allowed) {
            event.setCancelled(true);

            if (config.getBoolean("debug", false)) {
                getLogger().info("Blocked item " + movingItem.getType() + " from filter hopper at " + loc);
            }

            Bukkit.getScheduler().runTask(this, () -> {
                Inventory source = event.getSource();
                removeItemFromInventory(source, movingItem);
            });
        }
    }

    private void removeItemFromInventory(Inventory inventory, ItemStack itemToRemove) {
        ItemStack[] contents = inventory.getContents();
        int remaining = itemToRemove.getAmount();

        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack slot = contents[i];
            if (slot != null && slot.isSimilar(itemToRemove)) {
                int amount = slot.getAmount();
                if (amount > remaining) {
                    slot.setAmount(amount - remaining);
                    remaining = 0;
                } else {
                    inventory.setItem(i, null);
                    remaining -= amount;
                }
            }
        }
    }

    private void loadData() {
        if (!dataConfig.contains("filters")) return;

        for (String key : dataConfig.getConfigurationSection("filters").getKeys(false)) {
            try {
                String[] parts = key.split(",");
                Location loc = new Location(
                        parts[0],
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3])
                );

                Set<FilterItem> items = ConcurrentHashMap.newKeySet();
                List<String> filterData = dataConfig.getStringList("filters." + key);

                for (String data : filterData) {
                    items.add(FilterItem.fromString(data));
                }

                hopperFilters.put(loc, items);
            } catch (Exception e) {
                getLogger().warning("Failed to load filter at " + key + ": " + e.getMessage());
            }
        }
    }

    private void saveData() {
        dataConfig = new YamlConfiguration();

        for (Map.Entry<Location, Set<FilterItem>> entry : hopperFilters.entrySet()) {
            Location loc = entry.getKey();
            String key = "filters." + loc.toString();

            List<String> filterData = new ArrayList<>();
            for (FilterItem item : entry.getValue()) {
                filterData.add(item.toString());
            }

            dataConfig.set(key, filterData);
        }

        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Failed to save filter data: " + e.getMessage());
        }
    }

    static class FilterItem {
        private final Material material;
        private final String displayName;
        private final int hashCode;

        FilterItem(ItemStack item) {
            this.material = item.getType();
            this.displayName = item.hasItemMeta() && item.getItemMeta().hasDisplayName() ?
                    item.getItemMeta().getDisplayName() : null;
            this.hashCode = Objects.hash(material, displayName);
        }

        private FilterItem(Material material, String displayName) {
            this.material = material;
            this.displayName = displayName;
            this.hashCode = Objects.hash(material, displayName);
        }

        boolean matches(ItemStack item) {
            if (item.getType() != material) return false;

            if (displayName == null) {
                return !item.hasItemMeta() || !item.getItemMeta().hasDisplayName();
            }

            return item.hasItemMeta() &&
                    item.getItemMeta().hasDisplayName() &&
                    displayName.equals(item.getItemMeta().getDisplayName());
        }

        boolean isSimilar(ItemStack item) {
            return matches(item);
        }

        ItemStack toItemStack() {
            ItemStack item = new ItemStack(material);
            if (displayName != null) {
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(displayName);
                    item.setItemMeta(meta);
                }
            }
            return item;
        }

        @Override
        public String toString() {
            return material.name() + ":" + (displayName != null ? displayName : "null");
        }

        static FilterItem fromString(String data) {
            String[] parts = data.split(":", 2);
            Material mat = Material.valueOf(parts[0]);
            String name = parts[1].equals("null") ? null : parts[1];
            return new FilterItem(mat, name);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof FilterItem)) return false;
            FilterItem other = (FilterItem) obj;
            return material == other.material && Objects.equals(displayName, other.displayName);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    static class Location {
        private final String world;
        private final int x, y, z;
        private final int hashCode;

        Location(org.bukkit.Location loc) {
            this.world = loc.getWorld().getName();
            this.x = loc.getBlockX();
            this.y = loc.getBlockY();
            this.z = loc.getBlockZ();
            this.hashCode = Objects.hash(world, x, y, z);
        }

        Location(String world, int x, int y, int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.hashCode = Objects.hash(world, x, y, z);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Location)) return false;
            Location other = (Location) obj;
            return x == other.x && y == other.y && z == other.z && world.equals(other.world);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public String toString() {
            return world + "," + x + "," + y + "," + z;
        }
    }
}