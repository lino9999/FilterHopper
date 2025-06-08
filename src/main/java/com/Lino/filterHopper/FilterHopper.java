package com.Lino.filterHopper;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Hopper;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
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

import java.util.*;

public class FilterHopper extends JavaPlugin implements Listener {

    private NamespacedKey filterKey;
    private NamespacedKey filterDataKey;
    private Map<Location, List<FilterItem>> hopperFilters = new HashMap<>();
    private Map<UUID, Location> openGuis = new HashMap<>();

    @Override
    public void onEnable() {
        filterKey = new NamespacedKey(this, "filter_hopper");
        filterDataKey = new NamespacedKey(this, "filter_data");
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("filterhopper")) {
            if (args.length >= 3 && args[0].equalsIgnoreCase("give")) {
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage("Player not found!");
                    return true;
                }

                int amount = 1;
                try {
                    amount = Integer.parseInt(args[2]);
                    amount = Math.max(1, Math.min(64, amount));
                } catch (NumberFormatException e) {
                    sender.sendMessage("Invalid amount!");
                    return true;
                }

                ItemStack filterHopper = new ItemStack(Material.HOPPER, amount);
                ItemMeta meta = filterHopper.getItemMeta();
                meta.setDisplayName("§6Filter Hopper");
                meta.setLore(Arrays.asList("§7Right-click to set filter"));
                PersistentDataContainer container = meta.getPersistentDataContainer();
                container.set(filterKey, PersistentDataType.BYTE, (byte) 1);
                filterHopper.setItemMeta(meta);

                target.getInventory().addItem(filterHopper);
                sender.sendMessage("Given " + amount + " Filter Hopper(s) to " + target.getName());
                return true;
            }
            sender.sendMessage("Usage: /filterhopper give <player> <amount>");
        }
        return false;
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (item.getType() == Material.HOPPER && item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            PersistentDataContainer container = meta.getPersistentDataContainer();
            if (container.has(filterKey, PersistentDataType.BYTE)) {
                Block block = event.getBlock();
                if (block.getState() instanceof Hopper) {
                    Hopper hopper = (Hopper) block.getState();
                    PersistentDataContainer hopperContainer = hopper.getPersistentDataContainer();
                    hopperContainer.set(filterKey, PersistentDataType.BYTE, (byte) 1);
                    hopper.update();
                    hopperFilters.put(new Location(block.getLocation()), new ArrayList<>());
                }
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() == Material.HOPPER && block.getState() instanceof Hopper) {
            Hopper hopper = (Hopper) block.getState();
            PersistentDataContainer container = hopper.getPersistentDataContainer();
            if (container.has(filterKey, PersistentDataType.BYTE)) {
                event.setDropItems(false);
                ItemStack filterHopper = new ItemStack(Material.HOPPER);
                ItemMeta meta = filterHopper.getItemMeta();
                meta.setDisplayName("§6Filter Hopper");
                meta.setLore(Arrays.asList("§7Right-click to set filter"));
                PersistentDataContainer itemContainer = meta.getPersistentDataContainer();
                itemContainer.set(filterKey, PersistentDataType.BYTE, (byte) 1);
                filterHopper.setItemMeta(meta);
                block.getWorld().dropItemNaturally(block.getLocation(), filterHopper);
                hopperFilters.remove(new Location(block.getLocation()));
            }
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Block block = event.getClickedBlock();
            Player player = event.getPlayer();

            if (player.getInventory().getItemInMainHand().getType() == Material.HOPPER ||
                    player.getInventory().getItemInOffHand().getType() == Material.HOPPER) {
                return;
            }

            if (block != null && block.getType() == Material.HOPPER && block.getState() instanceof Hopper) {
                Hopper hopper = (Hopper) block.getState();
                PersistentDataContainer container = hopper.getPersistentDataContainer();
                if (container.has(filterKey, PersistentDataType.BYTE)) {
                    event.setCancelled(true);

                    Inventory gui = Bukkit.createInventory(null, 9, "Filter: select items to filter from your inventory");
                    Location loc = new Location(block.getLocation());
                    List<FilterItem> filter = hopperFilters.getOrDefault(loc, new ArrayList<>());

                    for (FilterItem filterItem : filter) {
                        gui.addItem(filterItem.toItemStack());
                    }

                    player.openInventory(gui);
                    openGuis.put(player.getUniqueId(), loc);
                }
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        if (openGuis.containsKey(player.getUniqueId())) {
            if (event.getView().getTitle().equals("Filter: select items to filter from your inventory")) {
                event.setCancelled(true);

                if (event.getClickedInventory() == player.getInventory()) {
                    ItemStack clicked = event.getCurrentItem();
                    if (clicked != null && clicked.getType() != Material.AIR) {
                        Inventory gui = event.getView().getTopInventory();
                        boolean found = false;

                        for (int i = 0; i < gui.getSize(); i++) {
                            ItemStack item = gui.getItem(i);
                            if (item != null && isSameFilterItem(item, clicked)) {
                                found = true;
                                break;
                            }
                        }

                        if (!found) {
                            for (int i = 0; i < gui.getSize(); i++) {
                                if (gui.getItem(i) == null) {
                                    gui.setItem(i, clicked.clone());
                                    break;
                                }
                            }
                        }
                    }
                } else if (event.getClickedInventory() == event.getView().getTopInventory()) {
                    event.getView().getTopInventory().setItem(event.getSlot(), null);
                }
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();

        if (openGuis.containsKey(player.getUniqueId())) {
            Location loc = openGuis.remove(player.getUniqueId());
            List<FilterItem> filter = new ArrayList<>();

            for (ItemStack item : event.getInventory().getContents()) {
                if (item != null && item.getType() != Material.AIR) {
                    filter.add(new FilterItem(item));
                }
            }

            hopperFilters.put(loc, filter);
        }
    }

    @EventHandler
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        // Verifica se la destinazione è un Filter Hopper
        if (event.getDestination().getHolder() instanceof Hopper) {
            Block block = event.getDestination().getLocation().getBlock();
            if (block.getState() instanceof Hopper) {
                Hopper hopper = (Hopper) block.getState();
                PersistentDataContainer container = hopper.getPersistentDataContainer();
                if (container.has(filterKey, PersistentDataType.BYTE)) {
                    Location loc = new Location(block.getLocation());
                    List<FilterItem> filter = hopperFilters.getOrDefault(loc, new ArrayList<>());

                    if (!filter.isEmpty()) {
                        boolean allowed = false;
                        for (FilterItem filterItem : filter) {
                            if (filterItem.matches(event.getItem())) {
                                allowed = true;
                                break;
                            }
                        }

                        if (!allowed) {
                            // Cancella l'evento per impedire il movimento dell'item
                            event.setCancelled(true);

                            // Crea un task per rimuovere l'item dall'inventario di origine
                            Bukkit.getScheduler().runTask(this, () -> {
                                Inventory source = event.getSource();
                                ItemStack itemToRemove = event.getItem();

                                // Cerca e rimuovi l'item dall'inventario di origine
                                for (int i = 0; i < source.getSize(); i++) {
                                    ItemStack slot = source.getItem(i);
                                    if (slot != null && slot.isSimilar(itemToRemove)) {
                                        if (slot.getAmount() > itemToRemove.getAmount()) {
                                            slot.setAmount(slot.getAmount() - itemToRemove.getAmount());
                                        } else {
                                            source.setItem(i, null);
                                        }
                                        break;
                                    }
                                }
                            });
                        }
                    }
                }
            }
        }
    }

    private boolean isSameFilterItem(ItemStack item1, ItemStack item2) {
        if (item1.getType() != item2.getType()) return false;

        String name1 = item1.hasItemMeta() && item1.getItemMeta().hasDisplayName() ?
                item1.getItemMeta().getDisplayName() : null;
        String name2 = item2.hasItemMeta() && item2.getItemMeta().hasDisplayName() ?
                item2.getItemMeta().getDisplayName() : null;

        if (name1 == null && name2 == null) return true;
        if (name1 == null || name2 == null) return false;
        return name1.equals(name2);
    }

    class FilterItem {
        private final Material material;
        private final String displayName;

        FilterItem(ItemStack item) {
            this.material = item.getType();
            this.displayName = item.hasItemMeta() && item.getItemMeta().hasDisplayName() ?
                    item.getItemMeta().getDisplayName() : null;
        }

        boolean matches(ItemStack item) {
            if (item.getType() != material) return false;

            String itemName = item.hasItemMeta() && item.getItemMeta().hasDisplayName() ?
                    item.getItemMeta().getDisplayName() : null;

            if (displayName == null && itemName == null) return true;
            if (displayName == null || itemName == null) return false;
            return displayName.equals(itemName);
        }

        ItemStack toItemStack() {
            ItemStack item = new ItemStack(material);
            if (displayName != null) {
                ItemMeta meta = item.getItemMeta();
                meta.setDisplayName(displayName);
                item.setItemMeta(meta);
            }
            return item;
        }
    }

    class Location {
        private final int x, y, z;
        private final String world;

        Location(org.bukkit.Location loc) {
            this.x = loc.getBlockX();
            this.y = loc.getBlockY();
            this.z = loc.getBlockZ();
            this.world = loc.getWorld().getName();
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
            return Objects.hash(x, y, z, world);
        }
    }
}