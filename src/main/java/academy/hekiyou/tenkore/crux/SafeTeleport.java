package academy.hekiyou.tenkore.crux;

import academy.hekiyou.door.annotations.Module;
import academy.hekiyou.door.annotations.RegisterCommand;
import academy.hekiyou.door.annotations.optional.OptionalObject;
import academy.hekiyou.door.annotations.optional.OptionalString;
import academy.hekiyou.door.exception.BadInterpretationException;
import academy.hekiyou.door.interp.Interpreter;
import academy.hekiyou.door.interp.Interpreters;
import academy.hekiyou.door.model.Invoker;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

@Module
@Deprecated
public class SafeTeleport {
    
    @RegisterCommand(
            permission = "crux.teleporting.teleport",
            description = "Teleports you to your target location.",
            alias = "tp",
            usage = {"[player-to-move=self] or <player-to-move-to>", "<x>", "<y>", "<z>", "[-f=\"\"]"},
            override = true
    )
    // this has been and always will be the most difficult command to write
    public void teleport(Invoker invoker,
                         @OptionalObject("self") Player target,
                         @OptionalString("") String x,
                         @OptionalString("") String y,
                         @OptionalString("") String z,
                         @OptionalString("") String forceFlag){
        Location dest = null;
        Player source = null;
        
        if(target != null && !x.isEmpty() && !x.equals("-f") && y.isEmpty()){
            // most likely called /tp player1 player2
            Player other = Bukkit.getPlayer(x);
            if(other == null){
                invoker.sendMessage(ChatColor.RED + "Player does not exist: %s", x);
                return;
            }
            source = target;
            dest = other.getLocation();
        }
        
        if(!x.isEmpty() && !y.isEmpty() && !z.isEmpty()){
            // most likely called /tp x y z or /tp player2 x y z
            double[] coords = parsePositionValue(target, new String[]{x, y, z});
            if(coords == null)
                return;
            source = target == null ? invoker.as(Player.class) : target;
            dest = new Location(invoker.as(Player.class).getWorld(), coords[0], coords[1], coords[2]);
        }
        
        if(dest == null && target != null && target != invoker.raw() && (x.isEmpty() || x.equals("-f"))){
            // most likely called /tp player2
            source = invoker.as(Player.class);
            dest = target.getLocation();
        }
        
        if(dest == null){
            invoker.sendMessage(ChatColor.RED + "Couldn't figure out how to teleport you...");
            return;
        }
        
        // this looks weird in code, but imagine a scenario where "target" in inside of a block, suffocating
        // the executed command would be: /teleport target -f
        // hopefully that clears up why we check if one of the various parameters is the force flag.
        // somewhat defeats the purpose of door, but we really don't have any option
        boolean force = x.equals("-f") || // check for /tp player2 -f
                z.equals("-f") || // check for /tp player1 player2 -f
                forceFlag.equals("-f"); // check for /tp player1 x y z -f or /tp x y z
        if(!isSafeDestination(dest) && !force && source.getGameMode() != GameMode.CREATIVE){
            invoker.sendMessage(ChatColor.RED + "Teleport denied - expected suffocation.");
            invoker.sendMessage(ChatColor.RED + "Change to Creative or force with \"-f\"");
            return;
        }
        
        // try to load the chunk first, to prevent the player from having to wait for it first
        // may also fix synchronization issues with players loading in _before_ chunks load
        // and thus, because of how beautifully well made minecraft is (/s), clients fall into the void
        Chunk chunk = dest.getChunk();
        chunk.load();
        source.teleport(dest);
        
        // don't mark the chunk as force loaded; technically, the chunk would be loaded without Chunk.load() otherwise
        // thus, it shouldn't be viewed as "forced" in the end
        chunk.setForceLoaded(false);
        
        invoker.sendMessage(ChatColor.YELLOW + "Teleported.");
    }
    
    private boolean isSafeDestination(Location loc){
        return loc.clone().add(0, 1, 0).getBlock().isPassable();
    }
    
    private @Nullable double[] parsePositionValue(Player player, @NotNull String[] coordinates){
        Interpreter<Double> interp = Objects.requireNonNull(Interpreters.of(double.class));
        
        double[] loc = { 0, 0, 0 };
        double[] ref = null;
        
        for(int i = 0; i < 3; i++){
            String coord = coordinates[i];
            
            try {
                if(coord.startsWith("~")){
                    if(ref == null){
                        Location playerLoc = player.getLocation();
                        ref = new double[]{playerLoc.getX(), playerLoc.getY(), playerLoc.getZ()};
                    }
                    
                    if(coord.length() > 1){
                        loc[i] = ref[i] + interp.apply(coord.substring(1));
                    } else {
                        loc[i] = ref[i];
                    }
                } else {
                    loc[i] = interp.apply(coord);
                }
            } catch (BadInterpretationException exc){
                player.sendMessage(ChatColor.RED + "Unable to process coordinate: " + coord);
                return null;
            }
        }
        
        return loc;
    }
    
}
