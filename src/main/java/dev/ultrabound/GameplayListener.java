package dev.ultrabound;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import java.io.File;
import java.util.*;

/** Conservative server-side hooks. Respect canceled vanilla events wherever possible. */
public final class GameplayListener implements Listener {
    private final UltraboundPlugin p;
    private final File placedFile;
    private final Set<String> placedOres=new HashSet<>();
    private final Map<UUID,Long> tntCooldown=new HashMap<>();
    private final Map<UUID,Integer> tntActive=new HashMap<>();
    private final NamespacedKey tntSource;
    GameplayListener(UltraboundPlugin p){
        this.p=p;
        tntSource=new NamespacedKey(p,"infinite_tnt_owner");
        placedFile=new File(p.getDataFolder(),"player-placed-ores.yml");
        YamlConfiguration cfg=YamlConfiguration.loadConfiguration(placedFile);
        placedOres.addAll(cfg.getStringList("placed-ore-positions"));
    }
    private String key(Block b){return b.getWorld().getUID()+":"+b.getX()+":"+b.getY()+":"+b.getZ();}
    private boolean ore(Material m){
        String name=m.name();return name.endsWith("_ORE") || m==Material.ANCIENT_DEBRIS;
    }
    public void saveOreMarkers(){
        YamlConfiguration cfg=new YamlConfiguration();cfg.set("placed-ore-positions",new ArrayList<>(placedOres));
        try{cfg.save(placedFile);}catch(Exception e){p.getLogger().warning("Could not save ore-placement markers: "+e.getMessage());}
    }
    @EventHandler public void join(PlayerJoinEvent event){
        Player player=event.getPlayer();PlayerStore.Data d=p.players().get(player.getUniqueId());
        Bukkit.getScheduler().runTaskLater(p,()->{
            if(!player.isOnline())return;
            if(d.skillset==null)p.menus().choose(player,false);
            else if("GUARDIAN".equals(d.skillset) && p.getConfig().getBoolean("guardian.auto-replace-bound-shield",true))p.items().restoreShieldIfEligible(player);
            p.abilities().prepareViewer(player);
        },Math.max(1,p.getConfig().getLong("first-join.gui-delay-ticks",40)));
    }
    @EventHandler public void quit(PlayerQuitEvent event){
        p.abilities().reveal(event.getPlayer().getUniqueId());
        p.players().unload(event.getPlayer().getUniqueId());
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void placeMark(BlockPlaceEvent e){
        if(ore(e.getBlockPlaced().getType())){placedOres.add(key(e.getBlockPlaced()));saveOreMarkers();}
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)public void oreXp(BlockBreakEvent e){
        if(!ore(e.getBlock().getType()))return;
        String coord=key(e.getBlock());
        if(placedOres.remove(coord)){saveOreMarkers();return;}
        PlayerStore.Data d=p.players().get(e.getPlayer().getUniqueId());
        if(!"MINER".equals(d.skillset) || !p.getConfig().getBoolean("progression.enabled",true))return;
        d.oreProgress++;
        int threshold=Math.max(1,p.getConfig().getInt("progression.natural-ore-breaks-per-point",24));
        if(d.oreProgress>=threshold){
            d.oreProgress-=threshold;d.availablePoints++;d.earnedPoints++;
            e.getPlayer().sendMessage(ChatColor.AQUA+"+1 Ultrabound skill point from mining!");
        }
        p.players().save(d);
    }
    // NOTE: A plugin cannot prove a block is naturally-generated just by checking placement history.
    // For strict anti-exploit usage, disable ore XP or use an external block provenance tracker.

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void blockPlaceTnt(BlockPlaceEvent e){
        if(!p.getConfig().getBoolean("infinite-tnt.enabled",true))return;
        Player player=e.getPlayer();
        ItemStack held=e.getItemInHand();
        if(!p.items().is(held,CustomItems.INFIN_TNT))return;
        if(!"BOMBARDEER".equals(p.players().get(player.getUniqueId()).skillset)){
            e.setCancelled(true);player.sendMessage(ChatColor.RED+"Only Bombardeers can use Infinite TNT.");return;
        }
        UUID owner=p.items().owner(held);
        if(owner==null || !owner.equals(player.getUniqueId())){e.setCancelled(true);return;}
        long now=System.currentTimeMillis();
        long cooldown=Math.max(1,p.getConfig().getInt("infinite-tnt.cooldown-ticks",40))*50L;
        if(tntCooldown.getOrDefault(owner,0L)>now || tntActive.getOrDefault(owner,0)>=p.getConfig().getInt("infinite-tnt.max-active-per-player",3)){
            e.setCancelled(true);return;
        }
        // Vanilla BlockPlaceEvent must have succeeded and not been canceled by protection plugins.
        // Priming and restocking happen next tick, after other protection listeners have run.
        ItemStack copy=held.clone();copy.setAmount(1);
        Block block=e.getBlockPlaced();Location where=block.getLocation().add(.5,0,.5);
        tntCooldown.put(owner,now+cooldown);
        Bukkit.getScheduler().runTask(p,()->{
            if(block.getType()!=Material.TNT)return;
            block.setType(Material.AIR,false);
            TNTPrimed primed=where.getWorld().spawn(where,TNTPrimed.class);
            primed.setFuseTicks(Math.max(1,Math.min(200,p.getConfig().getInt("infinite-tnt.fuse-ticks",80))));
            primed.getPersistentDataContainer().set(tntSource,PersistentDataType.STRING,owner.toString());
            tntActive.merge(owner,1,Integer::sum);
            // If vanilla consumed it, restore exactly one original bound item; never add duplicates.
            if(player.isOnline() && !hasOriginalTnt(player,owner)){
                player.getInventory().addItem(copy).values().forEach(x->player.getWorld().dropItemNaturally(player.getLocation(),x));
            }
        });
    }
    private boolean hasOriginalTnt(Player player,UUID owner){
        for(ItemStack stack:player.getInventory().getContents())
            if(p.items().is(stack,CustomItems.INFIN_TNT) && owner.equals(p.items().owner(stack)))return true;
        return false;
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)public void explosion(EntityExplodeEvent e){
        if(!(e.getEntity() instanceof TNTPrimed primed))return;
        String id=primed.getPersistentDataContainer().get(tntSource,PersistentDataType.STRING);
        if(id==null)return;
        try{UUID owner=UUID.fromString(id);tntActive.merge(owner,-1,(a,b)->Math.max(0,a+b));}
        catch(Exception ignored){}
        if(!p.getConfig().getBoolean("infinite-tnt.block-damage",false))e.blockList().clear();
        if(!p.getConfig().getBoolean("infinite-tnt.fire",false))e.setYield(0);
    }
    @EventHandler public void died(PlayerDeathEvent e){
        Player player=e.getEntity();
        UUID owner=player.getUniqueId();
        // Bound shields must not become transferable player loot.
        e.getDrops().removeIf(x->p.items().is(x,CustomItems.GUARDIAN_SHIELD) && owner.equals(p.items().owner(x)));
        p.abilities().reveal(owner);
    }
    @EventHandler public void respawn(PlayerRespawnEvent e){
        Bukkit.getScheduler().runTaskLater(p,()->p.items().restoreShieldIfEligible(e.getPlayer()),2L);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void pickups(EntityPickupItemEvent e){
        if(!(e.getEntity() instanceof Player player))return;
        ItemStack it=e.getItem().getItemStack();
        if(p.items().is(it,CustomItems.GUARDIAN_SHIELD) && !player.getUniqueId().equals(p.items().owner(it)))e.setCancelled(true);
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void guardShieldInventory(InventoryClickEvent e){
        if(!(e.getWhoClicked() instanceof Player player))return;
        ItemStack current=e.getCurrentItem(), cursor=e.getCursor();
        boolean movingShield=p.items().is(current,CustomItems.GUARDIAN_SHIELD);
        boolean carryingShield=p.items().is(cursor,CustomItems.GUARDIAN_SHIELD);
        if(!movingShield && !carryingShield)return;
        if(movingShield && !player.getUniqueId().equals(p.items().owner(current))){e.setCancelled(true);return;}
        if(carryingShield && !player.getUniqueId().equals(p.items().owner(cursor))){e.setCancelled(true);return;}
        Inventory clicked=e.getClickedInventory();
        // Allow owner's shield only inside their personal inventory. No chest/ender chest/shift-stashing.
        if(!(clicked instanceof PlayerInventory) || e.isShiftClick())e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void guardShieldDrag(InventoryDragEvent e){
        if(!p.items().is(e.getOldCursor(),CustomItems.GUARDIAN_SHIELD))return;
        int top=e.getView().getTopInventory().getSize();
        for(int slot:e.getRawSlots())if(slot<top){e.setCancelled(true);return;}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void guardShieldHoppers(InventoryMoveItemEvent e){
        if(p.items().is(e.getItem(),CustomItems.GUARDIAN_SHIELD))e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void guardShieldDrops(PlayerDropItemEvent e){
        if(p.items().is(e.getItemDrop().getItemStack(),CustomItems.GUARDIAN_SHIELD))e.setCancelled(true);
    }

    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)public void guardianShieldOwner(EntityDamageByEntityEvent e){
        if(e.getEntity() instanceof Player target){
            PlayerStore.Data d=p.players().get(target.getUniqueId());
            // Only implemented passives apply numerical effects; other tree nodes remain design-only.
            if("GUARDIAN".equals(d.skillset)) {
                int rank=p.players().rank(d,"fortified");
                if(rank>0)e.setDamage(Math.max(0,e.getDamage()*(1-.02*rank)));
            }else if("MELEE".equals(d.skillset)){
                int rank=p.players().rank(d,"guarded");
                if(rank>0)e.setDamage(Math.max(0,e.getDamage()*(1-.03*rank)));
            }else if("ENGINEER".equals(d.skillset)){
                int rank=p.players().rank(d,"reinforced-suit");
                if(rank>0)e.setDamage(Math.max(0,e.getDamage()*(1-.03*rank)));
            }
            if(e.getDamager() instanceof TNTPrimed explosive){
                String claimedOwner=explosive.getPersistentDataContainer().get(tntSource,PersistentDataType.STRING);
                if(claimedOwner!=null){
                    try{if(p.players().isTrusted(UUID.fromString(claimedOwner),target.getUniqueId()))e.setCancelled(true);}
                    catch(IllegalArgumentException ignored){}
                }
            }
            if(p.getConfig().getBoolean("true-invisibility.stop-on-damage",true) && p.abilities().isInvisible(target.getUniqueId()))p.abilities().reveal(target.getUniqueId());
        }
        if(e.getDamager() instanceof Player attacker && p.getConfig().getBoolean("true-invisibility.stop-on-attack",true)
            && p.abilities().isInvisible(attacker.getUniqueId()))p.abilities().reveal(attacker.getUniqueId());
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)public void interact(PlayerInteractEvent e){
        if(e.getHand()!=org.bukkit.inventory.EquipmentSlot.HAND || !e.getAction().isRightClick())return;
        Player player=e.getPlayer();ItemStack item=player.getInventory().getItemInMainHand();
        if(p.items().is(item,CustomItems.RESET_CLASS)){
            e.setCancelled(true);p.menus().choose(player,true);
        }else if(p.items().is(item,CustomItems.RESET_TREE)){
            e.setCancelled(true);
            PlayerStore.Data data=p.players().get(player.getUniqueId());
            if(data.skillset==null)return;
            // Refund is irreversible; require a second explicit command instead of consuming on right-click.
            player.sendMessage(ChatColor.YELLOW+"Confirm a skill tree refund with /ultrabound refund confirm while holding the token.");
        }
    }
    // A core trust check for Alchemist direct splash potions. Lingering and custom area debuffs still need integration.
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void potionSplash(PotionSplashEvent e){
        if(!(e.getPotion().getShooter() instanceof Player owner))return;
        if(!"ALCHEMIST".equals(p.players().get(owner.getUniqueId()).skillset))return;
        boolean harmful=e.getPotion().getEffects().stream().anyMatch(effect->p.getConfig().getStringList("potions.negative-effects")
            .contains(effect.getType().getKey().getKey().toUpperCase(Locale.ROOT)));
        if(!harmful)return;
        for(LivingEntity entity:e.getAffectedEntities())
            if(entity instanceof Player target && p.players().isTrusted(owner.getUniqueId(),target.getUniqueId()))e.setIntensity(entity,0);
    }
    @EventHandler public void zombieDrops(EntityDeathEvent e){
        if(!(e.getEntity() instanceof Zombie))return;
        double chance=Math.max(0,Math.min(.5,p.getConfig().getDouble("potions.infusium-zombie-drop-chance",0.015)));
        if(Math.random()<chance)e.getDrops().add(p.items().make(CustomItems.INFUSIUM,null));
    }
}
