package dev.ultrabound;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.util.*;

/** Validates an editable data registry. No partial replacement on invalid reload. */
public final class SkillRegistry {
    public record Node(String id,String name,int maxRank,int cost,double perRank,String mechanic,
                       String description,List<String> prerequisites,boolean implemented) { }
    public record Active(String id,String name,String type,int cooldown,int duration,
                         String description,boolean implemented,boolean unlockedByDefault,ConfigurationSection raw) { }
    public record Branch(String id,String name,LinkedHashMap<String,Node> nodes) { }
    public record Skillset(String id,String name,String category,Material icon,
                           int allocationCap,int branchCap,LinkedHashMap<String,Branch> branches,
                           LinkedHashMap<String,Active> actives) { }
    private final UltraboundPlugin plugin;
    private LinkedHashMap<String,Skillset> skills = new LinkedHashMap<>();
    SkillRegistry(UltraboundPlugin plugin){this.plugin=plugin;}

    public boolean reload() {
        YamlConfiguration yaml=YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"skillsets.yml"));
        ConfigurationSection all=yaml.getConfigurationSection("classes");
        if(all==null) return false;
        LinkedHashMap<String,Skillset> next=new LinkedHashMap<>();
        try {
            for (String id:all.getKeys(false)) {
                ConfigurationSection s=all.getConfigurationSection(id);
                if(s==null) throw new IllegalStateException("Missing class "+id);
                Material icon=Material.matchMaterial(s.getString("icon","PAPER"));
                if(icon==null) icon=Material.PAPER;
                LinkedHashMap<String,Branch> branches=new LinkedHashMap<>();
                ConfigurationSection bs=s.getConfigurationSection("branches");
                if(bs!=null) for(String bid:bs.getKeys(false)){
                    ConfigurationSection b=bs.getConfigurationSection(bid);
                    LinkedHashMap<String,Node> nodes=new LinkedHashMap<>();
                    ConfigurationSection ns=b.getConfigurationSection("nodes");
                    if(ns!=null) for(String nid:ns.getKeys(false)){
                        ConfigurationSection n=ns.getConfigurationSection(nid);
                        if(n==null)continue;
                        int cap=n.getInt("max-rank",1),cost=n.getInt("cost",1);
                        if(cap<1 || cap>20 || cost<1 || cost>1000)throw new IllegalArgumentException("Invalid node cap/cost: "+nid);
                        nodes.put(nid,new Node(nid,n.getString("name",nid),cap,cost,
                            n.getDouble("value-per-rank",0),n.getString("mechanic","planned"),
                            n.getString("description",""),n.getStringList("prerequisites"),n.getBoolean("implemented",false)));
                    }
                    branches.put(bid,new Branch(bid,b.getString("label",bid),nodes));
                }
                LinkedHashMap<String,Active> acts=new LinkedHashMap<>();
                ConfigurationSection as=s.getConfigurationSection("actives");
                if(as!=null)for(String aid:as.getKeys(false)){
                    ConfigurationSection a=as.getConfigurationSection(aid);
                    int cd=a.getInt("cooldown-seconds",60),dur=a.getInt("duration-seconds",6);
                    if(cd<0 || cd>86400 || dur<0 || dur>3600)throw new IllegalArgumentException("Invalid ability duration/cooldown: "+aid);
                    acts.put(aid,new Active(aid,a.getString("name",aid),a.getString("type","PLANNED"),cd,dur,
                       a.getString("description",""),a.getBoolean("implemented",false),a.getBoolean("unlocked-by-default",false),a));
                }
                next.put(id.toUpperCase(Locale.ROOT),new Skillset(id.toUpperCase(Locale.ROOT),s.getString("name",id),
                    s.getString("category","UNASSIGNED"),icon,Math.max(0,s.getInt("allocation-cap",30)),
                    Math.max(0,s.getInt("branch-cap",15)),branches,acts));
            }
            if(next.size()!=12 || !next.containsKey("ENGINEER") || !next.containsKey("MINER"))return false;
        }catch(Exception e){plugin.getLogger().warning("Skillset config validation failed: "+e.getMessage());return false;}
        skills=next;
        return true;
    }
    public Collection<Skillset> all(){return Collections.unmodifiableCollection(skills.values());}
    public Skillset get(String id){return id==null?null:skills.get(id.toUpperCase(Locale.ROOT).replace(' ','_').replace('-','_'));}
    public Node node(String cls,String nodeId){
        Skillset s=get(cls);if(s==null)return null;
        for(Branch b:s.branches().values())if(b.nodes().containsKey(nodeId))return b.nodes().get(nodeId);
        return null;
    }
    public String branchFor(String cls,String nodeId){
        Skillset s=get(cls);if(s==null)return null;
        for(Branch b:s.branches().values())if(b.nodes().containsKey(nodeId))return b.id();
        return null;
    }
    public Active active(String cls,String ability){Skillset s=get(cls);return s==null?null:s.actives().get(ability.toLowerCase(Locale.ROOT));}
}
