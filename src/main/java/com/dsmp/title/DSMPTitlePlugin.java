package com.dsmp.title;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.PrefixNode;
import net.milkbowl.vault.economy.Economy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class DSMPTitlePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private Economy economy;
    private LuckPerms luckPerms;
    private File dataFile;
    private YamlConfiguration data;
    private final Map<UUID, BukkitTask> waitingTasks = new ConcurrentHashMap<>();
    private final Set<UUID> waiting = ConcurrentHashMap.newKeySet();
    private enum PaymentType {
        ECONOMY,
        XP,
        ITEM
    }

    private static final class PendingPayment {
        final double amount;
        final Material item;
        final PaymentType type;

        PendingPayment(double amount, Material item, PaymentType type) {
            this.amount = amount;
            this.item = item;
            this.type = type;
        }

        boolean isItem() {
            return type == PaymentType.ITEM;
        }

        boolean isXp() {
            return type == PaymentType.XP;
        }
    }

    private final Map<UUID, PendingPayment> pendingPayments = new ConcurrentHashMap<>();
    private final Map<UUID, GuiHolder> openGuis = new ConcurrentHashMap<>();
    private List<String> bannedWords = List.of();
    private YamlConfiguration lang;
    private File langFile;
    private String language;
    private YamlConfiguration groupConfig;
    private File groupFile;
    private NamespacedKey titleTextKey;
    private NamespacedKey titleColorKey;
    private NamespacedKey titleWeightKey;

    private static final class GuiHolder implements InventoryHolder {
        private final UUID viewer, owner;
        private final boolean readOnly;
        private final int page;
        private Inventory inventory;
        GuiHolder(UUID viewer, UUID owner, boolean readOnly, int page) {
            this.viewer = viewer;
            this.owner = owner;
            this.readOnly = readOnly;
            this.page = page;
        }
        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private String getTitlePrefixWrap(){
        return getConfig().getString("title-prefix-wrap", "[");
    }
    private String getTitleSuffixWrap(){
        return getConfig().getString("title-suffix-wrap", "]");
    }
    private String getDefaultBuyTitleColor(){
        return getConfig().getString("default-buy-title-color", "&d");
    }

    /** 获取玩家激活称号索引；-1代表无激活，兼容旧active文本格式 */
    private int getActiveIndex(UUID uuid){
        String base = uuid.toString();
        if(data.isInt(base + ".active-index")){
            int idx = data.getInt(base + ".active-index", -1);
            List<Map<String,Object>> titles = (List<Map<String,Object>>) data.getList(base+".titles");
            if(titles == null) return -1;
            if(idx >=0 && idx < titles.size()) return idx;
            return -1;
        }
        // 旧版本兼容：读取旧active字符串，自动换算索引
        String oldActiveText = data.getString(base+".active");
        if(oldActiveText == null || oldActiveText.isBlank()){
            return -1;
        }
        List<Map<String,Object>> titles = (List<Map<String,Object>>) data.getList(base+".titles");
        if(titles == null) return -1;
        for(int i=0;i<titles.size();i++){
            Map<String,Object> entry = titles.get(i);
            String t = entry.get("title") == null ? "" : String.valueOf(entry.get("title"));
            if(oldActiveText.equals(t)){
                data.set(base+".active-index", i);
                return i;
            }
        }
        data.set(base+".active-index", -1);
        return -1;
    }

    private void setActiveIndex(UUID uuid, int index){
        String base = uuid.toString();
        List<Map<String,Object>> titles = (List<Map<String,Object>>) data.getList(base+".titles");
        if(index <0 || titles == null || index >= titles.size()){
            data.set(base+".active-index", -1);
        }else{
            data.set(base+".active-index", index);
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        titleTextKey = new NamespacedKey(this, "stored_title_text");
        titleColorKey = new NamespacedKey(this, "stored_title_color");
        titleWeightKey = new NamespacedKey(this, "stored_title_weight");

        // 修复：data.yml不打包进jar，运行自动创建空白文件，不再调用saveResource
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                if(getDataFolder().exists() || getDataFolder().mkdirs()){
                    dataFile.createNewFile();
                }
            } catch (IOException e) {
                getLogger().severe("无法创建data.yml：" + e.getMessage());
            }
        }
        data = YamlConfiguration.loadConfiguration(dataFile);

        try {
            luckPerms = LuckPermsProvider.get();
        } catch (Exception e) {
            getLogger().severe("未找到 LuckPerms API！插件已禁用。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        setupEconomy();
        loadLanguage();
        loadGroupConfig();
        loadRuntimeConfig();
        applyGroupTitles();

        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand cmd = Objects.requireNonNull(getCommand("dsmptitle"));
        cmd.setExecutor(this);
        cmd.setTabCompleter(this);
        getLogger().info("DSMPTitle 已启用。");
    }

    private void loadGroupConfig() {
        groupFile = new File(getDataFolder(), "group.yml");
        if (!groupFile.exists()) {
            try {
                if (!getDataFolder().exists()) getDataFolder().mkdirs();
                saveResource("group.yml", false);
            } catch (IllegalArgumentException ex) {
                try { groupFile.createNewFile(); } catch (IOException ignored) {}
            } catch (Exception ex) {
                getLogger().warning("无法创建 group.yml: " + ex.getMessage());
            }
        }
        groupConfig = YamlConfiguration.loadConfiguration(groupFile);
    }

    /**
     * 将 group.yml 中的 LuckPerms 权限组称号同步到对应的 Group PrefixNode
     *
     * 注意：这里不能使用 LuckPerms Context 作为“插件节点标记”，因为 Context
     * 会参与节点生效判断，普通玩家没有 dsmptitle=group 时称号就不会生效
     * 组称号改为普通 PrefixNode，并使用 data.yml 记录本插件上一次写入的节点，
     * 同时兼容清理 2.0.5 之前错误添加的 dsmptitle=group 节点
     */
    private void applyGroupTitles() {
        if (groupConfig == null || data == null) return;

        // 先清理本插件记录过的旧组称号，以及 2.0.5 错误添加的 Context 节点
        for (Group group : luckPerms.getGroupManager().getLoadedGroups()) {
            String groupName = group.getName();
            String base = "group-titles." + groupName;

            String oldPrefix = data.getString(base + ".prefix", null);
            int oldWeight = data.getInt(base + ".weight", -1);
            if (oldPrefix != null && oldWeight > 0) {
                group.data().toCollection().stream()
                        .filter(PrefixNode.class::isInstance)
                        .map(PrefixNode.class::cast)
                        .filter(node -> node.getPriority() == oldWeight
                                && node.getMetaValue().equals(oldPrefix)
                                && node.getContexts().isEmpty())
                        .toList()
                        .forEach(node -> group.data().remove(node));
            }

            // 清理 2.0.5 使用 Context 标记产生的旧节点
            group.data().toCollection().stream()
                    .filter(PrefixNode.class::isInstance)
                    .map(PrefixNode.class::cast)
                    .filter(this::isLegacyDsmpTitleGroupNode)
                    .toList()
                    .forEach(node -> group.data().remove(node));

            // 清理旧版 default-title 迁移遗留的普通 PrefixNode
            if (groupName.equalsIgnoreCase("default")) {
                String legacyPrefix = buildGroupPrefix(
                        getConfig().getString("default-title-color", "&6"),
                        getConfig().getString("default-title", "萌新")
                );
                int legacyWeight = getConfig().getInt("default-title-weight", 1);
                group.data().toCollection().stream()
                        .filter(PrefixNode.class::isInstance)
                        .map(PrefixNode.class::cast)
                        .filter(node -> node.getPriority() == legacyWeight
                                && node.getMetaValue().equals(legacyPrefix)
                                && node.getContexts().isEmpty())
                        .toList()
                        .forEach(node -> group.data().remove(node));
            }

            data.set(base, null);
            luckPerms.getGroupManager().saveGroup(group);
        }

        // group.yml 留空时，上面的清理完成后不会重新添加任何组称号
        for (String groupName : groupConfig.getKeys(false)) {
            if (groupName == null || groupName.isBlank()) continue;
            String path = groupName + ".";
            String title = groupConfig.getString(path + "title", "");
            if (title == null || title.isBlank()) continue;

            String color = groupConfig.getString(path + "color", "&7");
            int weight = groupConfig.getInt(path + "weight", 1);
            if (weight <= 0) {
                getLogger().warning("group.yml 中权限组 " + groupName + " 的 weight 必须大于 0，已跳过");
                continue;
            }

            Group group = luckPerms.getGroupManager().getGroup(groupName);
            if (group == null) {
                getLogger().warning("LuckPerms 找不到 group.yml 中配置的权限组: " + groupName);
                continue;
            }

            try {
                String prefix = buildGroupPrefix(color, title);

                // 关键修复：不再添加任何 Context，让普通玩家可以正常继承并显示该组称号
                PrefixNode node = PrefixNode.builder(prefix, weight).build();
                group.data().add(node);

                String base = "group-titles." + group.getName();
                data.set(base + ".prefix", prefix);
                data.set(base + ".weight", weight);
                luckPerms.getGroupManager().saveGroup(group);
            } catch (Exception ex) {
                getLogger().warning("设置权限组称号失败 " + groupName + ": " + ex.getMessage());
            }
        }

        saveData();
    }

    private String buildGroupPrefix(String color, String title) {
        String rawPrefix = color + getTitlePrefixWrap() + title + getTitleSuffixWrap();
        return serializeLegacy(miniMessage(rawPrefix))
                + toLegacyCodes(getConfig().getString("separator-color", "&7"));
    }

    /** 兼容清理 2.0.5 错误使用 Context 标记的组称号 */
    private boolean isLegacyDsmpTitleGroupNode(PrefixNode node) {
        return node.getContexts().contains("dsmptitle", "group");
    }

    @Override
    public void onDisable() {
        for (BukkitTask task : waitingTasks.values()) task.cancel();
        waitingTasks.clear();
        waiting.clear();
        pendingPayments.clear();
        openGuis.clear();
        saveData();
    }

    private boolean setupEconomy() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        economy = rsp == null ? null : rsp.getProvider();
        if (economy == null) {
            getLogger().info("未找到 Vault 经济服务，只有 use-item: economy 时才需要 Vault");
            return false;
        }
        return true;
    }

    /**
     * 支付方式：
     * economy = Vault 经济
     * xp      = 玩家经验等级
     * 其他值  = Bukkit Material 物品
     */
    private PaymentType getPaymentType() {
        String raw = getConfig().getString("use-item", "economy");
        if (raw == null) return null;
        raw = raw.trim();
        if (raw.equalsIgnoreCase("economy")) return PaymentType.ECONOMY;
        if (raw.equalsIgnoreCase("xp")) return PaymentType.XP;
        if (raw.isEmpty()) return null;
        return Material.matchMaterial(raw) != null ? PaymentType.ITEM : null;
    }

    private Material configuredPaymentItem() {
        String raw = getConfig().getString("use-item", "");
        if (raw == null || raw.trim().isEmpty()
                || raw.trim().equalsIgnoreCase("economy")
                || raw.trim().equalsIgnoreCase("xp")) {
            return null;
        }
        Material material = Material.matchMaterial(raw.trim());
        if (material == null) {
            getLogger().warning("配置 use-item 无效: " + raw);
        }
        return material;
    }

    private int xpLevelCost(double amount) {
        return Math.max(0, (int) Math.ceil(amount));
    }

    private boolean hasPayment(Player player, double amount, PaymentType type) {
        return switch (type) {
            case ECONOMY -> economy != null && economy.has(player, amount);
            case XP -> player.getLevel() >= xpLevelCost(amount);
            case ITEM -> {
                Material material = configuredPaymentItem();
                yield material != null && countItems(player, material) >= (int) Math.ceil(amount);
            }
        };
    }

    private boolean takePayment(Player player, double amount, PaymentType type) {
        return switch (type) {
            case ECONOMY -> economy != null && economy.withdrawPlayer(player, amount).transactionSuccess();
            case XP -> {
                int levels = xpLevelCost(amount);
                if (player.getLevel() < levels) {
                    yield false;
                }
                player.setLevel(player.getLevel() - levels);
                yield true;
            }
            case ITEM -> {
                Material material = configuredPaymentItem();
                int count = (int) Math.ceil(amount);
                if (material == null || countItems(player, material) < count) {
                    yield false;
                }
                removeItems(player, material, count);
                yield true;
            }
        };
    }

    private void refundPayment(Player player, PendingPayment payment) {
        if (payment == null) return;
        switch (payment.type) {
            case ITEM -> giveItems(player, payment.item, (int) Math.ceil(payment.amount));
            case XP -> player.setLevel(player.getLevel() + xpLevelCost(payment.amount));
            case ECONOMY -> {
                if (economy != null) {
                    economy.depositPlayer(player, payment.amount);
                }
            }
        }
    }

    private int countItems(Player player, Material material) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == material) total += stack.getAmount();
        }
        return total;
    }

    private void removeItems(Player player, Material material, int amount) {
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != material) continue;
            int take = Math.min(remaining, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            if (stack.getAmount() <= 0) contents[i] = null;
            remaining -= take;
        }
        player.getInventory().setStorageContents(contents);
    }

    private void giveItems(Player player, Material material, int amount) {
        if (material == null || amount <= 0) return;
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(new ItemStack(material, amount));
        leftovers.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
    }

    private void loadRuntimeConfig() {
        bannedWords = getConfig().getStringList("banned-words").stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }

    private void loadLanguage() {
        language = getConfig().getString("language", "zh_CN");
        if (language == null || language.isBlank()) language = "zh_CN";
        language = language.equalsIgnoreCase("en_US") ? "en_US" : "zh_CN";
        String resourceName = language.equals("en_US") ? "lang/en_US.yml" : "lang/zh_CN.yml";
        langFile = new File(getDataFolder(), resourceName);
        File parent = langFile.getParentFile();
        if (!parent.exists()) parent.mkdirs();
        if (!langFile.exists()) {
            try {
                saveResource(resourceName, false);
            } catch (IllegalArgumentException ex) {
                try { langFile.createNewFile(); } catch (IOException ignored) {}
            }
        }
        lang = YamlConfiguration.loadConfiguration(langFile);
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("无法保存 data.yml: " + e.getMessage());
        }
    }

    private String getPlayerEffectivePrefix(Player player){
        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if(user == null) return "";
        CachedMetaData meta = user.getCachedData().getMetaData();
        String pre = meta.getPrefix();
        return pre == null ? "" : pre;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(msg("usage"));
            if (sender.hasPermission("dsmptitle.reload") || sender.hasPermission("dsmptitle.set") || sender.hasPermission("dsmptitle.remove") || sender.hasPermission("dsmptitle.menu.other") || sender.hasPermission("dsmptitle.clear.other")) sender.sendMessage(msg("admin-usage"));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            if(args.length == 1 && !sender.hasPermission("dsmptitle.reload")){
                sender.sendMessage(msg("no-permission"));
                return true;
            }
            if(args.length == 1){
                reloadConfig();
                loadLanguage();
                loadGroupConfig();
                loadRuntimeConfig();
                setupEconomy();
                applyGroupTitles();
                data = YamlConfiguration.loadConfiguration(dataFile);
                sender.sendMessage(msg("reload-all-success"));
                return true;
            }
            if(args.length == 2){
                if(args[1].equalsIgnoreCase("config")){
                    if (!sender.hasPermission("dsmptitle.reload.config")) { sender.sendMessage(msg("no-permission")); return true; }
                    reloadConfig();
                    loadRuntimeConfig();
                    setupEconomy();
                    applyGroupTitles();
                    sender.sendMessage(msg("reload-config-success"));
                    return true;
                }
                if(args[1].equalsIgnoreCase("data")){
                    if (!sender.hasPermission("dsmptitle.reload.data")) { sender.sendMessage(msg("no-permission")); return true; }
                    data = YamlConfiguration.loadConfiguration(dataFile);
                    sender.sendMessage(msg("reload-data-success"));
                    return true;
                }
                if(args[1].equalsIgnoreCase("lang")){
                    if (!sender.hasPermission("dsmptitle.reload.lang")) { sender.sendMessage(msg("no-permission")); return true; }
                    loadLanguage();
                    sender.sendMessage(msg("reload-lang-success"));
                    return true;
                }
                if(args[1].equalsIgnoreCase("group")){
                    if (!sender.hasPermission("dsmptitle.reload.group")) { sender.sendMessage(msg("no-permission")); return true; }
                    loadGroupConfig();
                    applyGroupTitles();
                    sender.sendMessage(msg("reload-group-success"));
                    return true;
                }
            }
            sender.sendMessage(msg("reload-usage"));
            return true;
        }
        if(sub.equals("set")){
            if(!sender.hasPermission("dsmptitle.set")){
                sender.sendMessage(msg("no-permission"));
                return true;
            }
            if(args.length <5){
                sender.sendMessage(msg("set-usage"));
                return true;
            }
            String targetName = args[1];
            String titleText = args[2];
            String colorCode = args[3];
            int weight;
            try{
                weight = Integer.parseInt(args[4]);
            }catch (NumberFormatException e){
                sender.sendMessage(msg("set-weight-not-number"));
                return true;
            }
            if(weight <=0){
                sender.sendMessage(msg("weight-invalid"));
                return true;
            }
            UUID targetUuid = findKnownPlayer(targetName);
            if(targetUuid == null){
                sender.sendMessage(msg("player-not-found", Map.of("player",targetName)));
                return true;
            }
            if(titleText.isBlank()){
                sender.sendMessage(msg("set-title-too-short"));
                return true;
            }
            String sepColor = getConfig().getString("separator-color","&7");
            // 不能把 separator-color 作为 MiniMessage 末尾的颜色标签直接序列化
            // 因为组件末尾没有文字承载这个颜色，LegacyComponentSerializer 会丢掉尾部颜色
            // 因此先序列化称号本体，再手动追加 Legacy 颜色代码，让后面的玩家名称继承该颜色
            String titleRaw = colorCode + getTitlePrefixWrap() + titleText + getTitleSuffixWrap();
            String coloredPrefix = serializeLegacy(miniMessage(titleRaw)) + toLegacyCodes(sepColor);
            boolean ok;
            try{
                User user = luckPerms.getUserManager().loadUser(targetUuid).join();
                int oldConfigWeight = getConfig().getInt("prefix-weight",500);
                user.data().toCollection().stream()
                        .filter(PrefixNode.class::isInstance)
                        .map(PrefixNode.class::cast)
                        .filter(node -> node.getPriority() == oldConfigWeight)
                        .toList()
                        .forEach(node -> user.data().remove(node));
                PrefixNode newNode = PrefixNode.builder(coloredPrefix, weight).build();
                user.data().add(newNode);
                luckPerms.getUserManager().saveUser(user);
                ok = true;
            }catch (Exception e){
                getLogger().warning("/dt set 设置称号失败:"+e.getMessage());
                ok = false;
            }
            if(!ok){
                sender.sendMessage(msg("set-failed"));
                return true;
            }
            int newIndex = addOwnedTitle(targetUuid, targetName, titleText, colorCode, weight);
            setActiveIndex(targetUuid, newIndex);
            saveData();
            Map<String,String> vars = new HashMap<>();
            vars.put("player",targetName);
            vars.put("title",coloredPrefix);
            vars.put("weight",String.valueOf(weight));
            sender.sendMessage(msg("set-success",vars));
            return true;
        }
        if(sub.equals("remove")){
            if(!sender.hasPermission("dsmptitle.remove")){
                sender.sendMessage(msg("no-permission"));
                return true;
            }
            if(args.length <3){
                sender.sendMessage(msg("remove-usage"));
                return true;
            }
            String targetName = args[1];
            String delTitle = String.join(" ", Arrays.copyOfRange(args,2,args.length));
            delTitle = delTitle.replaceAll("^\"|\"$", "");
            UUID targetUuid = findKnownPlayer(targetName);
            if(targetUuid == null){
                sender.sendMessage(msg("player-not-found", Map.of("player",targetName)));
                return true;
            }
            String base = targetUuid.toString();
            List<Map<String,Object>> rawList = (List<Map<String,Object>>)data.getList(base + ".titles");
            if(rawList == null) rawList = new ArrayList<>();
            boolean found = false;
            int activeIdx = getActiveIndex(targetUuid);
            boolean deleteIsActive = false;
            List<Map<String,Object>> newList = new ArrayList<>();
            for(int i=0;i<rawList.size();i++){
                Map<String,Object> entry = rawList.get(i);
                String t = String.valueOf(entry.get("title"));
                if(!delTitle.equalsIgnoreCase(t)){
                    newList.add(entry);
                }else{
                    found = true;
                    if(i == activeIdx) deleteIsActive = true;
                }
            }
            if(!found){
                sender.sendMessage(msg("remove-not-found", Map.of("title", delTitle)));
                return true;
            }
            data.set(base+".titles", newList);
            if(deleteIsActive){
                clearUserTitle(targetUuid);
            }
            saveData();
            sender.sendMessage(msg("remove-success", Map.of("player", targetName, "title", delTitle)));
            return true;
        }
        if(sub.equals("removebyindex")){
            if(!sender.hasPermission("dsmptitle.remove")){
                sender.sendMessage(msg("no-permission"));
                return true;
            }
            if(args.length <3){
                sender.sendMessage(msg("remove-index-usage"));
                return true;
            }
            String targetName = args[1];
            int idx;
            try{
                idx = Integer.parseInt(args[2]);
            }catch (NumberFormatException e){
                sender.sendMessage(msg("index-invalid"));
                return true;
            }
            UUID targetUuid = findKnownPlayer(targetName);
            if(targetUuid == null){
                sender.sendMessage(msg("player-not-found", Map.of("player",targetName)));
                return true;
            }
            String base = targetUuid.toString();
            List<Map<String,Object>> rawList = (List<Map<String,Object>>)data.getList(base + ".titles");
            if(rawList == null) rawList = new ArrayList<>();
            if(idx <0 || idx >= rawList.size()){
                sender.sendMessage(msg("index-out-of-range", Map.of("size", String.valueOf(rawList.size()))));
                return true;
            }
            Map<String,Object> delEntry = rawList.get(idx);
            String delTitleText = String.valueOf(delEntry.get("title"));
            int activeIdx = getActiveIndex(targetUuid);
            boolean isDeleteActive = (idx == activeIdx);
            rawList.remove(idx);
            data.set(base+".titles", rawList);
            if(isDeleteActive){
                clearUserTitle(targetUuid);
            }else{
                // 删除条目在激活索引前面，激活索引减一
                if(activeIdx > idx){
                    setActiveIndex(targetUuid, activeIdx - 1);
                }
            }
            saveData();
            sender.sendMessage(msg("remove-index-success", Map.of("title", delTitleText)));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("console-player-only"));
            return true;
        }
        switch (sub) {
            case "buy" -> {
                if (!player.hasPermission("dsmptitle.buy")) { player.sendMessage(msg("no-permission")); return true; }
                buy(player);
            }
            case "cancel" -> {
                if (!player.hasPermission("dsmptitle.cancel")) { player.sendMessage(msg("no-permission")); return true; }
                cancel(player, true);
            }
            case "clear" -> {
                if (args.length >= 2) {
                    if (!player.hasPermission("dsmptitle.clear.other")) { player.sendMessage(msg("no-permission")); return true; }
                    UUID target = findKnownPlayer(args[1]);
                    if (target == null) {
                        player.sendMessage(msg("player-not-found", Map.of("player", args[1])));
                        return true;
                    }
                    clearUserTitle(target);
                    player.sendMessage(msg("clear-other-success", Map.of("player", args[1])));
                } else {
                    if (!player.hasPermission("dsmptitle.clear")) { player.sendMessage(msg("no-permission")); return true; }
                    clearPlayerTitle(player);
                }
            }
            case "gui", "menu" -> {
                if (args.length >= 2) {
                    if (!player.hasPermission("dsmptitle.menu.other")) {
                        player.sendMessage(msg("no-permission"));
                        return true;
                    }
                    UUID target = findKnownPlayer(args[1]);
                    if (target == null) {
                        player.sendMessage(msg("player-not-found", Map.of("player", args[1])));
                        return true;
                    }
                    openGui(player, target, true);
                } else {
                    if (!player.hasPermission("dsmptitle.menu")) { player.sendMessage(msg("no-permission")); return true; }
                    openGui(player, player.getUniqueId(), false);
                }
            }
            default -> {
                player.sendMessage(msg("usage"));
                if (player.hasPermission("dsmptitle.reload") || player.hasPermission("dsmptitle.set") || player.hasPermission("dsmptitle.remove") || player.hasPermission("dsmptitle.menu.other") || player.hasPermission("dsmptitle.clear.other")) player.sendMessage(msg("admin-usage"));
            }
        }
        return true;
    }

    private void clearUserTitle(UUID uuid){
        try{
            User user = luckPerms.getUserManager().loadUser(uuid).join();
            user.data().toCollection().stream()
                    .filter(PrefixNode.class::isInstance)
                    .map(PrefixNode.class::cast)
                    .filter(node -> node.getPriority() > 1)
                    .toList()
                    .forEach(node -> user.data().remove(node));
            luckPerms.getUserManager().saveUser(user);
            setActiveIndex(uuid, -1);
            saveData();
        }catch (Exception e){
            getLogger().warning("clearUserTitle异常："+e.getMessage());
        }
    }

    private void clearPlayerTitle(Player p){
        UUID uuid = p.getUniqueId();
        clearUserTitle(uuid);
        p.sendMessage(msg("clear-title-success"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> list = new ArrayList<>(List.of("buy", "cancel", "clear", "gui", "menu"));
            if (sender.hasPermission("dsmptitle.reload")) list.add("reload");
            if (sender.hasPermission("dsmptitle.set")) list.add("set");
            if (sender.hasPermission("dsmptitle.remove")) { list.add("remove"); list.add("removebyindex"); }
            return partial(list, args[0]);
        }
        if(args.length ==2 && args[0].equalsIgnoreCase("reload") && sender.hasPermission("dsmptitle.reload")){
            return partial(List.of("config","data","lang","group"), args[1]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("gui") || args[0].equalsIgnoreCase("menu")) && sender.hasPermission("dsmptitle.menu.other")) {
            List<String> names = new ArrayList<>();
            for (String key : data.getKeys(false)) {
                String name = data.getString(key + ".name");
                if (name != null) names.add(name);
            }
            for (Player p : Bukkit.getOnlinePlayers()) if (!names.contains(p.getName())) names.add(p.getName());
            return partial(names, args[1]);
        }
        if(args.length ==2 && args[0].equalsIgnoreCase("clear") && sender.hasPermission("dsmptitle.clear.other")){
            List<String> names = new ArrayList<>();
            for (String key : data.getKeys(false)) {
                String name = data.getString(key + ".name");
                if (name != null) names.add(name);
            }
            for (Player p : Bukkit.getOnlinePlayers()) if (!names.contains(p.getName())) names.add(p.getName());
            return partial(names, args[1]);
        }
        if(args.length ==2 && args[0].equalsIgnoreCase("set") && sender.hasPermission("dsmptitle.set")){
            return partial(knownPlayerNames(), args[1]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("removebyindex"))
                && sender.hasPermission("dsmptitle.remove")) {
            return partial(knownPlayerNames(), args[1]);
        }
        if (args.length >= 3 && (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("removebyindex"))
                && sender.hasPermission("dsmptitle.remove")) {
            // <titles> / <index> intentionally has no tab completion
            return List.of();
        }
        if(args.length ==3 && args[0].equalsIgnoreCase("set")){
            return List.of();
        }
        if(args.length ==4 && args[0].equalsIgnoreCase("set")){
            return partial(List.of("&0","&1","&2","&3","&4","&5","&6","&7","&8","&9","&a","&b","&c","&d","&e","&f"),args[3]);
        }
        if(args.length ==5 && args[0].equalsIgnoreCase("set")){
            return partial(List.of("300","500","600","700","800","1000"),args[4]);
        }
        return List.of();
    }

    private List<String> partial(List<String> list, String input) {
        return list.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(input.toLowerCase(Locale.ROOT))).sorted().toList();
    }

    private void buy(Player p) {
        UUID uuid = p.getUniqueId();
        if (waiting.contains(uuid)) {
            p.sendMessage(msg("already-waiting"));
            return;
        }

        double price = getConfig().getDouble("price", 60000.0);
        PaymentType paymentType = getPaymentType();
        Material paymentItem = paymentType == PaymentType.ITEM ? configuredPaymentItem() : null;

        if (paymentType == null || (paymentType == PaymentType.ITEM && paymentItem == null)) {
            p.sendMessage(msg("payment-config-invalid"));
            return;
        }
        if (paymentType == PaymentType.ECONOMY && economy == null) {
            p.sendMessage(msg("vault-required"));
            return;
        }

        if (!hasPayment(p, price, paymentType)) {
            String key = switch (paymentType) {
                case ECONOMY -> "no-money";
                case XP -> "xp-no-money";
                case ITEM -> "item-no-money";
            };
            Map<String, String> vars = new HashMap<>();
            vars.put("price", formatMoney(price));
            vars.put("xp", String.valueOf(xpLevelCost(price)));
            vars.put("item", paymentItem == null ? "" : paymentItem.name().toLowerCase(Locale.ROOT));
            p.sendMessage(msg(key, vars));
            return;
        }

        if (!takePayment(p, price, paymentType)) {
            p.sendMessage(msg(paymentType == PaymentType.ECONOMY ? "withdraw-failed" : "payment-failed"));
            return;
        }

        PendingPayment pending = new PendingPayment(price, paymentItem, paymentType);
        pendingPayments.put(uuid, pending);

        Map<String, String> chargedVars = new HashMap<>();
        chargedVars.put("price", formatMoney(price));
        chargedVars.put("xp", String.valueOf(xpLevelCost(price)));
        chargedVars.put("item", paymentItem == null ? "" : paymentItem.name().toLowerCase(Locale.ROOT));
        String chargedKey = switch (paymentType) {
            case ECONOMY -> "economy-success";
            case XP -> "xp-success";
            case ITEM -> "item-success";
        };
        // 将购买成功、输入提示、格式提示和取消提示合并为一条聊天消息
        // 避免部分客户端/聊天插件在同一 tick 连续发送消息时丢失第一行
        String purchaseStartMessage = String.join("\n",
                msg(chargedKey, chargedVars),
                msg("prompt", Map.of("seconds", String.valueOf(waitSeconds()))),
                msg("format", Map.of("min", String.valueOf(minChars()), "max", String.valueOf(maxChars()))),
                msg("cancel-help")
        );
        p.sendMessage(purchaseStartMessage);

        waiting.add(uuid);
        int seconds = waitSeconds();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            int left = seconds;

            @Override
            public void run() {
                if (!waiting.contains(uuid) || !p.isOnline()) {
                    cancelTask(uuid);
                    return;
                }
                left--;
                String key = switch (left) {
                    case 10 -> "countdown-10";
                    case 5 -> "countdown-5";
                    case 3 -> "countdown-3";
                    case 2 -> "countdown-2";
                    case 1 -> "countdown-1";
                    default -> null;
                };
                if (key != null) p.sendMessage(msg(key));

                if (left <= 0) {
                    PendingPayment paid = pendingPayments.get(uuid);
                    finishWaiting(uuid);
                    refundPayment(p, paid);

                    if (paid != null) {
                        Map<String, String> vars = new HashMap<>();
                        vars.put("price", formatMoney(paid.amount));
                        vars.put("xp", String.valueOf(xpLevelCost(paid.amount)));
                        vars.put("item", paid.item == null ? "" : paid.item.name().toLowerCase(Locale.ROOT));
                        String timeoutKey = paid.isItem() ? "timeout-item" : paid.isXp() ? "timeout-xp" : "timeout";
                        p.sendMessage(msg(timeoutKey, vars));
                    }
                }
            }
        }, 20L, 20L);
        waitingTasks.put(uuid, task);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        UUID uuid = p.getUniqueId();
        if (waiting.contains(uuid)){
            event.setCancelled(true);
            String input = event.getMessage();
            Bukkit.getScheduler().runTask(this, () -> processInput(p, input));
            return;
        }
        boolean chatFormatEnable = getConfig().getBoolean("enable-chat-format", true);
        if(!chatFormatEnable) return;
        event.setCancelled(true);
        String template = getConfig().getString("default-chat","%title%%player%: &r%message%");
        String prefix = getPlayerEffectivePrefix(p);
        String raw = template
                .replace("%title%", prefix)
                .replace("%player%", p.getName())
                .replace("%message%", event.getMessage());
        Component finalMsg = miniMessage(raw);
        for(Player online : Bukkit.getOnlinePlayers()){
            online.sendMessage(finalMsg);
        }
        Bukkit.getConsoleSender().sendMessage(finalMsg);
    }

    private void processInput(Player p, String input) {
        UUID uuid = p.getUniqueId();
        if (!waiting.contains(uuid)) return;
        PendingPayment payment = pendingPayments.get(uuid);
        double paid = payment == null ? getConfig().getDouble("price", 60000.0) : payment.amount;
        finishWaiting(uuid);
        Map<String,String> vars = new HashMap<>();
        vars.put("price", formatMoney(paid));
        vars.put("min", String.valueOf(minChars()));
        vars.put("max", String.valueOf(maxChars()));
        vars.put("xp", String.valueOf(payment == null ? xpLevelCost(paid) : xpLevelCost(payment.amount)));
        vars.put("item", payment != null && payment.item != null ? payment.item.name().toLowerCase(Locale.ROOT) : "");
        String lower = input.toLowerCase(Locale.ROOT);
        if (bannedWords.stream().anyMatch(lower::contains)) {
            refundPayment(p, payment);
            p.sendMessage(msg(paymentMessageKey(payment, "banned", "banned-item", "banned-xp"), vars));
            return;
        }
        int len = input.codePointCount(0, input.length());
        if (len < minChars()) {
            refundPayment(p, payment);
            p.sendMessage(msg(paymentMessageKey(payment, "too-short", "too-short-item", "too-short-xp"), vars));
            return;
        }
        if (len > maxChars()) {
            refundPayment(p, payment);
            p.sendMessage(msg(paymentMessageKey(payment, "too-long", "too-long-item", "too-long-xp"), vars));
            return;
        }
        String title = input.trim();
        if (title.isEmpty()) {
            refundPayment(p, payment);
            p.sendMessage(msg(paymentMessageKey(payment, "too-short", "too-short-item", "too-short-xp"), vars));
            return;
        }
        String buyColor = getDefaultBuyTitleColor();
        int buyWeight = getConfig().getInt("prefix-weight",500);
        if (!applyTitleWithParam(uuid, title, buyColor, buyWeight)) {
            refundPayment(p, payment);
            p.sendMessage(msg(paymentMessageKey(payment, "apply-failed", "apply-failed-item", "apply-failed-xp"), vars));
            return;
        }
        int newIdx = addOwnedTitle(uuid, p.getName(), title, buyColor, buyWeight);
        setActiveIndex(uuid, newIdx);
        saveData();
        p.sendMessage(msg("success"));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
    }

    /**
     * @return 返回新增/覆盖之后在titles列表的索引
     * 规则：title忽略大小写 && color字符串完全相等 → 覆盖，否则新增
     */
    private int addOwnedTitle(UUID uuid, String playerName, String titleText, String color, int weight){
        String base = uuid.toString();
        data.set(base + ".name", playerName);
        List<Map<String,Object>> rawList = (List<Map<String,Object>>)data.getList(base + ".titles");
        if(rawList == null) rawList = new ArrayList<>();
        int findIndex = -1;
        for(int i=0;i<rawList.size();i++){
            Map<String,Object> entry = rawList.get(i);
            String t = entry.get("title")!=null ? String.valueOf(entry.get("title")) : "";
            String c = entry.get("color")!=null ? String.valueOf(entry.get("color")) : "";
            if(titleText.equalsIgnoreCase(t) && color.equals(c)){
                findIndex = i;
                break;
            }
        }
        if(findIndex != -1){
            Map<String,Object> old = rawList.get(findIndex);
            old.put("weight", weight);
            old.put("time", System.currentTimeMillis());
            rawList.set(findIndex, old);
            data.set(base+".titles", rawList);
            return findIndex;
        }else{
            Map<String,Object> entry = new LinkedHashMap<>();
            entry.put("title", titleText);
            entry.put("color", color);
            entry.put("weight", weight);
            entry.put("time", System.currentTimeMillis());
            rawList.add(entry);
            data.set(base+".titles", rawList);
            return rawList.size()-1;
        }
    }

    private boolean applyTitleWithParam(UUID uuid, String titleText, String colorCode, int weight){
        try {
            User user = luckPerms.getUserManager().loadUser(uuid).join();
            user.data().toCollection().stream()
                    .filter(PrefixNode.class::isInstance)
                    .map(PrefixNode.class::cast)
                    .filter(node -> node.getPriority() > 1)
                    .toList()
                    .forEach(node -> user.data().remove(node));
            String sepColor = getConfig().getString("separator-color","&7");
            String rawPrefix = colorCode + getTitlePrefixWrap() + titleText + getTitleSuffixWrap();
            String colored = serializeLegacy(miniMessage(rawPrefix)) + toLegacyCodes(sepColor);
            user.data().add(PrefixNode.builder(colored, weight).build());
            luckPerms.getUserManager().saveUser(user);
            return true;
        }catch (Exception ex){
            getLogger().warning("applyTitleWithParam失败："+ex.getMessage());
            return false;
        }
    }


    private void cancel(Player p, boolean message) {
        UUID uuid = p.getUniqueId();
        if (!waiting.contains(uuid)) {
            if (message) p.sendMessage(msg("no-waiting"));
            return;
        }
        PendingPayment payment = pendingPayments.get(uuid);
        double paid = payment == null ? getConfig().getDouble("price", 60000.0) : payment.amount;
        finishWaiting(uuid);
        refundPayment(p, payment);
        Map<String, String> vars = new HashMap<>();
        vars.put("price", formatMoney(paid));
        vars.put("xp", String.valueOf(xpLevelCost(paid)));
        vars.put("item", payment != null && payment.item != null ? payment.item.name().toLowerCase(Locale.ROOT) : "");
        if (message) {
            p.sendMessage(msg(paymentMessageKey(payment, "cancelled", "cancelled-item", "cancelled-xp"), vars));
        }
    }

    private String paymentMessageKey(PendingPayment payment, String economyKey, String itemKey, String xpKey) {
        if (payment == null || payment.type == PaymentType.ECONOMY) return economyKey;
        if (payment.type == PaymentType.XP) return xpKey;
        return itemKey;
    }

    private void finishWaiting(UUID uuid) {
        waiting.remove(uuid);
        pendingPayments.remove(uuid);
        cancelTask(uuid);
    }

    private void cancelTask(UUID uuid) {
        BukkitTask task = waitingTasks.remove(uuid);
        if (task != null) task.cancel();
    }

    private List<String> knownPlayerNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String key : data.getKeys(false)) {
            String name = data.getString(key + ".name");
            if (name != null && !name.isBlank()) names.add(name);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return new ArrayList<>(names);
    }

    private UUID findKnownPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        for (String key : data.getKeys(false)) {
            if (name.equalsIgnoreCase(data.getString(key + ".name"))) {
                try {
                    return UUID.fromString(key);
                } catch (IllegalArgumentException ignored) {}
            }
        }
        return null;
    }

    private void openGui(Player viewer, UUID owner, boolean readOnly) {
        openGui(viewer, owner, readOnly, 0);
    }

    private void openGui(Player viewer, UUID owner, boolean readOnly, int requestedPage) {
        String ownerName = data.getString(owner + ".name");
        if (ownerName == null) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(owner);
            ownerName = op.getName() == null ? owner.toString() : op.getName();
        }
        int size = normalizeSize(getConfig().getInt("gui.size", 54));
        String guiTitle = readOnly ? getConfig().getString("gui.other-title", "&6%player%的称号仓库").replace("%player%", ownerName) : getConfig().getString("gui.title", "&6称号仓库");
        List<Map<?, ?>> titles = (List<Map<?,?>>)data.getList(owner + ".titles");
        if(titles == null) titles = new ArrayList<>();
        int pageSize = guiContentSlots(size).size();
        int maxPage = Math.max(0, (titles.size() - 1) / Math.max(1, pageSize));
        int page = Math.max(0, Math.min(requestedPage, maxPage));

        GuiHolder holder = new GuiHolder(viewer.getUniqueId(), owner, readOnly, page);
        Inventory inv = Bukkit.createInventory(holder, size, guiComponent(guiTitle));
        holder.inventory = inv;

        // 黑色玻璃板只填充仓库最外围一圈，不覆盖 Painting、Arrow 等功能物品
        Material fillerMaterial = material("gui.filler-material", Material.BLACK_STAINED_GLASS_PANE);
        ItemStack filler = new ItemStack(fillerMaterial);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.displayName(Component.empty());
        filler.setItemMeta(fillerMeta);
        Set<Integer> reservedSlots = new HashSet<>();
        reservedSlots.add(getConfig().getInt("gui.header-slot", 4));
        reservedSlots.add(getConfig().getInt("gui.empty-slot", 22));
        reservedSlots.add(getConfig().getInt("gui.previous-slot", 48));
        reservedSlots.add(getConfig().getInt("gui.next-slot", 50));
        for (int slot : guiBorderSlots(size)) {
            if (!reservedSlots.contains(slot)) inv.setItem(slot, filler.clone());
        }

        // 顶部中央标题图标
        ItemStack header = new ItemStack(material("gui.header-material", Material.PAINTING));
        ItemMeta headerMeta = header.getItemMeta();
        headerMeta.displayName(guiComponent(getConfig().getString("gui.header-name", "&6称号仓库")));
        headerMeta.lore(getConfig().getStringList("gui.header-lore").stream().map(this::guiComponent).toList());
        header.setItemMeta(headerMeta);
        inv.setItem(getConfig().getInt("gui.header-slot", 4), header);

        // 无称号时的占位箱子固定在第三排第五格（0-based slot 22）
        if (titles.isEmpty()) {
            ItemStack item = new ItemStack(material("gui.empty-material", Material.CHEST));
            ItemMeta meta = item.getItemMeta();
            meta.displayName(guiComponent(getConfig().getString("gui.empty-name", "&7暂无已购买的称号")));
            meta.lore(getConfig().getStringList("gui.empty-lore").stream().map(this::guiComponent).toList());
            item.setItemMeta(meta);
            inv.setItem(getConfig().getInt("gui.empty-slot", 22), item);
        } else {
            int activeIndex = getActiveIndex(owner);
            List<Integer> contentSlots = guiContentSlots(size);
            int from = page * pageSize;
            int to = Math.min(titles.size(), from + pageSize);
            for (int i = from; i < to; i++) {
                Map<?,?> entry = titles.get(i);
                String t = entry.get("title")!=null ? String.valueOf(entry.get("title")) : "";
                long time = toLong(entry.get("time"));
                String titleColor = entry.containsKey("color") && entry.get("color")!=null ? String.valueOf(entry.get("color")) : getDefaultBuyTitleColor();
                int titleWeight = entry.containsKey("weight") && entry.get("weight")!=null ? ((Number)entry.get("weight")).intValue() : getConfig().getInt("prefix-weight",500);
                boolean selected = (i == activeIndex);
                Material mat = selected ? material("gui.active-material", Material.LIME_DYE) : material("gui.title-material", Material.NAME_TAG);
                ItemStack item = new ItemStack(mat);
                ItemMeta meta = item.getItemMeta();
                meta.getPersistentDataContainer().set(titleTextKey, PersistentDataType.STRING, t);
                meta.getPersistentDataContainer().set(titleColorKey, PersistentDataType.STRING, titleColor);
                meta.getPersistentDataContainer().set(titleWeightKey, PersistentDataType.INTEGER, titleWeight);
                String guiItemNameTpl = getConfig().getString("gui.item-name", "%color%%wrap_prefix%%title%%wrap_suffix%");
                guiItemNameTpl = guiItemNameTpl.replace("%color%", titleColor);
                guiItemNameTpl = guiItemNameTpl.replace("%wrap_prefix%", getTitlePrefixWrap());
                guiItemNameTpl = guiItemNameTpl.replace("%wrap_suffix%", getTitleSuffixWrap());
                guiItemNameTpl = guiItemNameTpl.replace("%title%", t);
                meta.displayName(guiComponent(guiItemNameTpl));
                List<String> lore = readOnly ? getConfig().getStringList("gui.readonly-lore") : selected ? getConfig().getStringList("gui.active-lore") : getConfig().getStringList("gui.item-lore");
                Map<String,String> loreVars = Map.of("%time%", formatTime(time), "%title%", t);
                meta.lore(replaceAll(lore, loreVars).stream().map(this::guiComponent).toList());
                item.setItemMeta(meta);
                inv.setItem(contentSlots.get(i - from), item);
            }
        }

        // 底部翻页按钮
        if (maxPage > 0) {
            if (page > 0) {
                ItemStack prev = new ItemStack(material("gui.previous-material", Material.ARROW));
                ItemMeta meta = prev.getItemMeta();
                meta.displayName(guiComponent(getConfig().getString("gui.previous-name", "&e上一页")));
                meta.lore(getConfig().getStringList("gui.previous-lore").stream().map(this::guiComponent).toList());
                prev.setItemMeta(meta);
                inv.setItem(getConfig().getInt("gui.previous-slot", 48), prev);
            }
            if (page < maxPage) {
                ItemStack next = new ItemStack(material("gui.next-material", Material.ARROW));
                ItemMeta meta = next.getItemMeta();
                meta.displayName(guiComponent(getConfig().getString("gui.next-name", "&e下一页")));
                meta.lore(getConfig().getStringList("gui.next-lore").stream().map(this::guiComponent).toList());
                next.setItemMeta(meta);
                inv.setItem(getConfig().getInt("gui.next-slot", 50), next);
            }
        }

        openGuis.put(viewer.getUniqueId(), holder);
        viewer.openInventory(inv);
    }

    private List<Integer> guiContentSlots(int size) {
        List<Integer> slots = new ArrayList<>();
        int rows = size / 9;
        for(int row = 1; row < rows - 1; row++) {
            for(int col = 1; col < 8; col++) {
                slots.add(row * 9 + col);
            }
        }
        return slots;
    }

    private List<Integer> guiBorderSlots(int size) {
        List<Integer> slots = new ArrayList<>();
        int rows = size / 9;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < 9; col++) {
                if (row == 0 || row == rows - 1 || col == 0 || col == 8) {
                    slots.add(row * 9 + col);
                }
            }
        }
        return slots;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof GuiHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p) || !p.getUniqueId().equals(holder.viewer)) return;
        int previousSlot = getConfig().getInt("gui.previous-slot", 48);
        int nextSlot = getConfig().getInt("gui.next-slot", 50);
        if (event.getRawSlot() == previousSlot && holder.page > 0) {
            openGui(p, holder.owner, holder.readOnly, holder.page - 1);
            return;
        }
        if (event.getRawSlot() == nextSlot) {
            List<Map<?, ?>> titles = (List<Map<?,?>>)data.getList(holder.owner + ".titles");
            int count = titles == null ? 0 : titles.size();
            int pageSize = guiContentSlots(holder.inventory.getSize()).size();
            int maxPage = Math.max(0, (count - 1) / Math.max(1, pageSize));
            if (holder.page < maxPage) openGui(p, holder.owner, holder.readOnly, holder.page + 1);
            return;
        }
        if (holder.readOnly) return;
        ItemStack item = event.getCurrentItem();
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        String titleText = meta.getPersistentDataContainer().get(titleTextKey, PersistentDataType.STRING);
        String titleColor = meta.getPersistentDataContainer().get(titleColorKey, PersistentDataType.STRING);
        Integer titleWeight = meta.getPersistentDataContainer().get(titleWeightKey, PersistentDataType.INTEGER);
        if(titleText == null || titleColor == null || titleWeight == null) return;

        List<Map<String,Object>> titles = (List<Map<String,Object>>) data.getList(holder.owner.toString()+".titles");
        if(titles == null) return;
        int clickIndex = -1;
        for(int i=0;i<titles.size();i++){
            Map<String,Object> e = titles.get(i);
            String t = e.get("title") == null ? "" : String.valueOf(e.get("title"));
            String c = e.get("color") == null ? "" : String.valueOf(e.get("color"));
            if(titleText.equals(t) && titleColor.equals(c)){
                clickIndex = i;
                break;
            }
        }
        if(clickIndex == -1){
            p.sendMessage(msg("title-record-not-found"));
            return;
        }

        if (!applyTitleWithParam(holder.owner, titleText, titleColor, titleWeight)) {
            p.sendMessage(msg("title-switch-failed"));
            return;
        }
        setActiveIndex(holder.owner, clickIndex);
        data.set(holder.owner + ".name", p.getName());
        saveData();
        p.sendMessage(msg("title-set", Map.of("title", coloredTitleRaw(titleText,titleColor))));
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
        openGui(p, holder.owner, false);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player p) openGuis.remove(p.getUniqueId());
    }

    private int normalizeSize(int value) {
        return Math.max(9, Math.min(54, ((value + 8) / 9) * 9));
    }

    private Material material(String path, Material fallback) {
        Material m = Material.matchMaterial(getConfig().getString(path, fallback.name()));
        return m == null ? fallback : m;
    }

    private long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return 0L;
        }
    }

    private String formatTime(long time) {
        return time <= 0 ? "未知" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(time));
    }

    private String coloredTitleRaw(String title, String colorCode){
        return color(colorCode + getTitlePrefixWrap() + title + getTitleSuffixWrap());
    }

    private String coloredTitle(String title) {
        return coloredTitleRaw(title, getDefaultBuyTitleColor());
    }

    private int minChars() {
        return Math.max(1, getConfig().getInt("min-char", 2));
    }
    private int maxChars() {
        return Math.max(minChars(), getConfig().getInt("max-char", 9));
    }
    private int waitSeconds() {
        return Math.max(1, getConfig().getInt("wait-seconds", 60));
    }

    private String msg(String key) {
        return msg(key, Map.of());
    }

    private String msg(String key, Map<String,String> vars) {
        if (lang == null) return "";
        String prefix = lang.getString("prefix", "");
        String s = lang.getString("messages." + key, "");
        Map<String,String> allVars = new HashMap<>();
        if (vars != null) allVars.putAll(vars);
        allVars.put("prefix", prefix);
        allVars.put("currency", lang.getString("currency-name", language.equals("en_US") ? "coin" : "金币"));
        s = replaceAll(List.of(s), wrapVars(allVars)).getFirst();
        return serializeLegacy(miniMessage(s));
    }

    private Map<String,String> wrapVars(Map<String,String> vars) {
        Map<String,String> m = new HashMap<>();
        vars.forEach((k,v)->m.put("%"+k+"%",v));
        return m;
    }

    private List<String> replaceAll(List<String> list, Map<String,String> vars) {
        List<String> out = new ArrayList<>();
        for (String s : list) {
            for (var e:vars.entrySet()) s=s.replace(e.getKey(), e.getValue());
            out.add(s);
        }
        return out;
    }

    private List<String> colorList(List<String> list) {
        return list.stream().map(this::color).toList();
    }

    private String color(String s) {
        return serializeLegacy(miniMessage(s == null ? "" : s));
    }

    private Component miniMessage(String s) {
        return MiniMessage.miniMessage().deserialize(legacyToMiniMessage(s == null ? "" : s));
    }

    private Component guiComponent(String s) {
        return miniMessage(s).decoration(TextDecoration.ITALIC, false);
    }

    private String legacyToMiniMessage(String input) {
        String s = input == null ? "" : input;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            // 支持 &x&F&F&0&0&0&0 和 §x§F§F§0§0§0§0 两种十六进制颜色
            if ((c == '&' || c == '§') && i + 13 < s.length()
                    && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')) {
                char marker = c;
                StringBuilder hex = new StringBuilder(6);
                boolean valid = true;
                for (int j = 0; j < 6; j++) {
                    int pos = i + 2 + j * 2;
                    if (pos + 1 >= s.length()
                            || s.charAt(pos) != marker
                            || Character.digit(s.charAt(pos + 1), 16) < 0) {
                        valid = false;
                        break;
                    }
                    hex.append(s.charAt(pos + 1));
                }
                if (valid) {
                    out.append("<#").append(hex).append('>');
                    i += 13;
                    continue;
                }
            }

            // 支持普通的 &a / §a 等 Legacy 颜色和格式代码
            if ((c == '&' || c == '§') && i + 1 < s.length()) {
                String tag = switch (Character.toLowerCase(s.charAt(i + 1))) {
                    case '0' -> "<black>";
                    case '1' -> "<dark_blue>";
                    case '2' -> "<dark_green>";
                    case '3' -> "<dark_aqua>";
                    case '4' -> "<dark_red>";
                    case '5' -> "<dark_purple>";
                    case '6' -> "<gold>";
                    case '7' -> "<gray>";
                    case '8' -> "<dark_gray>";
                    case '9' -> "<blue>";
                    case 'a' -> "<green>";
                    case 'b' -> "<aqua>";
                    case 'c' -> "<red>";
                    case 'd' -> "<light_purple>";
                    case 'e' -> "<yellow>";
                    case 'f' -> "<white>";
                    case 'k' -> "<obfuscated>";
                    case 'l' -> "<bold>";
                    case 'm' -> "<strikethrough>";
                    case 'n' -> "<underlined>";
                    case 'o' -> "<italic>";
                    case 'r' -> "<reset>";
                    default -> null;
                };
                if (tag != null) {
                    out.append(tag);
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private String serializeLegacy(Component component) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(component);
    }

    /**
     * 将配置中的 & Legacy 颜色代码转换成 § Legacy 代码
     * 用于 LuckPerms PrefixNode 的末尾颜色状态，让后续的玩家名称继承 separator-color
     */
    private String toLegacyCodes(String input) {
        if (input == null || input.isEmpty()) return "";
        String s = input;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '&' || c == '§') && i + 13 < s.length()
                    && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')) {
                char marker = c;
                boolean valid = true;
                StringBuilder hex = new StringBuilder(6);
                for (int j = 0; j < 6; j++) {
                    int pos = i + 2 + j * 2;
                    if (pos + 1 >= s.length() || s.charAt(pos) != marker
                            || Character.digit(s.charAt(pos + 1), 16) < 0) {
                        valid = false;
                        break;
                    }
                    hex.append(s.charAt(pos + 1));
                }
                if (valid) {
                    out.append('§').append('x');
                    for (int j = 0; j < hex.length(); j++) {
                        out.append('§').append(hex.charAt(j));
                    }
                    i += 13;
                    continue;
                }
            }
            if (c == '&' && i + 1 < s.length()) {
                char code = s.charAt(i + 1);
                if ("0123456789abcdefklmnor".indexOf(Character.toLowerCase(code)) >= 0) {
                    out.append('§').append(code);
                    i++;
                    continue;
                }
            }
            if (c == '§') {
                out.append(c);
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private String formatMoney(double amount) {
        return amount == Math.rint(amount) ? String.valueOf((long) amount) : String.format(Locale.US, "%.2f", amount);
    }
}
