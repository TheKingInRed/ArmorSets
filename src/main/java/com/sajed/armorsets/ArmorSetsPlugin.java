package com.sajed.armorsets;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ArmorSetsPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    // ============================== SET DEFINITIONS ==============================

    public enum SetType {
        EMERALD("Emerald", ChatColor.GREEN, 0.5, 20, 8, 0.05, 0, "DIAMOND"),
        RUBY("Ruby", ChatColor.RED, 1.0, 22, 8, 0.00, 35, "DIAMOND"),
        TITANIUM("Titanium", ChatColor.GRAY, 1.5, 21, 8, 0.05, 40, "DIAMOND"),
        VIBRANIUM("Vibranium", ChatColor.AQUA, 2.0, 22, 8, 0.10, 50, "DIAMOND"),
        ADAMANTIUM("Adamantium", ChatColor.DARK_GRAY, 2.5, 23, 12, 0.15, 40, "NETHERITE"),
        VENOM("Venom", ChatColor.DARK_PURPLE, 2.5, 23, 12, 0.20, 45, "NETHERITE");

        public final String display;
        public final ChatColor color;
        public final double healthPerPiece;
        public final double armorTotal;
        public final double toughnessTotal;
        public final double duraReduction;
        public final int cooldownSeconds;
        public final String baseMaterial;

        SetType(String display, ChatColor color, double healthPerPiece,
                double armorTotal, double toughnessTotal, double duraReduction,
                int cooldownSeconds, String baseMaterial) {
            this.display = display;
            this.color = color;
            this.healthPerPiece = healthPerPiece;
            this.armorTotal = armorTotal;
            this.toughnessTotal = toughnessTotal;
            this.duraReduction = duraReduction;
            this.cooldownSeconds = cooldownSeconds;
            this.baseMaterial = baseMaterial;
        }

        public double pieceArmorFraction(String role) {
            switch (role) {
                case "helmet":
                    return armorTotal * (3.0 / 20.0);
                case "chestplate":
                    return armorTotal * (8.0 / 20.0);
                case "leggings":
                    return armorTotal * (6.0 / 20.0);
                default:
                    return armorTotal * (3.0 / 20.0);
            }
        }

        public double pieceToughness() {
            return toughnessTotal / 4.0;
        }

        public Material defaultMaterial(String role) {
            return Material.valueOf(baseMaterial + "_" + role.toUpperCase());
        }

        public static SetType fromName(String s) {
            for (SetType t : values()) {
                if (t.name().equalsIgnoreCase(s)) return t;
            }
            return null;
        }
    }

    public static final String[] ROLES = {
            "helmet", "chestplate", "leggings", "boots"
    };

    public static final int[] GUI_SLOTS = {11, 12, 13, 14};

    // ============================== STATE ==============================

    private NamespacedKey keySet;
    private NamespacedKey keyRole;
    private NamespacedKey keyArrow;

    private final Map<UUID, Map<SetType, Long>> cooldowns = new HashMap<>();
    private final Map<UUID, Integer> titaniumHits = new HashMap<>();
    private final Map<UUID, Integer> vibraniumHits = new HashMap<>();
    private final Map<UUID, Double> lastHitDamage = new HashMap<>();
    private final Map<UUID, UUID> lastAttacker = new HashMap<>();
    private final Map<UUID, Integer> venomKills = new HashMap<>();

    private final Map<UUID, GuiSession> sessions = new HashMap<>();
    private final Map<UUID, PendingInput> pendingInput = new HashMap<>();

    private final List<Region> regions = new ArrayList<>();

    static class GuiSession {
        SetType set;
        String name;
        List<String> lore;
        Integer modelData;
    }

    enum PendingType {
        NAME, LORE, MODEL
    }

    static class PendingInput {
        PendingType type;
        SetType set;
    }

    static class ConversionTarget {
        SetType set;
        String role;

        ConversionTarget(SetType set, String role) {
            this.set = set;
            this.role = role;
        }
    }

    static class Region {
        World world;
        int x1, y1, z1, x2, y2, z2;

        boolean contains(Block b) {
            return b.getWorld().equals(world)
                    && b.getX() >= Math.min(x1, x2)
                    && b.getX() <= Math.max(x1, x2)
                    && b.getY() >= Math.min(y1, y2)
                    && b.getY() <= Math.max(y1, y2)
                    && b.getZ() >= Math.min(z1, z2)
                    && b.getZ() <= Math.max(z1, z2);
        }
    }

    static class SetGuiHolder implements InventoryHolder {
        public SetType set;
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        public void setInventory(Inventory inv) {
            this.inventory = inv;
        }
    }

    // ============================== LIFECYCLE ==============================

    @Override
    public void onEnable() {
        keySet = new NamespacedKey(this, "armorset");
        keyRole = new NamespacedKey(this, "role");
        keyArrow = new NamespacedKey(this, "adam_arrow");

        // Create the plugin folder and required files.
        File folder = getDataFolder();
        folder.mkdirs();

        File dataFolder = new File(folder, "data");
        dataFolder.mkdirs();

        try {
            File configFile = new File(folder, "config.yml");
            if (!configFile.exists()) configFile.createNewFile();

            File setsFile = new File(folder, "sets.yml");
            if (!setsFile.exists()) setsFile.createNewFile();

            File abilitiesFile = new File(folder, "abilities.yml");
            if (!abilitiesFile.exists()) abilitiesFile.createNewFile();
        } catch (IOException e) {
            getLogger().severe("Could not create plugin files: " + e.getMessage());
        }

        saveConfig();
        reloadConfig();

        // Automatically register all commands from plugin.yml.
        for (String commandName : getDescription().getCommands().keySet()) {
            if (getCommand(commandName) != null) {
                getCommand(commandName).setExecutor(this);
                getCommand(commandName).setTabCompleter(this);
            }
        }

        getServer().getPluginManager().registerEvents(this, this);

        loadRegions();

        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                double max = p.getAttribute(Attribute.MAX_HEALTH).getValue();

                if (p.getHealth() > max) {
                    p.setHealth(max);
                }

                if (isFullSet(p, SetType.ADAMANTIUM)) {
                    for (ItemStack it : p.getInventory().getArmorContents()) {
                        if (it != null && isSetItem(it, SetType.ADAMANTIUM)) {
                            ItemMeta meta = it.getItemMeta();

                            if (meta instanceof Damageable) {
                                Damageable d = (Damageable) meta;

                                if (d.getDamage() > 0) {
                                    d.setDamage(d.getDamage() - 1);
                                    it.setItemMeta(meta);
                                }
                            }
                        }
                    }
                }
            }
        }, 40L, 40L);

        getLogger().info("ArmorSets enabled. 6 sets registered.");
    }

    // ============================== COMMANDS ==============================

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {

        String name = command.getName().toLowerCase();

        if (!(sender instanceof Player)) {
            sender.sendMessage("Player only.");
            return true;
        }

        Player p = (Player) sender;

        // Individual armor-piece conversion commands.
        ConversionTarget conversion = conversionFromCommand(name);

        if (conversion != null) {
            if (!p.hasPermission("armorsets.admin")) {
                p.sendMessage(color("&cYou do not have permission to use this."));
                return true;
            }

            ItemStack held = p.getInventory().getItemInMainHand();

            if (held == null || held.getType().isAir()) {
                p.sendMessage(color("&cYou must hold an armor piece first."));
                return true;
            }

            String expectedMaterial = "_" + conversion.role.toUpperCase();

            if (!held.getType().name().endsWith(expectedMaterial)) {
                p.sendMessage(color("&cYou must hold a " + conversion.role + "!"));
                return true;
            }

            applySetPiecePreservingText(held, conversion.set, conversion.role);

            p.getInventory().setItemInMainHand(held);

            p.sendMessage(color(conversion.set.color + "&l"
                    + conversion.set.display + " " + cap(conversion.role)
                    + " &7successfully applied!"));

            p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 1f, 1.4f);
            return true;
        }

        // Individual full-set commands.
        SetType directGet = setFromGetCommand(name);

        if (directGet != null) {
            if (!p.hasPermission("armorsets.getset")) {
                p.sendMessage(color("&cYou do not have permission to use this."));
                return true;
            }

            giveSet(p, directGet);

            p.sendMessage(color(directGet.color + "You received the "
                    + directGet.display + " set!"));

            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            return true;
        }

        // Existing customization GUI commands.
        SetType fromCmd = setFromCommand(name);

        if (fromCmd != null) {
            if (!p.hasPermission("armorsets.admin")) {
                p.sendMessage(color("&cYou do not have permission to use this."));
                return true;
            }

            openSetGui(p, fromCmd);
            return true;
        }

        switch (name) {
            case "getset": {
                if (!p.hasPermission("armorsets.getset")) {
                    p.sendMessage(color("&cYou do not have permission to use this."));
                    return true;
                }

                if (args.length < 1) {
                    p.sendMessage(color("&cUsage: /getset <emerald|ruby|titanium|vibranium|adamantium|venom>"));
                    return true;
                }

                SetType set = SetType.fromName(args[0]);

                if (set == null) {
                    p.sendMessage(color("&cUnknown set: " + args[0]));
                    return true;
                }

                giveSet(p, set);

                p.sendMessage(color(set.color + "You received the "
                        + set.display + " set!"));

                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                return true;
            }

            case "venomregion": {
                if (!p.hasPermission("armorsets.admin")) {
                    p.sendMessage(color("&cYou do not have permission to use this."));
                    return true;
                }

                handleRegionCommand(p, args);
                return true;
            }
        }

        return true;
    }

    private SetType setFromCommand(String cmdName) {
        switch (cmdName) {
            case "setemeraldset":
                return SetType.EMERALD;
            case "setrubyset":
                return SetType.RUBY;
            case "settitaniumset":
                return SetType.TITANIUM;
            case "setvibraniumset":
                return SetType.VIBRANIUM;
            case "setadamantiumset":
                return SetType.ADAMANTIUM;
            case "setvenomset":
                return SetType.VENOM;
            default:
                return null;
        }
    }

    private ConversionTarget conversionFromCommand(String command) {
        if (!command.startsWith("set")) return null;

        String remaining = command.substring(3);

        for (SetType set : SetType.values()) {
            String setName = set.name().toLowerCase();

            if (!remaining.startsWith(setName)) continue;

            String role = remaining.substring(setName.length());

            if (Arrays.asList(ROLES).contains(role)) {
                return new ConversionTarget(set, role);
            }
        }

        return null;
    }

    private SetType setFromGetCommand(String command) {
        if (!command.startsWith("get") || !command.endsWith("set")) {
            return null;
        }

        String setName = command.substring(3, command.length() - 3);

        return SetType.fromName(setName);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {

        String name = command.getName().toLowerCase();

        if (name.equals("getset") && args.length == 1) {
            List<String> out = new ArrayList<>();

            for (SetType t : SetType.values()) {
                if (t.name().toLowerCase().startsWith(args[0].toLowerCase())) {
                    out.add(t.name().toLowerCase());
                }
            }

            return out;
        }

        if (name.equals("venomregion") && args.length == 1) {
            return filter(Arrays.asList("pos1", "pos2", "list", "clear"), args[0]);
        }

        return new ArrayList<>();
    }

    private List<String> filter(List<String> options, String start) {
        List<String> out = new ArrayList<>();

        for (String o : options) {
            if (o.toLowerCase().startsWith(start.toLowerCase())) {
                out.add(o);
            }
        }

        return out;
    }

    // ============================== GUI ==============================

    private void openSetGui(Player p, SetType set) {
        GuiSession session = sessions.get(p.getUniqueId());

        if (session == null || session.set != set) {
            session = new GuiSession();
            session.set = set;
            sessions.put(p.getUniqueId(), session);
        }

        SetGuiHolder holder = new SetGuiHolder();
        holder.set = set;

        Inventory inv = Bukkit.createInventory(
                holder, 27, color(set.color + "&l" + set.display + " Set")
        );

        holder.setInventory(inv);

        ItemStack filler = button(Material.GRAY_STAINED_GLASS_PANE, " ", null);

        for (int i = 0; i < 27; i++) {
            inv.setItem(i, filler);
        }

        for (int s : GUI_SLOTS) {
            inv.setItem(s, null);
        }

        inv.setItem(2, button(Material.DIAMOND_HELMET, "&bHelmet", null));
        inv.setItem(11, null);
        inv.setItem(0, button(Material.BOOK,
                "&bPlace any armor pieces in the 4 middle slots.", null));
        inv.setItem(9, button(Material.DIAMOND_CHESTPLATE, "&bChestplate", null));
        inv.setItem(15, button(Material.DIAMOND_LEGGINGS, "&bLeggings", null));
        inv.setItem(17, button(Material.DIAMOND_BOOTS, "&bBoots", null));

        List<String> nameLore = new ArrayList<>();
        nameLore.add("&7Current: &f" +
                (session.name == null ? "&o(default " + set.display + " name)" : session.name));
        nameLore.add("&eClick, then type the name in chat.");

        inv.setItem(19, button(Material.NAME_TAG, "&6Customize Name", nameLore));

        List<String> loreLore = new ArrayList<>();
        loreLore.add("&7Separate lines with |");
        loreLore.add("&7Current: &f" +
                (session.lore == null ? "&o(default stat lore)" : String.join(" | ", session.lore)));
        loreLore.add("&eClick, then type the lore in chat.");

        inv.setItem(20, button(Material.WRITABLE_BOOK,
                "&6Customize Lore (subtitles)", loreLore));

        List<String> mdLore = new ArrayList<>();
        mdLore.add("&7Used with a resource pack to change how the item looks.");
        mdLore.add("&7Current: &f" +
                (session.modelData == null ? "&o(none)" : session.modelData));
        mdLore.add("&eClick, then type a number in chat.");

        inv.setItem(21, button(Material.PAPER, "&6Custom Model Data", mdLore));

        inv.setItem(23, button(Material.BARRIER,
                "&cClose (items return to you)", null));

        inv.setItem(25, button(Material.LIME_DYE, "&a&lApply",
                Arrays.asList(
                        "&7Applies " + set.display + " stats, hearts,",
                        "&7passives and abilities to the 4 items."
                )));

        p.openInventory(inv);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof SetGuiHolder) {
            for (int slot : e.getRawSlots()) {
                boolean allowed = slot >= 0 && slot < 27
                        && Arrays.stream(GUI_SLOTS).anyMatch(s -> s == slot);

                if (!allowed) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof SetGuiHolder)) return;

        SetGuiHolder holder = (SetGuiHolder) e.getInventory().getHolder();

        int raw = e.getRawSlot();
        int topSize = e.getInventory().getSize();

        if (raw >= topSize) {
            if (e.isShiftClick()) e.setCancelled(true);
            return;
        }

        e.setCancelled(true);

        if (Arrays.stream(GUI_SLOTS).anyMatch(s -> s == raw)) {
            e.setCancelled(false);
            return;
        }

        if (!(e.getWhoClicked() instanceof Player)) return;

        Player p = (Player) e.getWhoClicked();

        switch (raw) {
            case 19:
                startInput(p, holder.set, PendingType.NAME);
                break;
            case 20:
                startInput(p, holder.set, PendingType.LORE);
                break;
            case 21:
                startInput(p, holder.set, PendingType.MODEL);
                break;
            case 23:
                p.closeInventory();
                break;
            case 25:
                applyGui(p, holder.set, e.getInventory());
                break;
            default:
                break;
        }
    }

    private void startInput(Player p, SetType set, PendingType type) {
        PendingInput in = new PendingInput();
        in.type = type;
        in.set = set;

        pendingInput.put(p.getUniqueId(), in);
        p.closeInventory();

        String prompt = type == PendingType.NAME ? "the custom name"
                : type == PendingType.LORE ? "the lore (separate lines with |)"
                : "the custom model data number";

        p.sendMessage(color("&6[" + set.display + "] &eType "
                + prompt + " &ein chat, or type 'cancel'."));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();

        if (!pendingInput.containsKey(p.getUniqueId())) return;

        e.setCancelled(true);

        String msg = e.getMessage();
        UUID id = p.getUniqueId();

        getServer().getScheduler().runTask(
                this, () -> handleChatInput(p, id, msg)
        );
    }

    private void handleChatInput(Player p, UUID id, String msg) {
        PendingInput in = pendingInput.remove(id);

        if (in == null) return;

        if (msg.equalsIgnoreCase("cancel")) {
            p.sendMessage(color("&cCancelled. Reopen with /set"
                    + in.set.name().toLowerCase() + "set"));
            return;
        }

        GuiSession s = sessions.get(id);

        if (s == null || s.set != in.set) {
            s = new GuiSession();
            s.set = in.set;
            sessions.put(id, s);
        }

        switch (in.type) {
            case NAME:
                s.name = ChatColor.translateAlternateColorCodes('&', msg);
                p.sendMessage(color("&aName set to: &f" + s.name));
                break;

            case LORE:
                List<String> lines = new ArrayList<>();

                for (String line : msg.split("\\|")) {
                    lines.add(ChatColor.translateAlternateColorCodes('&', line.trim()));
                }

                s.lore = lines;

                p.sendMessage(color("&aLore set. Reopen the GUI with /set"
                        + in.set.name().toLowerCase() + "set to review it."));
                break;

            case MODEL:
                try {
                    s.modelData = Integer.parseInt(msg.trim());
                    p.sendMessage(color("&aCustom model data set to " + s.modelData));
                } catch (NumberFormatException ex) {
                    p.sendMessage(color("&cThat is not a number. Try again by clicking the button in the GUI."));
                    pendingInput.put(id, in);
                }
                break;
        }

        openSetGui(p, in.set);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof SetGuiHolder)) return;
        if (!(e.getPlayer() instanceof Player)) return;

        Player p = (Player) e.getPlayer();

        if (pendingInput.containsKey(p.getUniqueId())) return;

        returnItems(p, e.getInventory());
    }

    private void returnItems(Player p, Inventory inv) {
        for (int s : GUI_SLOTS) {
            ItemStack it = inv.getItem(s);

            if (it != null && !it.getType().isAir()) {
                HashMap<Integer, ItemStack> left = p.getInventory().addItem(it);

                for (ItemStack l : left.values()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), l);
                }

                inv.setItem(s, null);
            }
        }
    }

    private void applyGui(Player p, SetType set, Inventory inv) {
        boolean any = false;
        List<ItemStack> results = new ArrayList<>();

        for (int i = 0; i < 4; i++) {
            ItemStack it = inv.getItem(GUI_SLOTS[i]);

            if (it == null || it.getType().isAir()) continue;

            any = true;

            applySetPiece(it, set, ROLES[i], sessions.get(p.getUniqueId()));

            results.add(it);
            inv.setItem(GUI_SLOTS[i], null);
        }

        if (!any) {
            p.sendMessage(color("&cPut at least one armor piece in the slots first."));
            return;
        }

        for (ItemStack r : results) {
            HashMap<Integer, ItemStack> left = p.getInventory().addItem(r);

            for (ItemStack l : left.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), l);
            }
        }

        p.closeInventory();

        p.sendMessage(color(set.color + "&l" + set.display
                + " set applied! &7Sneak + right-click to use the ability."));

        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 1f, 1.4f);
    }

    // ============================== ITEM CREATION ==============================

    private void applySetPiece(ItemStack item, SetType set,
                               String role, GuiSession session) {
        applySetPieceInternal(item, set, role, session, true);
    }

    // Used by individual conversion commands.
    // Preserves the item's name, lore, enchantments and model data.
    private void applySetPiecePreservingText(ItemStack item,
                                             SetType set, String role) {
        applySetPieceInternal(item, set, role, null, false);
    }

    private void applySetPieceInternal(ItemStack item, SetType set,
                                       String role, GuiSession session,
                                       boolean customizeText) {

        ItemMeta meta = item.getItemMeta();

        if (meta == null) return;

        // Remove previous ArmorSets modifiers before applying new ones.
        for (Attribute attr : new Attribute[]{
                Attribute.ARMOR,
                Attribute.ARMOR_TOUGHNESS,
                Attribute.MAX_HEALTH
        }) {
            var mods = meta.getAttributeModifiers(attr);

            if (mods != null) {
                for (AttributeModifier m : new ArrayList<>(mods)) {
                    if (m.getName().startsWith("armorsets-")) {
                        meta.removeAttributeModifier(attr, m);
                    }
                }
            }
        }

        EquipmentSlot slot = EquipmentSlot.valueOf(
                role.equals("helmet") ? "HEAD" : role.toUpperCase()
        );

        double deltaArmor = set.pieceArmorFraction(role)
                - vanillaArmor(item.getType(), role);

        if (deltaArmor != 0) {
            meta.addAttributeModifier(Attribute.ARMOR,
                    new AttributeModifier(
                            UUID.nameUUIDFromBytes(
                                    ("armorsets-armor-" + role).getBytes()
                            ),
                            "armorsets-armor",
                            deltaArmor,
                            Operation.ADD_NUMBER,
                            slot
                    ));
        }

        double deltaTough = set.pieceToughness()
                - vanillaToughness(item.getType());

        if (deltaTough != 0) {
            meta.addAttributeModifier(Attribute.ARMOR_TOUGHNESS,
                    new AttributeModifier(
                            UUID.nameUUIDFromBytes(
                                    ("armorsets-tough-" + role).getBytes()
                            ),
                            "armorsets-tough",
                            deltaTough,
                            Operation.ADD_NUMBER,
                            slot
                    ));
        }

        if (set.healthPerPiece > 0) {
            meta.addAttributeModifier(Attribute.MAX_HEALTH,
                    new AttributeModifier(
                            UUID.nameUUIDFromBytes(
                                    ("armorsets-health-" + role).getBytes()
                            ),
                            "armorsets-health",
                            set.healthPerPiece,
                            Operation.ADD_NUMBER,
                            slot
                    ));
        }

        if (set == SetType.VENOM && role.equals("chestplate")) {
            int kills = venomKills.getOrDefault(ownerOf(item), 0);
            double bonus = Math.min(1.0, kills * 0.02);

            if (bonus > 0) {
                meta.addAttributeModifier(Attribute.ARMOR,
                        new AttributeModifier(
                                UUID.nameUUIDFromBytes(
                                        "armorsets-venomkills".getBytes()
                                ),
                                "armorsets-venomkills",
                                bonus,
                                Operation.ADD_NUMBER,
                                slot
                        ));
            }
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        pdc.set(keySet, PersistentDataType.STRING, set.name());
        pdc.set(keyRole, PersistentDataType.STRING, role);

        // Only change text when using the GUI or giving a new set.
        if (customizeText) {
            if (session != null && session.name != null) {
                meta.setDisplayName(session.name);
            } else {
                meta.setDisplayName(color(
                        set.color + "&l" + set.display + " " + cap(role)
                ));
            }

            if (session != null && session.lore != null) {
                meta.setLore(session.lore);
            } else {
                meta.setLore(defaultLore(set));
            }

            if (session != null && session.modelData != null) {
                meta.setCustomModelData(session.modelData);
            }
        }

        item.setItemMeta(meta);
    }

    private UUID ownerOf(ItemStack item) {
        return UUID.nameUUIDFromBytes(
                ("piece" + item.hashCode()).getBytes()
        );
    }

    private String cap(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private List<String> defaultLore(SetType set) {
        List<String> lore = new ArrayList<>();

        lore.add(color("&7Armor set: " + set.color + set.display));

        double hearts = set.healthPerPiece * 4 / 2.0;

        lore.add(color("&7Extra hearts: &c" + hearts + " ❤ total"));
        lore.add(color("&7Durability loss: &a-" +
                (int) (set.duraReduction * 100) + "%"));

        switch (set) {
            case EMERALD:
                lore.add(color("&7Passive: &f+5% durability over diamond"));
                break;

            case RUBY:
                lore.add(color("&7Passive: &f+10% armor over diamond"));
                lore.add(color("&bAbility: &fBlind everyone within 15 blocks"));
                lore.add(color("&bfor 3s and heal 1.5 hearts. &8(" +
                        set.cooldownSeconds + "s cd)"));
                break;

            case TITANIUM:
                lore.add(color("&7Passive: &fevery 20th hit is blocked and"));
                lore.add(color("&freflected back at the attacker"));
                lore.add(color("&bAbility: &fdeal 2x the enemy's last hit"));
                lore.add(color("&fback to them. &8(" +
                        set.cooldownSeconds + "s cd)"));
                break;

            case VIBRANIUM:
                lore.add(color("&7Passive: &fevery 80th hit knocks all"));
                lore.add(color("&fnearby enemies away with blindness + slowness"));
                lore.add(color("&bAbility: &fheal 3 hearts. &8(" +
                        set.cooldownSeconds + "s cd)"));
                break;

            case ADAMANTIUM:
                lore.add(color("&7Passive: &fself-repairs 1 durability / 2s"));
                lore.add(color("&bAbility: &farrow nova, 1 heart per arrow +"));
                lore.add(color("&fslowness and poison. &8(" +
                        set.cooldownSeconds + "s cd)"));
                break;

            case VENOM:
                lore.add(color("&7Passive 1: &f+0.1% armor per kill (max 5%)"));
                lore.add(color("&7Passive 2: &fblack concrete trail"));
                lore.add(color("&bAbility: &f2 hearts damage in 7 blocks + freeze. &8(" +
                        set.cooldownSeconds + "s cd)"));
                break;
        }

        return lore;
    }

    private void giveSet(Player p, SetType set) {
        for (int i = 0; i < 4; i++) {
            ItemStack it = new ItemStack(set.defaultMaterial(ROLES[i]));

            applySetPiece(it, set, ROLES[i], null);

            HashMap<Integer, ItemStack> left = p.getInventory().addItem(it);

            for (ItemStack l : left.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), l);
            }
        }
    }

    private double vanillaArmor(Material m, String role) {
        boolean helmet = role.equals("helmet");
        boolean chest = role.equals("chestplate");
        boolean legs = role.equals("leggings");
        boolean boots = role.equals("boots");

        if (helmet) {
            switch (m) {
                case LEATHER_HELMET:
                    return 1;
                case GOLDEN_HELMET:
                case CHAINMAIL_HELMET:
                case IRON_HELMET:
                case TURTLE_HELMET:
                    return 2;
                case DIAMOND_HELMET:
                case NETHERITE_HELMET:
                    return 3;
                default:
                    return 0;
            }
        }

        if (chest) {
            switch (m) {
                case LEATHER_CHESTPLATE:
                    return 3;
                case GOLDEN_CHESTPLATE:
                case CHAINMAIL_CHESTPLATE:
                    return 5;
                case IRON_CHESTPLATE:
                    return 6;
                case DIAMOND_CHESTPLATE:
                case NETHERITE_CHESTPLATE:
                    return 8;
                default:
                    return 0;
            }
        }

        if (legs) {
            switch (m) {
                case LEATHER_LEGGINGS:
                    return 2;
                case GOLDEN_LEGGINGS:
                    return 3;
                case CHAINMAIL_LEGGINGS:
                    return 4;
                case IRON_LEGGINGS:
                    return 5;
                case DIAMOND_LEGGINGS:
                case NETHERITE_LEGGINGS:
                    return 6;
                default:
                    return 0;
            }
        }

        if (boots) {
            switch (m) {
                case LEATHER_BOOTS:
                case GOLDEN_BOOTS:
                case CHAINMAIL_BOOTS:
                    return 1;
                case IRON_BOOTS:
                    return 2;
                case DIAMOND_BOOTS:
                case NETHERITE_BOOTS:
                    return 3;
                default:
                    return 0;
            }
        }

        return 0;
    }

    private double vanillaToughness(Material m) {
        if (m.name().startsWith("DIAMOND")) return 2;
        if (m.name().startsWith("NETHERITE")) return 3;
        return 0;
    }

    // ============================== SET CHECKS ==============================

    private boolean isSetItem(ItemStack item, SetType set) {
        if (item == null || !item.hasItemMeta()) return false;

        String s = item.getItemMeta()
                .getPersistentDataContainer()
                .get(keySet, PersistentDataType.STRING);

        return set.name().equals(s);
    }

    private String roleOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;

        return item.getItemMeta()
                .getPersistentDataContainer()
                .get(keyRole, PersistentDataType.STRING);
    }

    // Bukkit armor slots are checked individually to avoid ordering errors.
    private boolean isFullSet(Player p, SetType set) {
        ItemStack helmet = p.getInventory().getHelmet();
        ItemStack chestplate = p.getInventory().getChestplate();
        ItemStack leggings = p.getInventory().getLeggings();
        ItemStack boots = p.getInventory().getBoots();

        return isSetItem(helmet, set)
                && "helmet".equals(roleOf(helmet))
                && isSetItem(chestplate, set)
                && "chestplate".equals(roleOf(chestplate))
                && isSetItem(leggings, set)
                && "leggings".equals(roleOf(leggings))
                && isSetItem(boots, set)
                && "boots".equals(roleOf(boots));
    }

    // ============================== ABILITIES ==============================

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;

        if (e.getAction() != Action.RIGHT_CLICK_AIR
                && e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player p = e.getPlayer();

        if (!p.isSneaking()) return;

        for (SetType set : SetType.values()) {
            if (set.cooldownSeconds <= 0) continue;
            if (!isFullSet(p, set)) continue;

            long now = System.currentTimeMillis();

            Map<SetType, Long> cds = cooldowns.computeIfAbsent(
                    p.getUniqueId(), k -> new HashMap<>()
            );

            Long until = cds.get(set);

            if (until != null && until > now) {
                long secs = (until - now) / 1000 + 1;

                p.sendMessage(color("&c" + set.display
                        + " ability on cooldown: " + secs + "s"));

                return;
            }

            cds.put(set, now + set.cooldownSeconds * 1000L);

            performAbility(p, set);
            return;
        }
    }

    private void performAbility(Player p, SetType set) {
        Location loc = p.getLocation();

        switch (set) {
            case RUBY: {
                for (Entity ent : loc.getWorld().getNearbyEntities(
                        loc, 15, 15, 15
                )) {
                    if (ent instanceof LivingEntity && ent != p) {
                        ((LivingEntity) ent).addPotionEffect(
                                new PotionEffect(
                                        PotionEffectType.BLINDNESS,
                                        60, 0, false, true
                                )
                        );
                    }
                }

                double max = p.getAttribute(Attribute.MAX_HEALTH).getValue();

                p.setHealth(Math.min(max, p.getHealth() + 3.0));

                p.sendMessage(color(
                        "&4[Ruby] &7A wave of darkness erupts! You feel refreshed."
                ));

                p.playSound(loc, Sound.ENTITY_WITHER_AMBIENT, 1f, 0.6f);
                break;
            }

            case TITANIUM: {
                Double lastDmg = lastHitDamage.get(p.getUniqueId());
                UUID attackerId = lastAttacker.get(p.getUniqueId());

                LivingEntity target = null;

                if (attackerId != null
                        && Bukkit.getEntity(attackerId) instanceof LivingEntity) {
                    target = (LivingEntity) Bukkit.getEntity(attackerId);
                }

                if (target == null) {
                    for (Entity ent : loc.getWorld().getNearbyEntities(
                            loc, 10, 10, 10
                    )) {
                        if (ent instanceof LivingEntity && ent != p) {
                            target = (LivingEntity) ent;
                            break;
                        }
                    }
                }

                double dmg = (lastDmg == null ? 4.0 : lastDmg) * 2.0;

                if (target != null) {
                    target.damage(dmg, p);

                    p.sendMessage(color(
                            "&8[Titanium] &7You strike back for &c"
                                    + (dmg / 2) + " hearts&7!"
                    ));
                } else {
                    p.sendMessage(color(
                            "&8[Titanium] &cNo enemy found to strike."
                    ));
                }

                p.playSound(loc, Sound.ENTITY_IRON_GOLEM_ATTACK, 1f, 0.7f);
                break;
            }

            case VIBRANIUM: {
                double max = p.getAttribute(Attribute.MAX_HEALTH).getValue();

                p.setHealth(Math.min(max, p.getHealth() + 6.0));

                p.sendMessage(color(
                        "&b[Vibranium] &7You absorb energy and heal &c3 hearts&7!"
                ));

                p.playSound(loc, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.4f);
                break;
            }

            case ADAMANTIUM: {
                int count = 16;

                Location spawnLocation = loc.clone().add(0, 1.2, 0);

                for (int i = 0; i < count; i++) {
                    double angle = 2 * Math.PI * i / count;

                    Vector dir = new Vector(
                            Math.cos(angle), 0.15, Math.sin(angle)
                    ).normalize().multiply(1.6);

                    Arrow arrow = loc.getWorld().spawnArrow(
                            spawnLocation.clone(), dir, 3.0f, 0f
                    );

                    arrow.setShooter(p);
                    arrow.setPickupStatus(Arrow.PickupStatus.DISALLOWED);

                    arrow.getPersistentDataContainer().set(
                            keyArrow, PersistentDataType.BYTE, (byte) 1
                    );
                }

                p.sendMessage(color(
                        "&8[Adamantium] &7Adamantine shards erupt around you!"
                ));

                p.playSound(loc, Sound.ENTITY_ARROW_SHOOT, 1f, 0.5f);
                break;
            }

            case VENOM: {
                for (Entity ent : loc.getWorld().getNearbyEntities(
                        loc, 7, 7, 7
                )) {
                    if (ent instanceof LivingEntity && ent != p) {
                        LivingEntity le = (LivingEntity) ent;

                        le.damage(4.0, p);

                        le.addPotionEffect(
                                new PotionEffect(
                                        PotionEffectType.SLOWNESS,
                                        40, 250, false, true
                                )
                        );
                    }
                }

                p.sendMessage(color(
                        "&5[Venom] &7A burst of venom freezes everyone around you!"
                ));

                p.playSound(loc, Sound.ENTITY_SPIDER_AMBIENT, 1f, 0.5f);
                break;
            }

            default:
                break;
        }
    }

    // ============================== PASSIVES ==============================

    @EventHandler(ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Arrow) {
            Arrow arrow = (Arrow) e.getDamager();

            if (arrow.getPersistentDataContainer().has(
                    keyArrow, PersistentDataType.BYTE
            )) {
                e.setDamage(2.0);

                if (e.getEntity() instanceof LivingEntity) {
                    LivingEntity le = (LivingEntity) e.getEntity();

                    le.addPotionEffect(
                            new PotionEffect(
                                    PotionEffectType.SLOWNESS,
                                    40, 0, false, true
                            )
                    );

                    le.addPotionEffect(
                            new PotionEffect(
                                    PotionEffectType.POISON,
                                    40, 0, false, true
                            )
                    );
                }

                arrow.remove();
                return;
            }
        }

        if (!(e.getEntity() instanceof Player)) return;

        Player victim = (Player) e.getEntity();
        double dmg = e.getFinalDamage();

        if (e.getDamager() instanceof LivingEntity) {
            lastHitDamage.put(victim.getUniqueId(), dmg);
            lastAttacker.put(
                    victim.getUniqueId(),
                    e.getDamager().getUniqueId()
            );
        }

        if (isFullSet(victim, SetType.TITANIUM)) {
            int hits = titaniumHits.merge(
                    victim.getUniqueId(), 1, Integer::sum
            );

            if (hits % 20 == 0) {
                e.setCancelled(true);

                victim.sendMessage(color(
                        "&8[Titanium] &7Your armor deflects the blow!"
                ));

                if (e.getDamager() instanceof LivingEntity) {
                    LivingEntity attacker = (LivingEntity) e.getDamager();

                    attacker.damage(dmg, victim);

                    victim.playSound(
                            victim.getLocation(),
                            Sound.ENTITY_IRON_GOLEM_HURT,
                            1f, 0.6f
                    );
                }
            }
        }

        if (isFullSet(victim, SetType.VIBRANIUM)) {
            int hits = vibraniumHits.merge(
                    victim.getUniqueId(), 1, Integer::sum
            );

            if (hits % 80 == 0) {
                for (Entity ent : victim.getWorld().getNearbyEntities(
                        victim.getLocation(), 10, 10, 10
                )) {
                    if (ent instanceof LivingEntity && ent != victim) {
                        LivingEntity le = (LivingEntity) ent;

                        Vector kb = le.getLocation().toVector()
                                .subtract(victim.getLocation().toVector());

                        if (kb.lengthSquared() == 0) {
                            kb = new Vector(1, 0, 0);
                        }

                        kb.setY(0).normalize().multiply(2.5).setY(0.8);

                        le.setVelocity(kb);

                        le.addPotionEffect(
                                new PotionEffect(
                                        PotionEffectType.BLINDNESS,
                                        60, 0, false, true
                                )
                        );

                        le.addPotionEffect(
                                new PotionEffect(
                                        PotionEffectType.SLOWNESS,
                                        60, 0, false, true
                                )
                        );
                    }
                }

                victim.sendMessage(color(
                        "&b[Vibranium] &7A shockwave throws your enemies back!"
                ));

                victim.getWorld().playSound(
                        victim.getLocation(),
                        Sound.ENTITY_GENERIC_EXPLODE,
                        1f, 0.8f
                );
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent e) {
        ItemStack item = e.getItem();

        if (item == null || !item.hasItemMeta()) return;

        String s = item.getItemMeta()
                .getPersistentDataContainer()
                .get(keySet, PersistentDataType.STRING);

        SetType set = SetType.fromName(s);

        if (set == null || set.duraReduction <= 0) return;

        int reduced = (int) Math.ceil(
                e.getDamage() * (1 - set.duraReduction)
        );

        e.setDamage(reduced);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player killer = e.getEntity().getKiller();

        if (killer == null) return;
        if (!isFullSet(killer, SetType.VENOM)) return;

        int kills = venomKills.merge(
                killer.getUniqueId(), 1, Integer::sum
        );

        if (kills > 50) {
            venomKills.put(killer.getUniqueId(), 50);
            return;
        }

        double bonus = Math.min(1.0, kills * 0.02);

        ItemStack chest = killer.getInventory().getChestplate();

        if (chest == null || !isSetItem(chest, SetType.VENOM)) return;

        ItemMeta meta = chest.getItemMeta();

        for (AttributeModifier m : new ArrayList<>(
                meta.getAttributeModifiers(Attribute.ARMOR)
        )) {
            if (m.getName().equals("armorsets-venomkills")) {
                meta.removeAttributeModifier(Attribute.ARMOR, m);
            }
        }

        meta.addAttributeModifier(
                Attribute.ARMOR,
                new AttributeModifier(
                        UUID.nameUUIDFromBytes(
                                "armorsets-venomkills".getBytes()
                        ),
                        "armorsets-venomkills",
                        bonus,
                        Operation.ADD_NUMBER,
                        EquipmentSlot.CHEST
                )
        );

        chest.setItemMeta(meta);

        killer.getInventory().setChestplate(chest);

        killer.sendMessage(color(
                "&5[Venom] &7Kill bonus armor: &a+"
                        + String.format("%.1f", kills * 0.1) + "%"
        ));
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null) return;

        Block from = e.getFrom().getBlock();
        Block to = e.getTo().getBlock();

        if (from.getX() == to.getX()
                && from.getY() == to.getY()
                && from.getZ() == to.getZ()) {
            return;
        }

        Player p = e.getPlayer();

        if (!isFullSet(p, SetType.VENOM)) return;

        Block below = to.getRelative(BlockFace.DOWN);
        Material type = below.getType();

        if (type.isAir()
                || type == Material.WATER
                || type == Material.LAVA) {
            return;
        }

        if (type == Material.BLACK_CONCRETE
                || type == Material.BEDROCK) {
            return;
        }

        for (Region r : regions) {
            if (r.contains(below)) return;
        }

        below.setType(Material.BLACK_CONCRETE);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();

        cooldowns.remove(id);
        titaniumHits.remove(id);
        vibraniumHits.remove(id);
        lastHitDamage.remove(id);
        lastAttacker.remove(id);
        pendingInput.remove(id);
        sessions.remove(id);
    }

    // ============================== VENOM REGIONS ==============================

    private Region pendingPos1 = null;

    private void handleRegionCommand(Player p, String[] args) {
        if (args.length < 1) {
            p.sendMessage(color(
                    "&cUsage: /venomregion <pos1|pos2|list|clear [index]>"
            ));
            return;
        }

        switch (args[0].toLowerCase()) {
            case "pos1": {
                Region r = new Region();

                r.world = p.getWorld();

                r.x1 = p.getLocation().getBlockX();
                r.y1 = p.getLocation().getBlockY();
                r.z1 = p.getLocation().getBlockZ();

                r.x2 = r.x1;
                r.y2 = r.y1;
                r.z2 = r.z1;

                pendingPos1 = r;

                p.sendMessage(color(
                        "&aPosition 1 set at your location. Now stand at the opposite corner and use /venomregion pos2."
                ));
                break;
            }

            case "pos2": {
                if (pendingPos1 == null
                        || !pendingPos1.world.equals(p.getWorld())) {
                    p.sendMessage(color(
                            "&cSet position 1 first with /venomregion pos1 (in the same world)."
                    ));
                    return;
                }

                pendingPos1.x2 = p.getLocation().getBlockX();
                pendingPos1.y2 = p.getLocation().getBlockY();
                pendingPos1.z2 = p.getLocation().getBlockZ();

                regions.add(pendingPos1);

                saveRegions();

                p.sendMessage(color(
                        "&aProtected region #" + (regions.size() - 1)
                                + " created. Blocks inside it are immune to the Venom concrete passive."
                ));

                pendingPos1 = null;
                break;
            }

            case "list": {
                if (regions.isEmpty()) {
                    p.sendMessage(color("&7No protected regions."));
                    return;
                }

                for (int i = 0; i < regions.size(); i++) {
                    Region r = regions.get(i);

                    p.sendMessage(color(
                            "&7#" + i + " &f" + r.world.getName()
                                    + " " + Math.min(r.x1, r.x2)
                                    + "," + Math.min(r.y1, r.y2)
                                    + "," + Math.min(r.z1, r.z2)
                                    + " -> " + Math.max(r.x1, r.x2)
                                    + "," + Math.max(r.y1, r.y2)
                                    + "," + Math.max(r.z1, r.z2)
                    ));
                }
                break;
            }

            case "clear": {
                if (args.length < 2) {
                    regions.clear();
                    saveRegions();

                    p.sendMessage(color("&aAll regions cleared."));
                    return;
                }

                try {
                    int idx = Integer.parseInt(args[1]);

                    if (idx < 0 || idx >= regions.size()) {
                        p.sendMessage(color("&cNo region with that index."));
                        return;
                    }

                    regions.remove(idx);
                    saveRegions();

                    p.sendMessage(color("&aRegion removed."));
                } catch (NumberFormatException ex) {
                    p.sendMessage(color("&cUsage: /venomregion clear [index]"));
                }

                break;
            }

            default:
                p.sendMessage(color(
                        "&cUsage: /venomregion <pos1|pos2|list|clear [index]>"
                ));
        }
    }

    private void saveRegions() {
        getConfig().set("regions", null);

        for (int i = 0; i < regions.size(); i++) {
            Region r = regions.get(i);

            String base = "regions." + i + ".";

            getConfig().set(base + "world", r.world.getName());

            getConfig().set(base + "x1", r.x1);
            getConfig().set(base + "y1", r.y1);
            getConfig().set(base + "z1", r.z1);

            getConfig().set(base + "x2", r.x2);
            getConfig().set(base + "y2", r.y2);
            getConfig().set(base + "z2", r.z2);
        }

        saveConfig();
    }

    private void loadRegions() {
        ConfigurationSection sec =
                getConfig().getConfigurationSection("regions");

        if (sec == null) return;

        for (String key : sec.getKeys(false)) {
            String base = "regions." + key + ".";

            World w = Bukkit.getWorld(
                    getConfig().getString(base + "world", "")
            );

            if (w == null) continue;

            Region r = new Region();

            r.world = w;

            r.x1 = getConfig().getInt(base + "x1");
            r.y1 = getConfig().getInt(base + "y1");
            r.z1 = getConfig().getInt(base + "z1");

            r.x2 = getConfig().getInt(base + "x2");
            r.y2 = getConfig().getInt(base + "y2");
            r.z2 = getConfig().getInt(base + "z2");

            regions.add(r);
        }
    }

    // ============================== UTIL ==============================

    private ItemStack button(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);

        ItemMeta meta = it.getItemMeta();

        meta.setDisplayName(color(name));

        if (lore != null) {
            List<String> l = new ArrayList<>();

            for (String line : lore) {
                l.add(color(line));
            }

            meta.setLore(l);
        }

        it.setItemMeta(meta);

        return it;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}