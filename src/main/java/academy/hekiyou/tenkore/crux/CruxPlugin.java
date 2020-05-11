package academy.hekiyou.tenkore.crux;

import academy.hekiyou.door.FrontDoor;
import academy.hekiyou.tenkore.Tenkore;
import academy.hekiyou.tenkore.plugin.TenkorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public class CruxPlugin extends TenkorePlugin {

    private static Tenkore core;
    
    @Override
    public void enable(){
        FrontDoor.load(GeneralModule.class);
        Bukkit.getPluginManager().registerEvents(FrontDoor.load(Teleporting.class), (JavaPlugin)getCore());
        
        core = getCore();
    }
    
    @Override
    public void disable(){
        // let jvm gc the core if we unload
        core = null;
    }
    
    @NotNull
    public static Tenkore getTenkore(){
        return core;
    }
    
}
