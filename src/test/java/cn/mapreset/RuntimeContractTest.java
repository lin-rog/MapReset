package cn.mapreset;

import org.junit.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.Assert.*;

/** API-level event and chunk tests with an in-memory Server; NOT a real Paper integration test. */
public class RuntimeContractTest {
    static final Map<String,World> worlds=new HashMap<>();static final List<Player> online=new ArrayList<>();
    static Material[] lastChunk;static int chunkLookups;
    Path root;MapResetPlugin plugin;World hall,old;LobbyGuard guard;
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,InvocationHandler handler){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},(p,m,a)->{
        if(m.getName().equals("equals"))return p==a[0];if(m.getName().equals("hashCode"))return System.identityHashCode(p);if(m.getName().equals("toString"))return type.getSimpleName()+"TestDouble";
        return handler.invoke(p,m,a);
    });}
    static Object zero(Class<?> c){if(c==boolean.class)return false;if(c==int.class)return 0;if(c==long.class)return 0L;if(c==double.class)return 0d;if(c==float.class)return 0f;if(c==short.class)return (short)0;if(c==byte.class)return (byte)0;return null;}
    @BeforeClass public static void server(){
        if(Bukkit.getServer()!=null)return;
        Bukkit.setServer(proxy(Server.class,(p,m,a)->{
            switch(m.getName()){
                case "getLogger":return Logger.getLogger("MapResetTests");case "getName":return "MemoryServer";case "getVersion":return "API test double";case "getBukkitVersion":return "1.16.5";
                case "getWorld":return worlds.get(a[0]);case "getWorlds":return new ArrayList<>(worlds.values());case "getOnlinePlayers":return online;
                case "createChunkData":
                    Material[] chunk=new Material[16*256*16];Arrays.fill(chunk,Material.AIR);lastChunk=chunk;
                    return proxy(ChunkGenerator.ChunkData.class,(o,method,args)->{
                        if(method.getName().equals("setBlock")){chunk[((int)args[0]*16+(int)args[2])*256+(int)args[1]]=(Material)args[3];return null;}
                        if(method.getName().equals("getType"))return chunk[((int)args[0]*16+(int)args[2])*256+(int)args[1]];
                        if(method.getName().equals("getMaxHeight"))return 256;return zero(method.getReturnType());
                    });
                default:return zero(m.getReturnType());
            }
        }));
    }
    @Before public void setup()throws Exception{
        worlds.clear();online.clear();chunkLookups=0;root=Files.createTempDirectory("mapreset-runtime");
        Class<?> unsafeClass=Class.forName("sun.misc.Unsafe");Field field=unsafeClass.getDeclaredField("theUnsafe");field.setAccessible(true);Object unsafe=field.get(null);
        plugin=(MapResetPlugin)unsafeClass.getMethod("allocateInstance",Class.class).invoke(unsafe,MapResetPlugin.class);
        Field folder=JavaPlugin.class.getDeclaredField("dataFolder");folder.setAccessible(true);Path data=root.resolve("plugins/MapReset");Files.createDirectories(data);folder.set(plugin,data.toFile());
        plugin.root=root;plugin.data=data;plugin.names=new String[]{"world","world_nether","world_the_end"};plugin.lobbyName="mapreset_lobby";
        hall=world(plugin.lobbyName,World.Environment.NORMAL,1);old=world("world",World.Environment.NORMAL,2);plugin.lobby=hall;plugin.locked=true;
        guard=new LobbyGuard(plugin);
    }
    @After public void cleanup()throws Exception{FilesEx.delete(root.getParent(),root);}
    World world(String name,World.Environment environment,long seed){
        UUID id=UUID.randomUUID();final World[] holder=new World[1];World result=proxy(World.class,(p,m,a)->{
            switch(m.getName()){
                case "getName":return name;case "getUID":return id;case "getEnvironment":return environment;case "getSeed":return seed;case "getMaxHeight":return 256;
                case "getWorldFolder":return root.resolve(name).toFile();case "getPlayers":return Collections.emptyList();case "getChunkAt":chunkLookups++;return null;
                case "getWorldBorder":return proxy(WorldBorder.class,(q,n,b)->n.getName().equals("getSize")?700d:n.getName().equals("getCenter")?new Location(holder[0],0,0,0):zero(n.getReturnType()));
                default:return zero(m.getReturnType());
            }
        });holder[0]=result;worlds.put(name,result);return result;
    }
    static final class PlayerState {boolean flight=false,flying=false,gravity=true,invulnerable=false;World world;final UUID id=UUID.randomUUID();}
    Player player(PlayerState state){return proxy(Player.class,(p,m,a)->{
        switch(m.getName()){
            case "getUniqueId":return state.id;case "getWorld":return state.world;case "getGameMode":return GameMode.SURVIVAL;
            case "getAllowFlight":return state.flight;case "isFlying":return state.flying;case "hasGravity":return state.gravity;case "isInvulnerable":return state.invulnerable;
            case "setAllowFlight":state.flight=(boolean)a[0];return null;case "setFlying":state.flying=(boolean)a[0];return null;case "setGravity":state.gravity=(boolean)a[0];return null;case "setInvulnerable":state.invulnerable=(boolean)a[0];return null;
            default:return zero(m.getReturnType());
        }
    });}
    @Test public void oldWorldLoginIsRedirectedBeforeChunkLookup(){
        PlayerState state=new PlayerState();state.world=old;Player player=player(state);
        PlayerSpawnLocationEvent event=new PlayerSpawnLocationEvent(player,new Location(old,250,70,250));guard.initialSpawn(event);
        assertSame(hall,event.getSpawnLocation().getWorld());assertEquals(100,event.getSpawnLocation().getY(),0);assertEquals(0,chunkLookups);
    }
    @Test public void everyTeleportCauseIsBlockedDuringReset(){
        PlayerState state=new PlayerState();state.world=hall;Player player=player(state);
        for(PlayerTeleportEvent.TeleportCause cause:PlayerTeleportEvent.TeleportCause.values()){
            PlayerTeleportEvent event=new PlayerTeleportEvent(player,new Location(hall,0,100,0),new Location(old,0,70,0),cause);guard.teleport(event);assertTrue("cause "+cause,event.isCancelled());
        }
        plugin.locked=false;PlayerTeleportEvent event=new PlayerTeleportEvent(player,new Location(hall,0,100,0),new Location(old,0,70,0));guard.teleport(event);assertFalse(event.isCancelled());
    }
    @Test public void respawnDuringResetGoesToHall(){
        PlayerState state=new PlayerState();state.world=old;PlayerRespawnEvent event=new PlayerRespawnEvent(player(state),new Location(old,0,70,0),true);guard.respawn(event);assertSame(hall,event.getRespawnLocation().getWorld());
    }
    @Test public void lobbyProtectionSurvivesReconnectAndRestoresOriginalState(){
        PlayerState state=new PlayerState();state.world=hall;Player player=player(state);guard.protect(player);guard.protect(player);
        assertTrue(state.flight);assertFalse(state.gravity);assertTrue(state.invulnerable);
        new LobbyGuard(plugin).restore(player);assertFalse(state.flight);assertFalse(state.flying);assertTrue(state.gravity);assertFalse(state.invulnerable);
    }
    @Test public void voidDamageIsBlockedInHall(){PlayerState state=new PlayerState();state.world=hall;EntityDamageEvent event=new EntityDamageEvent(player(state),EntityDamageEvent.DamageCause.VOID,100);guard.damage(event);assertTrue(event.isCancelled());}
    @Test public void voidGeneratorProducesZeroBlocks(){
        MapGenerator generator=new MapGenerator(true);ChunkGenerator.BiomeGrid grid=proxy(ChunkGenerator.BiomeGrid.class,(p,m,a)->zero(m.getReturnType()));
        for(int[] coord:new int[][]{{0,0},{-20,15},{100,-400}}){generator.generateChunkData(hall,new Random(1),coord[0],coord[1],grid);for(Material material:lastChunk)assertEquals(Material.AIR,material);}
        assertFalse(generator.shouldGenerateCaves());assertFalse(generator.shouldGenerateDecorations());assertFalse(generator.shouldGenerateStructures());
    }
    @Test public void vanillaPostProcessingOnlySelectsFourOresAndIgnoresTreeCanopy(){
        org.bukkit.ChunkSnapshot snapshot=proxy(org.bukkit.ChunkSnapshot.class,(p,m,a)->{
            if(m.getName().equals("getHighestBlockYAt"))return 256;
            if(m.getName().equals("getBlockType")){int y=(Integer)a[1];if(y>75)return Material.AIR;if(y>70)return Material.OAK_LEAVES;if(y==70)return Material.GRASS_BLOCK;
                Material[] ores={Material.COAL_ORE,Material.REDSTONE_ORE,Material.LAPIS_ORE,Material.IRON_ORE,Material.GOLD_ORE,Material.DIAMOND_ORE,Material.EMERALD_ORE};return y<7?ores[y]:Material.STONE;}
            return zero(m.getReturnType());
        });
        VanillaScan scan=VanillaScan.scan(snapshot,0,0);assertEquals(256*4,scan.ores.size());assertEquals(256,scan.surface.size());for(int[] c:scan.surface)assertEquals(70,c[2]);for(int[] c:scan.ores)assertTrue(c[1]>=3&&c[1]<=6);
    }
    void prepareVerificationFiles()throws Exception{
        plugin.structureRevision="revision";plugin.expectedReset="reset-123";plugin.oldWorldIDs=new HashSet<>();
        plugin.netherTemplate=root.resolve("templates/nether");plugin.endTemplate=root.resolve("templates/end");
        for(Path folder:new Path[]{plugin.netherTemplate,plugin.endTemplate}){Files.createDirectories(folder);Files.write(folder.resolve("level.dat"),new byte[]{1});Files.write(folder.resolve("READY"),new byte[]{1});}
        for(int i=0;i<3;i++){
            String name=plugin.names[i];world(name,i==0?World.Environment.NORMAL:i==1?World.Environment.NETHER:World.Environment.THE_END,100+i);
            Path folder=root.resolve(name);Files.createDirectories(folder);YamlConfiguration id=new YamlConfiguration();id.set("reset-id",plugin.expectedReset);id.set("dimension",i);id.save(folder.resolve(WorldTransaction.ID_FILE).toFile());
        }
        YamlConfiguration layout=new YamlConfiguration();layout.set("format",2);layout.set("seed",100L);layout.set("structure-revision","revision");
        for(char id='a';id<='j';id++){String k="buildings."+id;layout.set(k+".min-x",0);layout.set(k+".min-z",0);layout.set(k+".width",13);layout.set(k+".depth",13);}
        layout.save(root.resolve("world/mapreset-coordinates.yml").toFile());plugin.transaction=new WorldTransaction(root,plugin.data,plugin.names);
    }
    @Test public void selfCheckAcceptsOneConsistentUpdate()throws Exception{prepareVerificationFiles();plugin.verifyActive(false);}
    @Test(expected=IOException.class)public void selfCheckRejectsMixedOldDimension()throws Exception{
        prepareVerificationFiles();YamlConfiguration id=YamlConfiguration.loadConfiguration(root.resolve("world_the_end/"+WorldTransaction.ID_FILE).toFile());id.set("reset-id","old-map");id.save(root.resolve("world_the_end/"+WorldTransaction.ID_FILE).toFile());plugin.verifyActive(false);
    }
    @Test(expected=IOException.class)public void selfCheckRejectsMissingDimension()throws Exception{prepareVerificationFiles();worlds.remove("world_nether");plugin.verifyActive(false);}
}
