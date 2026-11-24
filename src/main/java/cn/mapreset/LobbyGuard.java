package cn.mapreset;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.block.*;
import org.bukkit.event.player.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;
import java.io.*;
import java.util.*;

/** Never resolves an old login location by teleporting into it first. */
final class LobbyGuard implements Listener {
    final MapResetPlugin p;final File file;final YamlConfiguration saved;
    LobbyGuard(MapResetPlugin p){this.p=p;file=new File(p.getDataFolder(),"lobby-player-state.yml");saved=YamlConfiguration.loadConfiguration(file);}
    boolean isLobby(World w){return w!=null&&w.getName().equals(p.lobbyName);}
    void save(){try{saved.save(file);}catch(IOException e){p.log(e);}}
    void protect(Player player){
        String id=player.getUniqueId().toString();
        if(!saved.contains(id)){
            saved.set(id+".flight",player.getAllowFlight());saved.set(id+".flying",player.isFlying());saved.set(id+".gravity",player.hasGravity());saved.set(id+".invulnerable",player.isInvulnerable());save();
        }
        player.setAllowFlight(true);player.setFlying(true);player.setGravity(false);player.setInvulnerable(true);player.setFallDistance(0);
    }
    void restore(Player player){
        String id=player.getUniqueId().toString();if(!saved.contains(id))return;
        boolean canFly=saved.getBoolean(id+".flight");
        if(player.getGameMode()==GameMode.CREATIVE||player.getGameMode()==GameMode.SPECTATOR)canFly=true;
        player.setFlying(false);player.setAllowFlight(canFly);if(canFly&&saved.getBoolean(id+".flying"))player.setFlying(true);
        player.setGravity(saved.getBoolean(id+".gravity",true));player.setInvulnerable(saved.getBoolean(id+".invulnerable"));player.setFallDistance(0);saved.set(id,null);save();
    }
    boolean evacuate(){
        Location target=p.lobbySpawn();boolean all=true;
        for(Player player:new ArrayList<>(Bukkit.getOnlinePlayers())){
            protect(player);
            if(!player.teleport(target,PlayerTeleportEvent.TeleportCause.PLUGIN)||!isLobby(player.getWorld()))all=false;
        }
        return all;
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void login(PlayerLoginEvent e){if(p.lobby==null)e.disallow(PlayerLoginEvent.Result.KICK_OTHER,"大厅初始化中，请稍后重试");}
    @EventHandler(priority=EventPriority.HIGHEST) public void initialSpawn(PlayerSpawnLocationEvent e){if(p.lobby!=null)e.setSpawnLocation(p.lobbySpawn());}
    @EventHandler(priority=EventPriority.HIGHEST) public void join(PlayerJoinEvent e){Player player=e.getPlayer();protect(player);if(!isLobby(player.getWorld()))player.teleport(p.lobbySpawn());}
    @EventHandler(priority=EventPriority.HIGHEST) public void respawn(PlayerRespawnEvent e){if(p.blocked(e.getRespawnLocation().getWorld())||p.locked)e.setRespawnLocation(p.lobbySpawn());}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void teleport(PlayerTeleportEvent e){
        Location target=e.getTo();if(target==null||target.getWorld()==null)return;
        if(p.blocked(target.getWorld())){e.setCancelled(true);e.getPlayer().sendMessage("地图更新中，请留在大厅。");}
        else if(isLobby(target.getWorld())){e.setCancelled(false);protect(e.getPlayer());}
    }
    @EventHandler(priority=EventPriority.MONITOR) public void changed(PlayerChangedWorldEvent e){if(isLobby(e.getPlayer().getWorld()))protect(e.getPlayer());else restore(e.getPlayer());}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void damage(EntityDamageEvent e){if(e.getEntity() instanceof Player&&isLobby(e.getEntity().getWorld()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void place(BlockPlaceEvent e){if(isLobby(e.getBlock().getWorld()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void breakBlock(BlockBreakEvent e){if(isLobby(e.getBlock().getWorld()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void bucket(PlayerBucketEmptyEvent e){if(isLobby(e.getPlayer().getWorld()))e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST) public void move(PlayerMoveEvent e){
        if(e.getTo()==null)return;
        if(isLobby(e.getPlayer().getWorld())&&(e.getTo().getY()<60||e.getTo().getY()>180||Math.abs(e.getTo().getX())>64||Math.abs(e.getTo().getZ())>64))e.setTo(p.lobbySpawn());
    }
    void tick(){
        for(Player player:Bukkit.getOnlinePlayers()){
            if(p.blocked(player.getWorld())){protect(player);if(!player.teleport(p.lobbySpawn()))p.isolationFailure("玩家被外部插件阻止撤离: "+player.getName());}
            else if(isLobby(player.getWorld())){protect(player);if(player.getLocation().getY()<60)player.teleport(p.lobbySpawn());}
        }
    }
}
