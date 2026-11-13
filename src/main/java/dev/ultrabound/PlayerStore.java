package dev.ultrabound;

import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.File;
import java.util.*;

/** UUID-keyed records, separate from config. Files never overwritten by a reload. */
public final class PlayerStore {
    private final UltraboundPlugin plugin;
    private final Map<UUID,Data> cache=new HashMap<>();
    public static final class Data {
        public final UUID uuid;
        public String skillset=null;
        public boolean kitClaimed=false;
        public int availablePoints=0, earnedPoints=0, allocatedPoints=0, oreProgress=0;
        public final Map<String,Integer> ranks=new LinkedHashMap<>();
        public final List<String> equipped=new ArrayList<>();
        public final Set<String> unlockedBranches=new LinkedHashSet<>();
        public final Set<UUID> trusted=new LinkedHashSet<>();
        public final Map<String,Long> cooldowns=new HashMap<>();
        public int storedXp=0;
        public Data(UUID uuid){this.uuid=uuid;}
    }
    PlayerStore(UltraboundPlugin p){plugin=p;new File(p.getDataFolder(),"playerdata").mkdirs();}
    public Data get(UUID id){return cache.computeIfAbsent(id,this::load);}
    private Path path(UUID id){return new File(plugin.getDataFolder(),"playerdata/"+id+".yml").toPath();}
    private Data load(UUID id){
        Data d=new Data(id);Path file=path(id);
        if(!Files.exists(file)){
            d.availablePoints=Math.max(0,plugin.getConfig().getInt("progression.starting-points",3));
            d.earnedPoints=d.availablePoints;return d;
        }
        YamlConfiguration y=YamlConfiguration.loadConfiguration(file.toFile());
        d.skillset=y.getString("class",null);
        d.kitClaimed=y.getBoolean("kit-claimed",false);
        d.availablePoints=Math.max(0,y.getInt("available-points",0));
        d.earnedPoints=Math.max(d.availablePoints,y.getInt("earned-points",0));
        d.allocatedPoints=Math.max(0,y.getInt("allocated-points",0));
        d.oreProgress=Math.max(0,y.getInt("ore-progress",0));
        d.storedXp=Math.max(0,y.getInt("stored-xp",0));
        if(y.isConfigurationSection("ranks"))for(String k:y.getConfigurationSection("ranks").getKeys(false))d.ranks.put(k,Math.max(0,y.getInt("ranks."+k)));
        d.equipped.addAll(y.getStringList("equipped-actives"));
        d.unlockedBranches.addAll(y.getStringList("unlocked-branches"));
        if("ENGINEER".equals(d.skillset))d.unlockedBranches.add("mechanics");
        if(y.isConfigurationSection("cooldowns"))for(String k:y.getConfigurationSection("cooldowns").getKeys(false))d.cooldowns.put(k,Math.max(0,y.getLong("cooldowns."+k)));
        for(String s:y.getStringList("trusted-uuids"))try{d.trusted.add(UUID.fromString(s));}catch(Exception ignored){}
        return d;
    }
    public void save(Data d){
        YamlConfiguration y=new YamlConfiguration();
        y.set("class",d.skillset);y.set("kit-claimed",d.kitClaimed);
        y.set("earned-points",d.earnedPoints);y.set("available-points",d.availablePoints);
        y.set("allocated-points",d.allocatedPoints);y.set("ore-progress",d.oreProgress);
        y.set("stored-xp",d.storedXp);
        y.set("ranks",new LinkedHashMap<>(d.ranks));
        y.set("cooldowns",new HashMap<>(d.cooldowns));
        y.set("trusted-uuids",d.trusted.stream().map(UUID::toString).toList());
        y.set("equipped-actives",new ArrayList<>(d.equipped));
        y.set("unlocked-branches",new ArrayList<>(d.unlockedBranches));
        Path target=path(d.uuid),temp=target.resolveSibling(target.getFileName()+".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(temp,y.saveToString(),StandardCharsets.UTF_8);
            try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
        }catch(Exception e){plugin.getLogger().severe("Could not save player record for "+d.uuid+": "+e.getMessage());}
    }
    public void flush(){for(Data d:new ArrayList<>(cache.values()))save(d);}
    public void unload(UUID id){Data d=cache.remove(id);if(d!=null)save(d);}
    public boolean isTrusted(UUID owner, UUID target){return get(owner).trusted.contains(target);}
    public int rank(Data d,String node){return d.ranks.getOrDefault(node,0);}
    public String buy(Data d,String nodeId){
        SkillRegistry.Skillset s=plugin.skills().get(d.skillset);
        if(s==null)return "Choose a skillset first.";
        SkillRegistry.Node n=plugin.skills().node(d.skillset,nodeId);
        if(n==null)return "No such node.";
        if(!n.implemented())return "Design preview only — this node does not have a live effect yet.";
        int current=rank(d,nodeId);
        if(current>=n.maxRank())return "Already at maximum rank.";
        if(d.availablePoints<n.cost())return "Not enough skill points.";
        int max=s.allocationCap()==0?Integer.MAX_VALUE:s.allocationCap();
        max=Math.min(max,plugin.getConfig().getInt("progression.total-allocated-cap",30));
        if(s.id().equals("ENGINEER"))max=Integer.MAX_VALUE;
        if(d.allocatedPoints+n.cost()>max)return "Class allocation cap reached.";
        String branch=plugin.skills().branchFor(d.skillset,nodeId);
        if(s.id().equals("ENGINEER") && !"mechanics".equals(branch) && !d.unlockedBranches.contains(branch))return "Unlock that Engineer branch first.";
        int branchAllocated=0;
        SkillRegistry.Branch b=s.branches().get(branch);
        if(b!=null) for(SkillRegistry.Node other:b.nodes().values())branchAllocated+=rank(d,other.id())*other.cost();
        int branchCap=s.id().equals("ENGINEER")?Integer.MAX_VALUE:Math.min(s.branchCap(),plugin.getConfig().getInt("progression.branch-allocated-cap",15));
        if(branchAllocated+n.cost()>branchCap)return "Branch allocation cap reached.";
        for(String prerequisite:n.prerequisites()){
            String[] parts=prerequisite.split(":",2);
            int required=parts.length==2?Integer.parseInt(parts[1]):1;
            if(rank(d,parts[0])<required)return "Requires "+prerequisite;
        }
        d.availablePoints-=n.cost();d.allocatedPoints+=n.cost();d.ranks.put(nodeId,current+1);
        save(d);return "Purchased "+n.name()+" ("+(current+1)+"/"+n.maxRank()+").";
    }
    public String unlockBranch(Data d,String branch){
        if(!"ENGINEER".equals(d.skillset))return "Only Engineer has paid branch unlocks.";
        SkillRegistry.Skillset skill=plugin.skills().get("ENGINEER");
        if(skill==null || !skill.branches().containsKey(branch))return "Unknown Engineer branch.";
        if(d.unlockedBranches.contains(branch))return "Branch already unlocked.";
        int price=branch.equals("mechanics")?0:Math.max(1,plugin.getConfig().getInt("progression.engineer-branch-unlock-cost",2));
        if(d.availablePoints<price)return "Not enough skill points (needs "+price+").";
        d.availablePoints-=price;d.allocatedPoints+=price;d.unlockedBranches.add(branch);save(d);
        return "Unlocked "+branch+" for "+price+" points.";
    }
    public void refundAll(Data d){d.availablePoints+=d.allocatedPoints;d.allocatedPoints=0;d.ranks.clear();d.unlockedBranches.clear();
        if("ENGINEER".equals(d.skillset))d.unlockedBranches.add("mechanics");save(d);}
    public void changeClass(Data d,String id){
        refundAll(d);d.skillset=id;d.unlockedBranches.clear();
        if("ENGINEER".equals(id))d.unlockedBranches.add("mechanics");
        d.equipped.clear();d.cooldowns.clear();d.kitClaimed=false;d.oreProgress=0;save(d);
    }
}
