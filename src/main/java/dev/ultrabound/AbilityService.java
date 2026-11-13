package dev.ultrabound;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;

/** Implemented actives only. Unimplemented registry entries are never activated or charged. */
public final class AbilityService {
    private final UltraboundPlugin plugin;
    private final Map<UUID,BukkitTask> invisTasks=new HashMap<>();
    AbilityService(UltraboundPlugin p){plugin=p;}
    public boolean isInvisible(UUID id){return invisTasks.containsKey(id);}
    public void prepareViewer(Player viewer){
        for(UUID hidden:invisTasks.keySet()){
            Player p=Bukkit.getPlayer(hidden);
            if(p!=null && !viewer.equals(p) && (!viewer.hasPermission("ultrabound.admin") || plugin.getConfig().getBoolean("true-invisibility.hide-from-staff")))
                viewer.hidePlayer(plugin,p);
        }
    }
    public void restoreAllInvisible(){for(UUID id:new HashSet<>(invisTasks.keySet()))reveal(id);}
    public void reveal(UUID id){
        BukkitTask task=invisTasks.remove(id);if(task!=null)task.cancel();
        Player target=Bukkit.getPlayer(id);
        if(target!=null)for(Player viewer:Bukkit.getOnlinePlayers())viewer.showPlayer(plugin,target);
    }
    private void vanish(Player p,int seconds){
        reveal(p.getUniqueId());
        for(Player viewer:Bukkit.getOnlinePlayers()){
            if(viewer.equals(p))continue;
            if(viewer.hasPermission("ultrabound.admin") && !plugin.getConfig().getBoolean("true-invisibility.hide-from-staff"))continue;
            viewer.hidePlayer(plugin,p);
        }
        BukkitTask task=Bukkit.getScheduler().runTaskLater(plugin,()->reveal(p.getUniqueId()),seconds*20L);
        invisTasks.put(p.getUniqueId(),task);
    }
    public String equip(Player p,String id,int slot){
        PlayerStore.Data d=plugin.players().get(p.getUniqueId());
        if(d.skillset==null)return "Choose your class first.";
        int slots=Math.max(1,Math.min(4,plugin.getConfig().getInt("ability.slots",2)));
        if(slot<1 || slot>slots)return "Slot must be 1-"+slots+".";
        SkillRegistry.Active a=plugin.skills().active(d.skillset,id);
        if(a==null)return "Unknown ability for your class.";
        if(!a.implemented())return "That ability is only a design preview so far.";
        if(!a.unlockedByDefault())return "This ability has not been unlocked.";
        d.equipped.remove(id);
        while(d.equipped.size()<slot)d.equipped.add("");
        d.equipped.set(slot-1,id);
        plugin.players().save(d);return "Equipped "+a.name()+" in slot "+slot+".";
    }
    public String activate(Player p,String id){
        PlayerStore.Data d=plugin.players().get(p.getUniqueId());
        if(d.skillset==null)return "Choose your skillset first.";
        SkillRegistry.Active a=plugin.skills().active(d.skillset,id);
        if(a==null)return "Unknown active for your class.";
        if(!a.implemented())return "This ability is not implemented yet.";
        if(!a.unlockedByDefault())return "Ability not yet unlocked.";
        if(plugin.getConfig().getBoolean("ability.require-equipped",true) && !d.equipped.contains(id))return "Equip it first: /ultrabound equip "+id+" 1";
        if(p.getGameMode()==GameMode.SPECTATOR && !plugin.getConfig().getBoolean("ability.allow-while-spectator"))return "Abilities are disabled in spectator mode.";
        if(plugin.getConfig().getStringList("ability.disabled-worlds").contains(p.getWorld().getName()))return "Disabled in this world.";
        long now=System.currentTimeMillis(),left=d.cooldowns.getOrDefault(id,0L)-now;
        if(left>0)return "Cooldown: "+((left+999)/1000)+" seconds remaining.";
        int seconds=Math.min(120,Math.max(0,a.duration()));
        boolean success=switch(a.type()){
            case "HASTE"->{add(p,PotionEffectType.HASTE,seconds==0?8:seconds,2);yield true;}
            case "SHIELD"->{
                add(p,PotionEffectType.RESISTANCE,seconds==0?6:seconds,0);
                add(p,PotionEffectType.ABSORPTION,seconds==0?6:seconds,0);yield true;
            }
            case "HEAL"->{heal(p,a.raw().getDouble("amount",4));yield true;}
            case "POTION"->{
                PotionEffectType type=effectByName(a.raw().getString("potion-effect","SPEED"));
                if(type==null)yield false;
                add(p,type,seconds==0?6:seconds,a.raw().getInt("amplifier",0));yield true;
            }
            case "AREA_HEAL"->{
                double radius=Math.max(1,Math.min(24,a.raw().getDouble("radius",7)));
                heal(p,2);
                for(Player ally:p.getWorld().getNearbyPlayers(p.getLocation(),radius)){
                    if(plugin.players().isTrusted(p.getUniqueId(),ally.getUniqueId())){
                        heal(ally,2);add(ally,PotionEffectType.REGENERATION,Math.max(2,seconds),0);
                    }
                }
                yield true;
            }
            case "SCAN"->{
                for(Entity entity:p.getNearbyEntities(Math.max(4,a.raw().getInt("range",20)),12,Math.max(4,a.raw().getInt("range",20))))
                    if(entity instanceof LivingEntity mob && !(entity instanceof Player))
                        mob.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,Math.max(2,seconds)*20,0,false,false,true));
                yield true;
            }
            case "UPDRAFT"->{
                var v=p.getVelocity();p.setVelocity(v.setY(Math.max(.35,Math.min(1.1,a.raw().getDouble("vertical-power",.75)))));
                yield true;
            }
            case "TRUE_INVIS"->{vanish(p,Math.max(1,plugin.getConfig().getInt("true-invisibility.duration-seconds",8)));yield true;}
            case "ASCEND"->ascend(p);
            default->false; // Unknown or unimplemented effect is never charged.
        };
        if(!success)return "Cannot activate: invalid item, configuration or unfinished mechanic.";
        d.cooldowns.put(id,now+Math.max(0,a.cooldown())*1000L);
        plugin.players().save(d);
        p.sendActionBar(net.kyori.adventure.text.Component.text(a.name()+" activated • cooldown "+a.cooldown()+"s"));
        if(plugin.getConfig().getBoolean("vfx.enabled",true) && !a.type().equals("TRUE_INVIS")){
            p.getWorld().spawnParticle(Particle.ENCHANT,p.getLocation().add(0,1,0),Math.max(0,Math.min(100,plugin.getConfig().getInt("vfx.particle-count",8))),.4,.5,.4,.05);
            p.playSound(p.getLocation(),Sound.BLOCK_AMETHYST_BLOCK_CHIME,.65f,1.2f);
        }
        return "Activated "+a.name()+".";
    }
    public void add(Player p,PotionEffectType type,int seconds,int amp){
        if(type!=null) p.addPotionEffect(new PotionEffect(type,seconds*20,Math.max(0,amp),false,true,true));
    }
    private PotionEffectType effectByName(String raw){
        try{return Registry.MOB_EFFECT.get(NamespacedKey.minecraft(raw.toLowerCase(Locale.ROOT)));}
        catch(Exception e){return null;}
    }
    private void heal(Player p,double amount){
        var attribute=p.getAttribute(Attribute.MAX_HEALTH);
        double maximum=attribute==null?20:attribute.getValue();
        p.setHealth(Math.min(maximum,p.getHealth()+Math.max(0,amount)));
    }
    /** Atomic item swap: every eligible enchant on ONE item increases exactly one level. */
    private boolean ascend(Player p){
        ItemStack hand=p.getInventory().getItemInMainHand();
        if(hand.getType().isAir() || !hand.hasItemMeta())return false;
        int cost=Math.max(0,plugin.getConfig().getInt("enchanter.ascension-xp-level-cost",5));
        if(p.getLevel()<cost)return false;
        ItemStack upgraded=hand.clone();ItemMeta meta=upgraded.getItemMeta();
        Map<Enchantment,Integer> current=new HashMap<>(meta.getEnchants());
        if(current.isEmpty())return false;
        int maxConfig=Math.max(1,plugin.getConfig().getInt("enchanter.max-enchant-level",6));
        boolean allowOver=plugin.getConfig().getBoolean("enchanter.allow-above-vanilla-max",false);
        List<String> excluded=plugin.getConfig().getStringList("enchanter.excluded-enchantments");
        for(var entry:current.entrySet()){
            Enchantment enchant=entry.getKey();int next=entry.getValue()+1;
            if(excluded.contains(enchant.getKey().getKey()))return false;
            if(next>maxConfig || (!allowOver && next>enchant.getMaxLevel()))return false;
            if(!enchant.canEnchantItem(hand))return false;
            for(Enchantment other:current.keySet())if(!other.equals(enchant) && enchant.conflictsWith(other))return false;
        }
        for(var entry:current.entrySet()){
            if(!meta.addEnchant(entry.getKey(),entry.getValue()+1,true))return false;
        }
        upgraded.setItemMeta(meta);
        p.getInventory().setItemInMainHand(upgraded);
        p.setLevel(p.getLevel()-cost);
        return true;
    }
}
