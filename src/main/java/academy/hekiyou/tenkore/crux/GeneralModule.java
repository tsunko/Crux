package academy.hekiyou.tenkore.crux;

import academy.hekiyou.door.annotations.Module;
import academy.hekiyou.door.annotations.RegisterCommand;
import academy.hekiyou.door.annotations.optional.OptionalBoolean;
import academy.hekiyou.door.annotations.optional.OptionalInteger;
import academy.hekiyou.door.annotations.optional.OptionalObject;
import academy.hekiyou.door.annotations.optional.OptionalString;
import academy.hekiyou.door.interp.Interpreters;
import academy.hekiyou.door.model.Invoker;
import com.grack.nanojson.*;
import org.bukkit.*;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

@Module
public class GeneralModule {

    private static final Map<String, Integer> TIME_REF_TO_TICKS = buildTimeMap();
    
    private static ExecutorService THREAD_SERVICE = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private static final String MC_PROFILE_URL = "https://api.mojang.com/profiles/minecraft";
    private static final String NAMES_URL_FMT = "https://api.mojang.com/user/profiles/%s/names";
    private static final int TIMEOUT = 3000;
    private static final Pattern UUID_FIX = Pattern.compile("(\\\\w{8})(\\\\w{4})(\\\\w{4})(\\\\w{4})(\\\\w{12})");

    @RegisterCommand(
            permission = "crux.general.give",
            description = "Gives an item to a player with the designated amount.",
            alias = "i",
            override = true
    )
    public void item(Invoker invoker,
                     @OptionalObject("self") HumanEntity target,
                     Material material,
                     @OptionalInteger(1) int amount,
                     @OptionalString("") String json,
                     @OptionalBoolean(false) boolean drop){
        if(target == null)
            target = invoker.as(Player.class);
    
        ItemStack stack = new ItemStack(material, amount);

        // test if no json was provided but we want to drop
        if(json.equals("true")){
            drop = true;
        } else if(!json.isEmpty()){
            try {
                // we would use Bukkit's modifyItemStack function to do this,
                // BUT: its implementation specifies a try-catch that just prints out to console
                // thank you bukkit, very cool.
                // Bukkit.getUnsafe().modifyItemStack(stack, json);
                modifyItemStack(stack, json);
            } catch (IllegalArgumentException exc){
                invoker.sendMessage(ChatColor.RED + "Bad item JSON.");
                return;
            }
        }

        if(drop){
            target.getWorld().dropItem(target.getLocation(), stack);
        } else {
            target.getInventory().addItem(stack);
        }

        invoker.sendMessage(
                ChatColor.GREEN + "Gave %d x %s to %s",
                amount,
                material.toString(),
                target == invoker.raw() ? "yourself" : target.getName()
        );
    }

    @RegisterCommand(
            permission = "crux.general.gamemode",
            description = "Swaps a player's gamemode to the new one",
            usage = {"[player=self]", "<survival|creative|adventure|spectator|0|1|2|3>"},
            alias = {"gm"},
            override = true
    )
    public void gamemode(Invoker invoker,
                         @OptionalObject("self") HumanEntity target,
                         GameMode gamemode){
        if(target == null)
            target = invoker.as(Player.class);
        
        if(gamemode == GameMode.SPECTATOR && !invoker.hasPermission("crux.general.gamemode.spectator")){
            invoker.sendMessage(ChatColor.RED + "You cannot enter spectator mode.");
            return;
        }
        
        target.setGameMode(gamemode);
        
        Bukkit.broadcast(
                String.format(
                        ChatColor.YELLOW + "%s has changed their gamemode to %s",
                        target.getName(),
                        gamemode
                ),
                "crux.general.gamemode"
        );
    }

    @RegisterCommand(
            permission = "crux.general.weather",
            description = "Changes the weather in the target world (default is the current world)",
            usage = {"<storm|thunderstorm|clear>", "[length=1200 (ticks; or 1 minute @ 20TPS)]", "[world=current world]"},
            override = true
    )
    public void weather(Invoker invoker,
                        String type,
                        @OptionalInteger(1200) int length,
                        @OptionalObject("current world") World world){
        if(world == null)
            world = getWorldFor(invoker);
        type = type.toLowerCase();
        
        if(type.contains("storm")){
            world.setStorm(true);
            world.setWeatherDuration(length);
            if(type.startsWith("thunder")){
                world.setThundering(true);
                world.setThunderDuration(length);
            }
            invoker.sendMessage(ChatColor.YELLOW + "Started a %s in %s", type, world.getName());
        } else if(type.equals("clear")){
            world.setStorm(false);
            world.setThundering(false);
            world.setWeatherDuration(0);
            world.setThunderDuration(0);
            invoker.sendMessage(ChatColor.YELLOW + "Cleared weather conditions for " + world.getName());
        } else {
            invoker.sendMessage(ChatColor.RED + "No such weather condition \"%s\"", type);
        }
    }

    @RegisterCommand(
            permission = "crux.general.time",
            description = "Changes the time in the target world (defaulting to the current one you're in)",
            usage = {"<day|noon|sunset|night|midnight|sunrise|time, in ticks>", "[world=current world]"},
            override = true
    )
    public void time(Invoker invoker,
                     String time,
                     @OptionalObject("current world") World world){
        if(world == null)
            world = getWorldFor(invoker);
        Integer ticks = TIME_REF_TO_TICKS.get(time);

        // if this throws a nurupo, we have a broken Interpreters impl...
        if(ticks == null){
            if(time.chars().allMatch(Character::isDigit)){
                ticks = Objects.requireNonNull(Interpreters.of(int.class)).apply(time);
            } else {
                invoker.sendMessage(ChatColor.RED + "Couldn't determine a tick for \"" + time + "\"");
                return;
            }
        }
        world.setTime(ticks);

        invoker.sendMessage(
                ChatColor.YELLOW + "Changed time in %s to %d (full time is %d)",
                world.getName(),
                ticks,
                world.getFullTime()
        );
    }

    @RegisterCommand(
            permission = "crux.general.playertime",
            description = "Changes the time for only the designated player; note this also locks their local time",
            usage = {"[player=self]", "<day|noon|sunset|night|midnight|sunrise|time in ticks|reset>"}
    )
    public void playertime(Invoker invoker,
                           @OptionalObject("self") Player target,
                           String time){
        if(target == null)
            target = invoker.as(Player.class);
        
        if(time.toLowerCase(Locale.ENGLISH).equals("reset")){
            target.resetPlayerTime();
            invoker.sendMessage(ChatColor.YELLOW + "Reset %s's time.", target.getName());
        } else {
            Integer ticks = TIME_REF_TO_TICKS.get(time);
            // same as above, only broken Interpreters impl would throw nurupo
            if(ticks == null)
                ticks = Objects.requireNonNull(Interpreters.of(int.class)).apply(time);

            target.setPlayerTime(ticks, false);
            invoker.sendMessage(ChatColor.YELLOW + "Changed %s's time to %d.", target.getName(), ticks);
        }
    }
    
    @RegisterCommand(
            permission = "crux.general.namecheck",
            description = "Looks up a UUID or player's username history",
            usage = "<uuid or username>"
    )
    public void namecheck(Invoker invoker,
                          String playerNameOrUuid){
        NamecheckTask task = new NamecheckTask(invoker, playerNameOrUuid);
        THREAD_SERVICE.execute(task);
        invoker.sendMessage(ChatColor.YELLOW + "Submitted request for username history...");
    }

    private World getWorldFor(Invoker invoker){
        CommandSender sender = invoker.as(CommandSender.class);
        World world;
        if(sender instanceof BlockCommandSender){
            world = ((BlockCommandSender)sender).getBlock().getWorld();
        } else {
            Player player = invoker.as(Player.class);
            world = player.getWorld();
        }
        return world;
    }

    private static Map<String, Integer> buildTimeMap(){
        HashMap<String, Integer> timeMap = new HashMap<>();
        timeMap.put("day", 1000);
        timeMap.put("noon", 6000);
        timeMap.put("sunset", 12000);
        timeMap.put("night", 13000);
        timeMap.put("midnight", 18000);
        timeMap.put("sunrise", 23000);
        return Collections.unmodifiableMap(timeMap);
    }
    
    private static class NamecheckTask implements Runnable {
    
        private final String playerNameOrUuid;
        private final WeakReference<Invoker> invoker;
    
        NamecheckTask(Invoker invoker, String playerNameOrUuid){
            this.invoker = new WeakReference<>(invoker);
            this.playerNameOrUuid = playerNameOrUuid;
        }
    
        @Override
        public void run(){
            UUID uuid;
        
            if(!playerNameOrUuid.contains("-")){
                uuid = getUUIDFromName(playerNameOrUuid);
            } else {
                uuid = UUID.fromString(playerNameOrUuid);
            }
        
            if(uuid == null){
                notifyUser(ChatColor.RED + "Unable to get UUID from input; is the UUID/username correct?");
                return;
            }
        
            List<String> usernames = getAllUsernames(uuid);
            if(usernames == null)
                return;
        
            notifyUser(ChatColor.GREEN + "Username history for " + playerNameOrUuid + ":");
            for(String str : usernames){
                notifyUser(ChatColor.GREEN + "- " + str);
            }
        }
    
        private void notifyUser(String str){
            Invoker real = invoker.get();
            if(real == null)
                return;
            real.sendMessage(str);
        }
        
    }
    
    
    @Nullable
    private static UUID getUUIDFromName(String name){
        HttpsURLConnection conn = null;
        InputStream is = null;
        OutputStream os = null;
        
        try {
            conn = (HttpsURLConnection) new URL(MC_PROFILE_URL).openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            
            os = conn.getOutputStream();
            os.write(JsonWriter.string()
                        .array()
                            .value(name)
                        .end()
                    .done().getBytes(StandardCharsets.UTF_8));
            os.flush();
            
            is = conn.getInputStream();
            
            JsonArray arr = JsonParser.array().withLazyNumbers().from(is);
            if(arr.size() <= 0)
                return null;
            String unformattedUUID = ((JsonObject)arr.get(0)).getString("id");
            return UUID.fromString(UUID_FIX.matcher(unformattedUUID).replaceAll("$1-$2-$3-$4-$5"));
        } catch (MalformedURLException exc){
            throw new IllegalStateException("shouldn't reach here", exc);
        }  catch (IOException | JsonParserException exc) {
            exc.printStackTrace();
            return null;
        } finally {
            if(conn != null)
                conn.disconnect();
            if(is != null){
                try {
                    is.close();
                } catch(IOException ignored) {}
            }
            if(os != null){
                try {
                    os.close();
                } catch(IOException ignored) {}
            }
        }
    }
    
    @Nullable
    private static List<String> getAllUsernames(UUID uuid){
        HttpsURLConnection conn = null;
        InputStream is = null;
        List<String> usernames = new ArrayList<>();
        
        try {
            conn = (HttpsURLConnection) new URL(String.format(NAMES_URL_FMT, uuid.toString().replace("-", ""))).openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setDoInput(true);
            conn.setRequestMethod("GET");
            is = conn.getInputStream();
            
            // use withLazyNumbers to prevent parsing the changedToAt field
            JsonArray names = JsonParser.array().withLazyNumbers().from(is);
    
            for(Object name : names){
                JsonObject entry = (JsonObject) name;
                usernames.add(entry.get("name").toString());
            }
        } catch (MalformedURLException exc){
            throw new IllegalStateException("shouldn't reach here", exc);
        }  catch (IOException | JsonParserException exc) {
            exc.printStackTrace();
            return null;
        } finally {
            if(conn != null)
                conn.disconnect();
            if(is != null){
                try {
                    is.close();
                } catch(IOException ignored) {}
            }
        }
        
        return usernames;
    }
    
    // ugly reflection hack below
    private static String nmsVersion;
    private static Method CB_asNMSCopy;
    private static Method CB_getItemMeta;
    private static Method NMS_parse;
    private static Method NMS_setTag;
    
    @NotNull
    private static ItemStack modifyItemStack(ItemStack stack, String json){
        try {
            if(nmsVersion == null){
                nmsVersion = Bukkit.getServer().getClass().getPackage().getName();
                nmsVersion = nmsVersion.substring(nmsVersion.lastIndexOf('.') + 1);
            }
            if(CB_asNMSCopy == null){
                CB_asNMSCopy = Class.forName("org.bukkit.craftbukkit." + nmsVersion + ".inventory.CraftItemStack")
                        .getDeclaredMethod("asNMSCopy", ItemStack.class);
                CB_asNMSCopy.setAccessible(true);
            }
            if(CB_getItemMeta == null){
                CB_getItemMeta = Class.forName("org.bukkit.craftbukkit." + nmsVersion + ".inventory.CraftItemStack")
                        .getDeclaredMethod("getItemMeta",
                                Class.forName("net.minecraft.server." + nmsVersion + ".ItemStack"));
                CB_getItemMeta.setAccessible(true);
            }
            if(NMS_parse == null){
                NMS_parse = Class.forName("net.minecraft.server." + nmsVersion + ".MojangsonParser")
                        .getDeclaredMethod("parse", String.class);
                NMS_parse.setAccessible(true);
            }
            if(NMS_setTag == null){
                NMS_setTag = Class.forName("net.minecraft.server." + nmsVersion + ".ItemStack")
                        .getDeclaredMethod("setTag",
                                Class.forName("net.minecraft.server." + nmsVersion + ".NBTTagCompound"));
                NMS_setTag.setAccessible(true);
            }
            
            Object nmsStack = CB_asNMSCopy.invoke(null, stack);
            NMS_setTag.invoke(nmsStack, NMS_parse.invoke(null,json));
            stack.setItemMeta((ItemMeta)CB_getItemMeta.invoke(null,nmsStack));
            return stack;
        } catch (ReflectiveOperationException exc){
            throw new RuntimeException(exc);
        } catch (Exception exc){
            if(exc.getClass().getName().contains("MojangsonParseException")){
                throw new IllegalArgumentException(exc);
            } else {
                throw new IllegalStateException(exc);
            }
        }
    }

}
