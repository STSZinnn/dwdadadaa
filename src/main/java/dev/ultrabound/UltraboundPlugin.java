package dev.ultrabound;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;

/** Paper-only plugin bootstrap. No NMS, client mods, or runtime downloads. */
public final class UltraboundPlugin extends JavaPlugin {
    private PlayerStore players;
    private SkillRegistry registry;
    private CustomItems items;
    private AbilityService abilities;
    private MenuService menus;
    private GameplayListener events;

    @Override public void onEnable() {
        saveDefaultConfig();
        saveResourceIfAbsent("skillsets.yml");
        saveResourceIfAbsent("implementation-status.yml");
        registry = new SkillRegistry(this);
        if (!registry.reload()) {
            getLogger().severe("Invalid skillsets.yml: disabling plugin before touching player data.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        players = new PlayerStore(this);
        items = new CustomItems(this);
        abilities = new AbilityService(this);
        menus = new MenuService(this);
        events = new GameplayListener(this);
        Bukkit.getPluginManager().registerEvents(events, this);
        Bukkit.getPluginManager().registerEvents(menus, this);
        UltraboundCommand command = new UltraboundCommand(this);
        getCommand("ultrabound").setExecutor(command);
        getCommand("ultrabound").setTabCompleter(command);
        getLogger().info("Ultrabound 0.1.0-alpha: core systems enabled; see implementation-status.yml for incomplete mechanics.");
    }

    @Override public void onDisable() {
        if (abilities != null) abilities.restoreAllInvisible();
        if (players != null) players.flush();
        if (events != null) events.saveOreMarkers();
    }

    private void saveResourceIfAbsent(String path) {
        if (!new File(getDataFolder(), path).exists()) saveResource(path, false);
    }
    public PlayerStore players() { return players; }
    public SkillRegistry skills() { return registry; }
    public CustomItems items() { return items; }
    public AbilityService abilities() { return abilities; }
    public MenuService menus() { return menus; }
    public GameplayListener events() { return events; }

    /** Config reload never touches /playerdata/ nor cooldown state. */
    public boolean reloadSettings() {
        YamlConfiguration next=YamlConfiguration.loadConfiguration(new File(getDataFolder(),"config.yml"));
        if(!valid(next)) return false;
        if (!registry.reload()) return false;
        reloadConfig();
        return true;
    }
    private boolean valid(YamlConfiguration y){
        return between(y.getInt("progression.starting-points",3),0,1000)
            && between(y.getInt("progression.total-allocated-cap",30),0,10000)
            && between(y.getInt("progression.branch-allocated-cap",15),0,10000)
            && between(y.getInt("progression.natural-ore-breaks-per-point",24),1,100000)
            && between(y.getInt("progression.engineer-branch-unlock-cost",2),1,1000)
            && between(y.getInt("ability.slots",2),1,4)
            && between(y.getInt("infinite-tnt.fuse-ticks",80),1,200)
            && between(y.getInt("infinite-tnt.cooldown-ticks",40),1,1200)
            && between(y.getInt("infinite-tnt.max-active-per-player",3),0,50)
            && between(y.getInt("enchanter.max-enchant-level",6),1,255)
            && between(y.getInt("enchanter.ascension-xp-level-cost",5),0,1000)
            && y.getDouble("potions.infusium-zombie-drop-chance",.015)>=0
            && y.getDouble("potions.infusium-zombie-drop-chance",.015)<=.5;
    }
    private boolean between(int value,int low,int high){return value>=low && value<=high;}
}
