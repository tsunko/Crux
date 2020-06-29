package academy.hekiyou.tenkore.crux;

import academy.hekiyou.door.annotations.Module;
import academy.hekiyou.door.annotations.RegisterCommand;
import academy.hekiyou.door.annotations.optional.OptionalObject;
import academy.hekiyou.door.model.Invoker;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.*;

@Module
public class Teleporting implements Listener {
    
    private final Map<UUID, Stack<BlockLocation>> history = new WeakHashMap<>();
    // Map<target player, requesting player>
    private final Map<Player, Stack<Player>> requests = new WeakHashMap<>();
    private final Set<Player> returning = Collections.newSetFromMap(new WeakHashMap<>());

    @RegisterCommand(
            permission = "crux.teleporting.back",
            description = "Teleport to your last recorded location (which can be where you last died or teleported from).",
            alias = "return"
    )
    public void back(Invoker invoker){
        Player player = invoker.as(Player.class);
        Stack<BlockLocation> past = history.get(player.getUniqueId());
        
        if(past == null || past.isEmpty()){
            invoker.sendMessage(ChatColor.RED + "You have no recorded location history!");
        } else {
            returning.add(player);
            player.teleport(past.pop().getRealLocation());
            invoker.sendMessage(ChatColor.YELLOW + "Teleported.");
        }
    }
    
    @RegisterCommand(
            permission = "crux.teleporting.call-and-accept",
            description = "Requests a teleport to a given user.",
            alias = {"tprequest", "tpr"}
    )
    public void call(Invoker invoker, Player target){
        Player player = invoker.as(Player.class);
        Stack<Player> current = requests.computeIfAbsent(target, (p) -> new Stack<>());
        current.push(player);
        target.sendMessage(ChatColor.YELLOW + player.getName() + " has requested a teleport. " +
                "You can accept it with /tpaccept or its aliases.");
        invoker.sendMessage(ChatColor.YELLOW + "Teleport request sent.");
    }
    
    @RegisterCommand(
            permission = "crux.teleporting.call-and-accept",
            description = "Brings a player to you. If you have /tp privileges, this acts also as /tp <them> <you>.",
            alias = {"tpaccept", "tpa"}
    )
    public void bring(Invoker invoker, @OptionalObject("target") Player target){
        Player player = invoker.as(Player.class);
        if(player.hasPermission("crux.teleporting.teleport") && target != null){
            target.teleport(player);
            removeRequest(player, target); // just clear the entry, even if none exists
            return;
        }
        
        Stack<Player> current = requests.get(invoker.as(Player.class));
        
        if(target == null){
            if(current.isEmpty()){
                invoker.sendMessage(ChatColor.RED + "No pending teleport requests.");
                return;
            }
            target = current.pop();
        }
        
        target.teleport(player);
        removeRequest(player, target);
        invoker.sendMessage(ChatColor.GREEN + "Brought " + target.getName());
    }
    
    @RegisterCommand(
            permission = "crux.teleporting.call-and-accept",
            description = "Denies a teleport request from the last or specified player.",
            alias = {"tpdeny", "tpd"}
    )
    public void deny(Invoker invoker, @OptionalObject("target") Player target){
        Stack<Player> current = requests.get(invoker.as(Player.class));
        if(current == null || (current.isEmpty() && target == null)){
            invoker.sendMessage(ChatColor.RED + "You have no pending requests.");
            return;
        }
        
        if(target == null){
            target = current.pop();
        } else {
            if(!current.remove(target)){
                invoker.sendMessage(ChatColor.RED + target.getName() + " didn't request a teleport.");
                return;
            }
        }
        target.sendMessage(ChatColor.RED + "Your request for teleport was denied.");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void handleTeleportEvent(PlayerTeleportEvent event){
        // a bit of a journey to figure out, but essentially, NMS can actually trigger one of these events
        // in a way that appears "randomly". in most scenarios, it's due to a synchronization issue between the server
        // and client, however, it is possible to trigger a seemingly random PlayerTeleportEvent by placing a roof
        // immediately above your head and spamming:
        // CTRL+W+SPACE+LSHIFT (that is, crouch + move forward + jump + sprint) all at once, rapidly
        // this causes your player to _very_ briefly clip into the block; server doesn't like this and teleports you
        // back downwards (so you're no longer clipped), triggering a PlayerTeleportEvent with the cause being UNKNOWN
        
        // this if-statement fix is banking on the fact that (hopefully) UNKNOWN is truly UNKNOWN and is never given
        // as a reason directly from a plugin
        if(event.getCause() == PlayerTeleportEvent.TeleportCause.UNKNOWN)
            return;
        
        Player player = event.getPlayer();

        if(returning.contains(player)){
            // player is returning; remove them off the list
            returning.remove(player);
            return;
        }

        updateHistory(player, event.getFrom());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void handleDeathEvent(PlayerDeathEvent event){
        Player player = event.getEntity();
        updateHistory(player, player.getLocation());
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void handleDisconnectEvent(PlayerQuitEvent event){
        Player player = event.getPlayer();
        requests.remove(player); // immediately clear requests queue; players can just request again
        
        UUID uuid = player.getUniqueId();
        // for on-death events, gracefully remove them after a designated time of 5 minutes-ish
        // if the player relogs, do not remove the entries.
        Bukkit.getScheduler().scheduleSyncDelayedTask((Plugin)CruxPlugin.getTenkore(), ()->{
            if(Bukkit.getPlayer(uuid) == null)
                history.remove(uuid);
        }, 20 * 60 * 5);
    }
    
    private void removeRequest(@NotNull Player from, @NotNull Player target){
        Stack<Player> current = requests.get(from);
        if(current == null)
            return;
        
        current.remove(target);
        // clean up requests
        if(current.isEmpty())
            requests.remove(from);
    }

    private void updateHistory(@NotNull Player player, @NotNull Location loc){
        Stack<BlockLocation> past = history.computeIfAbsent(player.getUniqueId(), (unused)->new Stack<>());
        BlockLocation newLocation = new BlockLocation(loc);
        if(past.contains(newLocation))
            return;
        past.push(newLocation);
    }
    
    private static class BlockLocation {
        
        private final Location realLocation;
        
        BlockLocation(Location location){
            this.realLocation = location;
        }
    
        Location getRealLocation(){
            return realLocation;
        }
    
        @Override
        public boolean equals(Object o){
            if(this == o) return true;
            if(o == null || getClass() != o.getClass()) return false;
            Location that = ((BlockLocation) o).getRealLocation();
            return realLocation.getBlockX() == that.getBlockX() &&
                   realLocation.getBlockY() == that.getBlockY() &&
                   realLocation.getBlockZ() == that.getBlockZ();
        }
    
        @Override
        public int hashCode(){
            return Objects.hash(realLocation.getBlockX(), realLocation.getBlockY(), realLocation.getBlockZ());
        }
    
    }

}
