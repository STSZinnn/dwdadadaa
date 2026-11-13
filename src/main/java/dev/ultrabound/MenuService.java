package dev.ultrabound;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.*;

/** All custom inventories are private server-side holders; clicks and drags cannot extract GUI icons. */
public final class MenuService implements Listener {
    static final class Holder implements InventoryHolder {
        final String screen,cls,branch;
        final boolean changingClass;
        final Map<Integer,String> choices=new HashMap<>();
        Inventory inventory;
        Holder(String screen,String cls,String branch,boolean changingClass){
            this.screen=screen;this.cls=cls;this.branch=branch;this.changingClass=changingClass;
        }
        @Override public Inventory getInventory(){return inventory;}
    }
    private final UltraboundPlugin p;
    MenuService(UltraboundPlugin plugin){p=plugin;}
    private Inventory make(Holder h,int size,String name){h.inventory=Bukkit.createInventory(h,size,ChatColor.DARK_AQUA+"Ultrabound » "+name);return h.inventory;}
    private ItemStack icon(Material mat,String name,String...description){
        ItemStack item=new ItemStack(mat);ItemMeta m=item.getItemMeta();m.setDisplayName(name);
        List<String> lore=new ArrayList<>();for(String s:description)lore.add(ChatColor.GRAY+s);
        m.setLore(lore);item.setItemMeta(m);return item;
    }
    private void nav(Inventory i){i.setItem(i.getSize()-1,icon(Material.BARRIER,ChatColor.RED+"Back / Close"));}
    public void choose(Player player,boolean changing){
        Holder h=new Holder("choose",null,null,changing);Inventory inv=make(h,54,changing?"Change Class":"Choose Class");
        int n=0;
        for(SkillRegistry.Skillset s:p.skills().all()){
            int slot=10+(n/9)*9+(n%9);n++;
            inv.setItem(slot,icon(s.icon(),ChatColor.AQUA+s.name(),"Role: "+s.category(),"Click to inspect and confirm.","Starting gear respects confirmed design."));
            h.choices.put(slot,s.id());
        }
        nav(inv);player.openInventory(inv);
    }
    private void confirm(Player player,String id,boolean changing){
        SkillRegistry.Skillset s=p.skills().get(id);if(s==null)return;
        Holder h=new Holder("confirm",s.id(),null,changing);Inventory inv=make(h,27,"Confirm "+s.name());
        inv.setItem(11,icon(Material.LIME_CONCRETE,ChatColor.GREEN+"CONFIRM", "Choose "+s.name(),"Your class is locked until reset."));
        inv.setItem(15,icon(Material.RED_CONCRETE,ChatColor.RED+"CANCEL"));
        inv.setItem(13,icon(s.icon(),ChatColor.AQUA+s.name(),"Category: "+s.category(), "Confirmed starter kits granted once."));
        player.openInventory(inv);
    }
    public void help(Player player){
        Holder h=new Holder("help",null,null,false);Inventory inv=make(h,54,"Helpbook");
        int n=0;for(SkillRegistry.Skillset s:p.skills().all()){
            int slot=10+(n/9)*9+(n%9);n++;
            inv.setItem(slot,icon(s.icon(),ChatColor.AQUA+s.name(),"Category: "+s.category(),"Click for branches and abilities"));h.choices.put(slot,s.id());
        }
        nav(inv);player.openInventory(inv);
    }
    public void guide(Player player,String cls){
        SkillRegistry.Skillset s=p.skills().get(cls);if(s==null)return;
        Holder h=new Holder("guide",s.id(),null,false);Inventory inv=make(h,54,s.name()+" Guide");
        inv.setItem(4,icon(s.icon(),ChatColor.AQUA+s.name(),"Category: "+s.category(),"Branches: "+s.branches().size(),"Active abilities: "+s.actives().size()));
        int slot=10;for(SkillRegistry.Branch b:s.branches().values()){
            inv.setItem(slot,icon(Material.BOOK,ChatColor.YELLOW+b.name(),"Passive nodes: "+b.nodes().size()));
            h.choices.put(slot,b.id());slot++;
        }
        inv.setItem(31,icon(Material.BLAZE_POWDER,ChatColor.LIGHT_PURPLE+"Abilities","Click for active ability details"));
        nav(inv);player.openInventory(inv);
    }
    public void tree(Player player){
        PlayerStore.Data data=p.players().get(player.getUniqueId());
        SkillRegistry.Skillset s=p.skills().get(data.skillset);
        if(s==null){player.sendMessage(ChatColor.RED+"Choose a class first.");return;}
        Holder h=new Holder("tree",s.id(),null,false);Inventory inv=make(h,54,"Skill Tree • "+s.name());
        int slot=10;for(SkillRegistry.Branch branch:s.branches().values()){
            boolean locked=s.id().equals("ENGINEER") && !"mechanics".equals(branch.id()) && !data.unlockedBranches.contains(branch.id());
            inv.setItem(slot,icon(locked?Material.BARRIER:Material.BOOK,ChatColor.AQUA+branch.name(),
                "Nodes: "+branch.nodes().size(),locked?"LOCKED - click to unlock using points":"Click to browse"));
            h.choices.put(slot,branch.id());slot++;
        }
        inv.setItem(31,icon(Material.BLAZE_POWDER,ChatColor.LIGHT_PURPLE+"Equipped Actives","Click to select ability loadout"));
        inv.setItem(40,icon(Material.EMERALD,ChatColor.GOLD+"Available: "+data.availablePoints,
            "Earned: "+data.earnedPoints,"Allocated: "+data.allocatedPoints,
            "Engineer has no global allocation cap"));
        nav(inv);player.openInventory(inv);
    }
    private void nodes(Player player,String cls,String branch){
        SkillRegistry.Skillset s=p.skills().get(cls);if(s==null)return;
        SkillRegistry.Branch b=s.branches().get(branch);if(b==null)return;
        Holder h=new Holder("nodes",s.id(),branch,false);Inventory inv=make(h,54,b.name()+" Nodes");
        PlayerStore.Data d=p.players().get(player.getUniqueId());
        int n=0;
        for(SkillRegistry.Node node:b.nodes().values()){
            if(n>=45)break;
            int slot=n++;
            int current=p.players().rank(d,node.id());
            Material material=!node.implemented()?Material.BARRIER:current>=node.maxRank()?Material.EMERALD_BLOCK:Material.AMETHYST_SHARD;
            String status=!node.implemented()?"DESIGN PREVIEW":current>=node.maxRank()?"MAXED":"CLICK TO PURCHASE";
            String detail="Per rank: "+node.perRank()+" | mechanic: "+node.mechanic();
            inv.setItem(slot,icon(material,ChatColor.AQUA+node.name(),node.description(),"Rank "+current+" / "+node.maxRank(),
                "Next cost: "+node.cost(),"Current value: "+(current*node.perRank()),
                "Next value: "+(Math.min(node.maxRank(),current+1)*node.perRank()),detail,"Prerequisites: "+node.prerequisites(),status));
            h.choices.put(slot,node.id());
        }
        nav(inv);player.openInventory(inv);
    }
    private void actives(Player player,String cls){
        SkillRegistry.Skillset s=p.skills().get(cls);if(s==null)return;
        Holder h=new Holder("actives",s.id(),null,false);Inventory inv=make(h,54,"Active Abilities");
        PlayerStore.Data d=p.players().get(player.getUniqueId());
        int n=0;for(SkillRegistry.Active a:s.actives().values()){
            if(n>=45)break;int slot=n++;
            int existing=d.equipped.indexOf(a.id());
            inv.setItem(slot,icon(a.implemented()?Material.BLAZE_POWDER:Material.BARRIER,ChatColor.AQUA+a.name(),
                a.description(),"Type: "+a.type(),"Cooldown: "+a.cooldown()+"s",
                a.implemented()?"Left click: equip slot 1 | Right click: slot 2":"DESIGN PREVIEW • no game logic yet",
                existing>=0?"Equipped slot "+(existing+1):"Not equipped"));
            h.choices.put(slot,a.id());
        }
        nav(inv);player.openInventory(inv);
    }
    public void recipes(Player player){
        Holder h=new Holder("recipes",null,null,false);Inventory inv=make(h,27,"Recipe Browser");
        inv.setItem(10,icon(Material.AMETHYST_SHARD,ChatColor.LIGHT_PURPLE+"Infusium","Special Alchemist catalyst.","Use /ultrabound infuse with two potions."));
        inv.setItem(12,icon(Material.SHIELD,ChatColor.AQUA+"Guardian Shield","Bound to one Guardian and returned on death."));
        inv.setItem(14,icon(Material.TNT,ChatColor.GOLD+"Infinite TNT","Bombardeer starter item only."));
        inv.setItem(16,icon(Material.ANVIL,ChatColor.YELLOW+"Engineer Machines","Recipes and machines are planned, not live."));
        nav(inv);player.openInventory(inv);
    }
    public void adminItems(Player player){
        Holder h=new Holder("items",null,null,false);Inventory inv=make(h,27,"Admin Items");
        String[] choices={CustomItems.RESET_CLASS,CustomItems.RESET_TREE,CustomItems.INFUSIUM,CustomItems.INFIN_TNT,CustomItems.GUARDIAN_SHIELD};
        for(int i=0;i<choices.length;i++){
            int slot=10+i;String id=choices[i];
            inv.setItem(slot,p.items().make(id,id.equals(CustomItems.GUARDIAN_SHIELD)||id.equals(CustomItems.INFIN_TNT)?player.getUniqueId():null));
            h.choices.put(slot,id);
        }
        nav(inv);player.openInventory(inv);
    }
    public void adminConfig(Player player){
        Holder h=new Holder("config",null,null,false);Inventory inv=make(h,27,"Admin Settings");
        inv.setItem(10,icon(Material.COMPARATOR,ChatColor.YELLOW+"Progression","Edit safely: /ub set global progression..."));
        inv.setItem(12,icon(Material.BOOK,ChatColor.YELLOW+"Skillsets","Nodes live in skillsets.yml","Reload with /ub reload"));
        inv.setItem(14,icon(Material.REDSTONE,ChatColor.YELLOW+"Ability Values","Edit in skillsets.yml","Reload with /ub reload"));
        inv.setItem(16,icon(Material.PAPER,ChatColor.YELLOW+"Implementation Status","Check implementation-status.yml before testing."));
        nav(inv);player.openInventory(inv);
    }
    @EventHandler public void click(InventoryClickEvent e){
        if(!(e.getView().getTopInventory().getHolder() instanceof Holder h))return;
        e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player player))return;
        int slot=e.getRawSlot();if(slot<0 || slot>=e.getView().getTopInventory().getSize())return;
        if(slot==e.getView().getTopInventory().getSize()-1){player.closeInventory();return;}
        String choice=h.choices.get(slot);
        switch(h.screen){
            case "choose"->{if(choice!=null)confirm(player,choice,h.changingClass);}
            case "confirm"->{
                if(slot==15){choose(player,h.changingClass);return;}
                if(slot!=11)return;
                PlayerStore.Data d=p.players().get(player.getUniqueId());
                if(h.changingClass){
                    if(d.skillset==null || !p.items().is(player.getInventory().getItemInMainHand(),CustomItems.RESET_CLASS)){
                        player.sendMessage(ChatColor.RED+"Hold a valid reset token in your main hand.");return;
                    }
                    ItemStack token=player.getInventory().getItemInMainHand();token.setAmount(token.getAmount()-1);
                }else if(d.skillset!=null){player.sendMessage(ChatColor.RED+"Already selected. Use a Reset Token.");return;}
                p.players().changeClass(d,h.cls);
                p.items().grantKit(player,h.cls);
                player.closeInventory();player.sendMessage(ChatColor.GREEN+"You are now "+p.skills().get(h.cls).name()+"!");
            }
            case "help"->{if(choice!=null)guide(player,choice);}
            case "guide"->{
                if(slot==31)actives(player,h.cls);
                else if(choice!=null)nodes(player,h.cls,choice);
            }
            case "tree"->{
                if(slot==31)actives(player,h.cls);
                else if(choice!=null){
                    PlayerStore.Data data=p.players().get(player.getUniqueId());
                    if(h.cls.equals("ENGINEER") && !data.unlockedBranches.contains(choice)){
                        player.sendMessage(ChatColor.YELLOW+p.players().unlockBranch(data,choice));tree(player);
                    }else nodes(player,h.cls,choice);
                }
            }
            case "nodes"->{
                if(choice==null)return;
                player.sendMessage(ChatColor.AQUA+p.players().buy(p.players().get(player.getUniqueId()),choice));
                nodes(player,h.cls,h.branch);
            }
            case "actives"->{
                if(choice!=null){String result=p.abilities().equip(player,choice,e.isRightClick()?2:1);
                    player.sendMessage(ChatColor.YELLOW+result);actives(player,h.cls);}
            }
            case "items"->{
                if(!player.hasPermission("ultrabound.admin"))return;
                if(choice!=null){
                    UUID owner=choice.equals(CustomItems.GUARDIAN_SHIELD)||choice.equals(CustomItems.INFIN_TNT)?player.getUniqueId():null;
                    p.getServer().getScheduler().runTask(p,()->{
                        var leftovers=player.getInventory().addItem(p.items().make(choice,owner));
                        if(leftovers.isEmpty())player.sendMessage(ChatColor.GREEN+"Granted "+choice);
                        else player.sendMessage(ChatColor.RED+"Inventory full; not granted.");
                    });
                }
            }
            default->{}
        }
    }
    @EventHandler public void drag(InventoryDragEvent e){
        if(e.getView().getTopInventory().getHolder() instanceof Holder)e.setCancelled(true);
    }
}
