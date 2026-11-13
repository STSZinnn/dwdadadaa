package dev.ultrabound;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Command routes. Admin writes make a backup and validate before accepting config changes. */
public final class UltraboundCommand implements TabExecutor {
    private final UltraboundPlugin p;
    UltraboundCommand(UltraboundPlugin plugin){p=plugin;}
    private boolean admin(CommandSender who){
        if(who.hasPermission("ultrabound.admin"))return true;
        who.sendMessage(ChatColor.RED+"This command is admin-only.");return false;
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(args.length==0){
            sender.sendMessage(ChatColor.AQUA+"Ultrabound: /ub helpbook | choose | skilltree | trust | ability | equip | recipes | status");
            return true;
        }
        String sub=args[0].toLowerCase(Locale.ROOT);
        if(sub.equals("points") && admin(sender)){points(sender,args);return true;}
        if(sub.equals("reload") && admin(sender)){
            sender.sendMessage(p.reloadSettings()?ChatColor.GREEN+"Settings reloaded; progression untouched.":ChatColor.RED+"Invalid skillsets.yml; old settings kept.");return true;
        }
        if(sub.equals("set") && admin(sender)){configSet(sender,args);return true;}
        if(!(sender instanceof Player player)){sender.sendMessage("Player-only command (except points/reload/set).");return true;}
        PlayerStore.Data d=p.players().get(player.getUniqueId());
        switch(sub){
            case "choose"->{if(d.skillset==null)p.menus().choose(player,false);
                else player.sendMessage(ChatColor.RED+"Class already selected. Use a Skillset Reset Token.");}
            case "helpbook","help"->{
                if(args.length>=2){SkillRegistry.Skillset s=p.skills().get(args[1]);
                    if(s==null)player.sendMessage(ChatColor.RED+"Unknown skillset.");
                    else p.menus().guide(player,s.id());}
                else p.menus().help(player);
            }
            case "skilltree","tree"->p.menus().tree(player);
            case "recipes"->p.menus().recipes(player);
            case "items"->{if(admin(sender))p.menus().adminItems(player);}
            case "config"->{if(admin(sender))p.menus().adminConfig(player);}
            case "status"->{
                player.sendMessage(ChatColor.AQUA+"Class: "+(d.skillset==null?"unassigned":d.skillset)+
                    " | Skill points: "+d.availablePoints+" | Allocated: "+d.allocatedPoints);
                player.sendMessage(ChatColor.GRAY+"Equipped: "+d.equipped);
            }
            case "trust"->trust(player,args);
            case "branch"->{
                if(args.length>=3 && args[1].equalsIgnoreCase("unlock"))
                    player.sendMessage(ChatColor.YELLOW+p.players().unlockBranch(d,args[2].toLowerCase(Locale.ROOT)));
                else player.sendMessage("/ub branch unlock <branch-id>");
            }
            case "ability"->{
                if(args.length<2){player.sendMessage(ChatColor.YELLOW+"/ub ability <ability-id>");break;}
                player.sendMessage(ChatColor.AQUA+p.abilities().activate(player,args[1].toLowerCase(Locale.ROOT)));
            }
            case "equip"->{
                if(args.length<3){player.sendMessage(ChatColor.YELLOW+"/ub equip <ability-id> <1|2>");break;}
                try{player.sendMessage(ChatColor.AQUA+p.abilities().equip(player,args[1].toLowerCase(Locale.ROOT),Integer.parseInt(args[2])));}
                catch(NumberFormatException e){player.sendMessage(ChatColor.RED+"Use a numeric slot.");}
            }
            case "refund"->{
                if(args.length>=2 && args[1].equalsIgnoreCase("confirm") && p.items().is(player.getInventory().getItemInMainHand(),CustomItems.RESET_TREE)){
                    if(d.skillset==null)return true;
                    ItemStack token=player.getInventory().getItemInMainHand();token.setAmount(token.getAmount()-1);
                    p.players().refundAll(d);
                    player.sendMessage(ChatColor.GREEN+"All spent skill points have been refunded.");
                }else player.sendMessage(ChatColor.YELLOW+"Hold a Refund Token, then /ub refund confirm");
            }
            case "infuse"->infuse(player);
            default->sender.sendMessage(ChatColor.YELLOW+"Commands: helpbook, choose, skilltree, ability, equip, trust, recipes, infuse, items, config, points, set, reload");
        }
        return true;
    }
    private void points(CommandSender sender,String[] args){
        if(args.length<4){sender.sendMessage("/ub points <give|take|set> <online-player> <amount>");return;}
        Player target=Bukkit.getPlayerExact(args[2]);if(target==null){sender.sendMessage("Player must be online.");return;}
        try{
            int amount=Integer.parseInt(args[3]);if(amount<0 || amount>100000)throw new IllegalArgumentException();
            PlayerStore.Data d=p.players().get(target.getUniqueId());
            int before=d.availablePoints;
            switch(args[1].toLowerCase(Locale.ROOT)){
                case "give"->d.availablePoints=Math.min(100000,Math.addExact(d.availablePoints,amount));
                case "take"->d.availablePoints=Math.max(0,d.availablePoints-amount);
                case "set"->d.availablePoints=amount;
                default->{sender.sendMessage("Unknown points action.");return;}
            }
            d.earnedPoints=Math.max(d.earnedPoints,d.availablePoints+d.allocatedPoints);
            p.players().save(d);sender.sendMessage(ChatColor.GREEN+"Set unspent points: "+before+" -> "+d.availablePoints);
        }catch(Exception e){sender.sendMessage(ChatColor.RED+"Invalid amount. Use a number from 0 to 100000.");}
    }
    private void trust(Player player,String[] args){
        PlayerStore.Data data=p.players().get(player.getUniqueId());
        if(args.length==1 || args[1].equalsIgnoreCase("list")){
            if(data.trusted.isEmpty()){player.sendMessage(ChatColor.YELLOW+"Trust list is empty.");return;}
            player.sendMessage(ChatColor.AQUA+"Trusted UUIDs:");
            for (UUID id : data.trusted) {
                String name = Bukkit.getOfflinePlayer(id).getName();
                String displayName = name == null ? id.toString() : name + " (" + id + ")";
                player.sendMessage(ChatColor.GRAY + displayName);
            }
            return;
        }
        if(args[1].equalsIgnoreCase("remove")){
            if(args.length<3){player.sendMessage("/ub trust remove <name|uuid>");return;}
            UUID chosen=null;
            for(UUID id:data.trusted){
                if(id.toString().equalsIgnoreCase(args[2])){chosen=id;break;}
                String known=Bukkit.getOfflinePlayer(id).getName();
                if(known!=null && known.equalsIgnoreCase(args[2])){chosen=id;break;}
            }
            if(chosen==null)player.sendMessage(ChatColor.RED+"No matching trusted player.");
            else{data.trusted.remove(chosen);p.players().save(data);player.sendMessage(ChatColor.GREEN+"Trust removed.");}
            return;
        }
        Player other=Bukkit.getPlayerExact(args[1]);
        if(other==null || other.equals(player)){player.sendMessage(ChatColor.RED+"Specify another online player.");return;}
        if(!data.trusted.add(other.getUniqueId()))data.trusted.remove(other.getUniqueId());
        p.players().save(data);
        player.sendMessage(ChatColor.AQUA+"Trust for "+other.getName()+": "+data.trusted.contains(other.getUniqueId()));
    }
    private void configSet(CommandSender sender,String[] args){
        if(args.length<4){sender.sendMessage("/ub set <global|skillsets> <path> <value>");return;}
        String which=args[1].toLowerCase(Locale.ROOT);
        if(!which.equals("global") && !which.equals("skillsets")){sender.sendMessage("Use global or skillsets.");return;}
        if(args[2].contains("..") || args[2].startsWith(".")){sender.sendMessage("Invalid path.");return;}
        File file=new File(p.getDataFolder(),which.equals("global")?"config.yml":"skillsets.yml");
        try{
            Files.createDirectories(new File(p.getDataFolder(),"backups").toPath());
            Path backup=new File(p.getDataFolder(),"backups/"+(which.equals("global")?"config":"skillsets")+"-"+System.currentTimeMillis()+".yml").toPath();
            Files.copy(file.toPath(),backup,StandardCopyOption.REPLACE_EXISTING);
            YamlConfiguration yaml=YamlConfiguration.loadConfiguration(file);
            String val=String.join(" ",Arrays.copyOfRange(args,3,args.length));
            Object parsed;if(val.equalsIgnoreCase("true")||val.equalsIgnoreCase("false"))parsed=Boolean.valueOf(val);
            else{try{parsed=Integer.parseInt(val);}catch(NumberFormatException e){try{parsed=Double.parseDouble(val);}catch(NumberFormatException e2){parsed=val;}}}
            yaml.set(args[2],parsed);yaml.save(file);
            if(!p.reloadSettings()){
                Files.copy(backup,file.toPath(),StandardCopyOption.REPLACE_EXISTING);
                p.reloadSettings();sender.sendMessage(ChatColor.RED+"Invalid edit; reverted from backup.");return;
            }
            sender.sendMessage(ChatColor.GREEN+"Saved "+args[2]+" = "+val+". Backup created.");
        }catch(Exception e){sender.sendMessage(ChatColor.RED+"Cannot save config: "+e.getMessage());}
    }
    /** Main hand + off hand potions + tagged catalyst in inventory -> one custom potion. */
    private void infuse(Player player){
        PlayerStore.Data d=p.players().get(player.getUniqueId());
        if(p.getConfig().getBoolean("potions.combine-requires-alchemist",true) && !"ALCHEMIST".equals(d.skillset)){
            player.sendMessage(ChatColor.RED+"Alchemist class required.");return;
        }
        ItemStack a=player.getInventory().getItemInMainHand(),b=player.getInventory().getItemInOffHand();
        if(a.getType()!=Material.POTION || b.getType()!=Material.POTION ||
            !(a.getItemMeta() instanceof PotionMeta left) || !(b.getItemMeta() instanceof PotionMeta right)){
            player.sendMessage(ChatColor.YELLOW+"Hold one potion in each hand and carry Infusium.");return;
        }
        int catalyst=-1;
        for(int i=0;i<36;i++)if(p.items().is(player.getInventory().getItem(i),CustomItems.INFUSIUM)){catalyst=i;break;}
        if(catalyst<0){player.sendMessage(ChatColor.RED+"You need Infusium.");return;}
        Map<PotionEffectType,PotionEffect> all=new LinkedHashMap<>();
        addEffects(all,left);addEffects(all,right);
        int max=p.players().rank(d,"potion-stacking")>0?3:2;
        if(all.size()<2 || all.size()>max){player.sendMessage(ChatColor.RED+"Requires 2-"+max+" distinct effects (currently "+all.size()+").");return;}
        ItemStack output=new ItemStack(Material.POTION);
        PotionMeta result=(PotionMeta)output.getItemMeta();
        result.setBasePotionType(org.bukkit.potion.PotionType.WATER);
        for(PotionEffect effect:all.values())result.addCustomEffect(effect,true);
        result.setDisplayName(ChatColor.DARK_PURPLE+"Infused Potion ("+all.size()+" effects)");
        output.setItemMeta(result);
        // Full validation happens before item removal. A one-item potion fills the emptied main-hand slot.
        if(a.getAmount()!=1 || b.getAmount()!=1){player.sendMessage(ChatColor.RED+"Use one potion per hand.");return;}
        ItemStack ingredient=player.getInventory().getItem(catalyst);
        if(ingredient==null || !p.items().is(ingredient,CustomItems.INFUSIUM))return;
        if(ingredient.getAmount()==1)player.getInventory().setItem(catalyst,null);
        else ingredient.setAmount(ingredient.getAmount()-1);
        player.getInventory().setItemInOffHand(null);
        player.getInventory().setItemInMainHand(output);
        player.sendMessage(ChatColor.GREEN+"Combined potion created with "+all.size()+" distinct effects.");
    }
    private void addEffects(Map<PotionEffectType,PotionEffect> out,PotionMeta meta){
        if(meta.getBasePotionType()!=null)for(PotionEffect effect:meta.getBasePotionType().getPotionEffects())out.putIfAbsent(effect.getType(),effect);
        for(PotionEffect effect:meta.getCustomEffects())out.putIfAbsent(effect.getType(),effect);
    }
    @Override public List<String> onTabComplete(CommandSender s,Command c,String alias,String[] args){
        if(args.length==1)return prefix(List.of("helpbook","choose","skilltree","trust","ability","equip","recipes","infuse","status","refund","branch","items","config","points","set","reload"),args[0]);
        if(args.length==2 && args[0].equalsIgnoreCase("helpbook"))return prefix(p.skills().all().stream().map(SkillRegistry.Skillset::id).toList(),args[1]);
        if(args.length==2 && (args[0].equalsIgnoreCase("ability")||args[0].equalsIgnoreCase("equip")) && s instanceof Player player){
            PlayerStore.Data d=p.players().get(player.getUniqueId());
            SkillRegistry.Skillset skillset=p.skills().get(d.skillset);
            if(skillset!=null)return prefix(new ArrayList<>(skillset.actives().keySet()),args[1]);
        }
        if(args.length==3 && args[0].equalsIgnoreCase("equip"))return prefix(List.of("1","2"),args[2]);
        if(args.length==2 && args[0].equalsIgnoreCase("trust"))return prefix(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(),args[1]);
        return List.of();
    }
    private List<String> prefix(List<String> items,String input){return items.stream().filter(x->x.toLowerCase(Locale.ROOT).startsWith(input.toLowerCase(Locale.ROOT))).limit(40).toList();}
}
