package dev.ultrabound;

import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import java.util.*;

/** PDC-based item identity; display names are never authentication. */
public final class CustomItems {
    public static final String RESET_CLASS="class_reset_token",RESET_TREE="skill_tree_reset_token",
       INFIN_TNT="infinite_tnt",GUARDIAN_SHIELD="guardian_shield",INFUSIUM="infusium";
    private final UltraboundPlugin plugin;
    private final NamespacedKey idKey,ownerKey,versionKey;
    CustomItems(UltraboundPlugin p){plugin=p;idKey=new NamespacedKey(p,"item_id");ownerKey=new NamespacedKey(p,"owner_uuid");versionKey=new NamespacedKey(p,"item_version");}
    public String identity(ItemStack item){
        if(item==null || !item.hasItemMeta())return null;
        return item.getItemMeta().getPersistentDataContainer().get(idKey,PersistentDataType.STRING);
    }
    public UUID owner(ItemStack item){
        if(item==null || !item.hasItemMeta())return null;
        String id=item.getItemMeta().getPersistentDataContainer().get(ownerKey,PersistentDataType.STRING);
        try{return UUID.fromString(id);}catch(Exception e){return null;}
    }
    public boolean is(ItemStack item,String name){return name.equals(identity(item));}
    public ItemStack make(String id,UUID owner){
        Material mat=switch(id){
            case RESET_CLASS->Material.NETHER_STAR;
            case RESET_TREE->Material.ECHO_SHARD;
            case INFIN_TNT->Material.TNT;
            case GUARDIAN_SHIELD->Material.SHIELD;
            case INFUSIUM->Material.AMETHYST_SHARD;
            default->Material.PAPER;
        };
        ItemStack item=new ItemStack(mat);
        ItemMeta m=item.getItemMeta();
        m.setDisplayName(switch(id){
            case RESET_CLASS->ChatColor.LIGHT_PURPLE+"Skillset Reset Token";
            case RESET_TREE->ChatColor.AQUA+"Skill Tree Refund Token";
            case INFIN_TNT->ChatColor.GOLD+"Infinite TNT";
            case GUARDIAN_SHIELD->ChatColor.BLUE+"Guardian Shield";
            case INFUSIUM->ChatColor.DARK_PURPLE+"Infusium";
            default->"Ultrabound Custom Item";
        });
        m.setLore(switch(id){
            case RESET_CLASS->List.of(ChatColor.GRAY+"Confirm before changing your class.");
            case RESET_TREE->List.of(ChatColor.GRAY+"Refund allocated skill points.");
            case INFIN_TNT->List.of(ChatColor.GRAY+"Auto-primes; fuse 4 seconds by default.",ChatColor.DARK_GRAY+"Special item; not consumed.");
            case GUARDIAN_SHIELD->List.of(ChatColor.GRAY+"Owner-bound Guardian shield.");
            case INFUSIUM->List.of(ChatColor.GRAY+"Combines potion effects for Alchemists.");
            default->List.of();
        });
        m.getPersistentDataContainer().set(idKey,PersistentDataType.STRING,id);
        m.getPersistentDataContainer().set(versionKey,PersistentDataType.INTEGER,1);
        if(owner!=null)m.getPersistentDataContainer().set(ownerKey,PersistentDataType.STRING,owner.toString());
        if(id.equals(GUARDIAN_SHIELD))m.setUnbreakable(true);
        item.setItemMeta(m);
        return item;
    }
    public void grantKit(Player p,String cls){
        PlayerStore.Data data=plugin.players().get(p.getUniqueId());
        if(data.kitClaimed)return;
        List<ItemStack> kit=new ArrayList<>();
        switch(cls){
            case "MINER"->{
                ItemStack pick=new ItemStack(Material.IRON_PICKAXE);
                pick.addUnsafeEnchantment(Enchantment.EFFICIENCY,3);
                kit.add(pick);kit.add(new ItemStack(Material.TORCH,32));
                kit.add(new ItemStack(Material.BREAD,16));kit.add(new ItemStack(Material.CRAFTING_TABLE));
            }
            case "BOMBARDEER"->kit.add(make(INFIN_TNT,p.getUniqueId()));
            case "MELEE"->{
                ItemStack sword=new ItemStack(Material.IRON_SWORD);
                sword.addUnsafeEnchantment(Enchantment.SHARPNESS,2);kit.add(sword);
            }
            case "VILLAGER_MASTER"->kit.add(new ItemStack(Material.VILLAGER_SPAWN_EGG,2));
            case "GUARDIAN"->kit.add(make(GUARDIAN_SHIELD,p.getUniqueId()));
            case "ALCHEMIST"->{
                kit.add(new ItemStack(Material.BREWING_STAND));kit.add(new ItemStack(Material.GLASS_BOTTLE,64));
                kit.add(new ItemStack(Material.BLAZE_ROD,4));kit.add(new ItemStack(Material.NETHER_WART,2));
                kit.add(new ItemStack(Material.GLOWSTONE_DUST,8));kit.add(new ItemStack(Material.REDSTONE,8));
            }
            case "ENCHANTER"->{
                kit.add(new ItemStack(Material.ENCHANTING_TABLE));kit.add(new ItemStack(Material.BOOKSHELF,3));
                kit.add(new ItemStack(Material.LAPIS_LAZULI,16));kit.add(new ItemStack(Material.EXPERIENCE_BOTTLE,16));
            }
            default->{} // Other starter kits in the documents remain proposals.
        }
        // Save claim BEFORE distributing. Reissued only through authorized admin action on failure.
        data.kitClaimed=true;plugin.players().save(data);
        for(ItemStack it:kit) {
            Map<Integer,ItemStack> overflow=p.getInventory().addItem(it);
            for(ItemStack extra:overflow.values())p.getWorld().dropItemNaturally(p.getLocation(),extra);
        }
    }
    public boolean containsOwnedShield(Player p){
        for(ItemStack item:p.getInventory().getContents())
            if(is(item,GUARDIAN_SHIELD) && p.getUniqueId().equals(owner(item)))return true;
        return false;
    }
    public void restoreShieldIfEligible(Player p){
        PlayerStore.Data d=plugin.players().get(p.getUniqueId());
        if(!"GUARDIAN".equals(d.skillset) || !d.kitClaimed || containsOwnedShield(p))return;
        var leftovers=p.getInventory().addItem(make(GUARDIAN_SHIELD,p.getUniqueId()));
        for(ItemStack extra:leftovers.values())p.getWorld().dropItemNaturally(p.getLocation(),extra);
    }
}
