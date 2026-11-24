package cn.mapreset;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static cn.mapreset.TerrainPlan.*;

public final class MapResetPlugin extends JavaPlugin implements Listener {
    Path root,data,pool,netherTemplate,endTemplate;String[] names;String lobbyName,primaryName;World lobby,generating;
    WorldTransaction transaction;LobbyGuard guard;StructureProvider provider;String structureRevision="unavailable";
    Material roadA,roadB,foundation,stairC,stairD;
    volatile boolean stopping=false;boolean booted=false,busy=false,locked=true,internalLoad=false,resetQueued=false,recoveryFailure=false;
    String status="启动中";ReserveGenerator generationTask;Set<UUID> oldWorldIDs=new HashSet<>();String expectedReset;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"MapReset-worker");t.setDaemon(true);return t;});
    @Override public void onLoad(){
        try{
            saveDefaultConfig();getConfig().addDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(Objects.requireNonNull(getResource("config.yml")),StandardCharsets.UTF_8)));
            root=getServer().getWorldContainer().toPath().toAbsolutePath().normalize();data=getDataFolder().toPath().toAbsolutePath().normalize();FilesEx.safe(root,data);
            pool=data.resolve("reserves/overworld");netherTemplate=data.resolve("templates/nether");endTemplate=data.resolve("templates/end");
            Files.createDirectories(pool);Files.createDirectories(netherTemplate);Files.createDirectories(endTemplate);
            Properties properties=new Properties();try(InputStream in=Files.newInputStream(Paths.get("server.properties"))){properties.load(in);}
            primaryName=properties.getProperty("level-name","world");lobbyName=getConfig().getString("lobby.name","mapreset_lobby");validateName(lobbyName);
            String[] defaults=primaryName.equals(lobbyName)?new String[]{"world","world_nether","world_the_end"}:new String[]{primaryName,primaryName+"_nether",primaryName+"_the_end"};
            String[] keys={"overworld","nether","end"};names=new String[3];
            for(int i=0;i<3;i++){String configured=getConfig().getString("active."+keys[i],"auto");names[i]="auto".equalsIgnoreCase(configured)?defaults[i]:configured;validateName(names[i]);if(names[i].equals(lobbyName))throw new IOException("大厅和游戏世界不得同名");}
            if(new HashSet<>(Arrays.asList(names)).size()!=3)throw new IOException("三界名称必须不同");
            transaction=new WorldTransaction(root,data,names);
            if(!Bukkit.getWorlds().isEmpty())throw new IOException("不支持/reload和热加载");
            if(transaction.pending()&&!primaryName.equals(lobbyName))throw new IOException("待恢复事务要求大厅为默认世界，先还原初始化配置");
            transaction.recoverAtStartup();
        }catch(Exception e){recoveryFailure=true;status="启动保护: "+e.getMessage();log(e);}
    }
    void validateName(String name)throws IOException{
        if(name==null||!name.matches("[A-Za-z0-9_-]+")||Arrays.asList("plugins","logs","libraries","cache","versions","backups").contains(name.toLowerCase(Locale.ROOT)))throw new IOException("无效世界目录名: "+name);
        FilesEx.safe(root,root.resolve(name));
    }
    @Override public ChunkGenerator getDefaultWorldGenerator(String worldName,String id){return new MapGenerator(true);}
    @Override public void onEnable(){
        if(transaction==null){getLogger().severe(status);Bukkit.getPluginManager().disablePlugin(this);return;}
        guard=new LobbyGuard(this);Bukkit.getPluginManager().registerEvents(guard,this);Bukkit.getPluginManager().registerEvents(this,this);
        try{
            roadA=solid("blocks.road-a");roadB=solid("blocks.road-b");foundation=Material.STONE_BRICKS;stairC=solid("blocks.road-stair-c");stairD=solid("blocks.road-stair-d");
            double ratio=getConfig().getDouble("blocks.road-b-ratio",.35);if(ratio<0||ratio>1)throw new IOException("road-b-ratio必须在0..1之间");
            if(!(stairC.createBlockData() instanceof org.bukkit.block.data.type.Stairs)||!(stairD.createBlockData() instanceof org.bukkit.block.data.type.Stairs))throw new IOException("road-stair-c/d 必须是楼梯材料");
            reloadStructures();
        }catch(Exception e){status="配置错误: "+e.getMessage();log(e);}
    }
    Material solid(String key){Material m=Material.matchMaterial(getConfig().getString(key,""));if(m==null||!m.isBlock()||!m.isSolid()||ReserveGenerator.forbiddenOre(m))throw new IllegalArgumentException(key+" 应是合法的实心方块，不能是禁止的矿石");return m;}
    public StructureProvider getStructureProvider(){return provider;}
    public boolean isResetInProgress(){return locked;}
    public boolean canEnter(World world){return !blocked(world);}
    public void setStructureProvider(StructureProvider value){
        if(!Bukkit.isPrimaryThread()||busy||locked&&booted)throw new IllegalStateException("只能在主线程且无任务时注册结构接口");
        provider=Objects.requireNonNull(value);refreshRevision();
    }
    void reloadStructures()throws IOException{provider=new YamlStructures(this);refreshRevision();}
    void refreshRevision(){
        StringBuilder signature=new StringBuilder("generation-2.3-road-sections|").append(provider.revision());
        for(char id='a';id<='j';id++){StructureSpec spec=provider.spec(""+id);signature.append('|').append(id).append(':').append(spec.width).append(',').append(spec.depth).append(',').append(spec.height).append(',').append(spec.firstFloor).append(',').append(spec.foundationLayers);
            for(StructureSpec.Entry e:spec.entrances.values())signature.append(e.side).append(e.x).append(',').append(e.y).append(',').append(e.z);}
        try{if(provider instanceof YamlStructures)signature.append(WorldTransaction.fingerprint(data.resolve("structures")));}
        catch(IOException e){throw new IllegalStateException(e);}
        structureRevision=UUID.nameUUIDFromBytes(signature.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }
    @EventHandler public void loaded(ServerLoadEvent e){
        if(booted)return;booted=true;
        Bukkit.getScheduler().runTask(this,()->{
            try{
                internalLoad=true;
                lobby=Bukkit.getWorld(lobbyName);if(lobby==null)lobby=Bukkit.createWorld(new WorldCreator(lobbyName).generator(new MapGenerator(true)).generateStructures(false));
                if(lobby==null)throw new IOException("大厅创建失败");lobby.setKeepSpawnInMemory(false);lobby.setSpawnLocation(0,100,0);lobby.setGameRule(GameRule.SPAWN_RADIUS,0);lobby.setGameRule(GameRule.DO_MOB_SPAWNING,false);lobby.setGameRule(GameRule.DO_WEATHER_CYCLE,false);lobby.setStorm(false);lobby.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false);lobby.setTime(6000);lobby.setDifficulty(Difficulty.PEACEFUL);
                guard.evacuate();
                if(recoveryFailure){status="恢复失败，玩家保持大厅隔离；请检查日志";return;}
                loadExisting();
                if(transaction.pending()){
                    expectedReset=transaction.journal().getString("id");finishLoadedReset();
                }else{locked=false;status=readyMessage();reconcilePoolThenPump();}
            }catch(Exception ex){isolationFailure("启动自查失败: "+ex.getMessage());log(ex);}
            finally{internalLoad=false;}
            Bukkit.getScheduler().runTaskTimer(this,()->guard.tick(),1,1);
            Bukkit.getScheduler().runTaskTimer(this,()->pump(false),1200,1200);
        });
    }
    String readyMessage(){if(provider==null)return "结构/方块配置未就绪，请修复日志中的错误后reloadstructures";return primaryReady()?"就绪":"需要一次性 mapreset setup 并手动重启，随后可在线重置";}
    boolean primaryReady(){return lobby!=null&&Bukkit.getWorlds().size()>0&&Bukkit.getWorlds().get(0)==lobby&&primaryName.equals(lobbyName);}
    World.Environment environment(int index){return index==0?World.Environment.NORMAL:index==1?World.Environment.NETHER:World.Environment.THE_END;}
    void loadExisting(){
        internalLoad=true;try{for(int i=0;i<3;i++)if(Bukkit.getWorld(names[i])==null&&Files.isRegularFile(root.resolve(names[i]).resolve("level.dat")))loadWorld(i);}finally{internalLoad=false;}
    }
    World loadWorld(int index){
        internalLoad=true;
        try{
            WorldCreator creator=new WorldCreator(names[index]).environment(environment(index));
            // New game maps retain the saved vanilla generator.
            World w=Bukkit.createWorld(creator);if(w==null)throw new IllegalStateException("世界载入失败: "+names[index]);
            if(index==0){w.getWorldBorder().setCenter(0,0);w.getWorldBorder().setSize(700);w.setGameRule(GameRule.SPAWN_RADIUS,0);}return w;
        }finally{internalLoad=false;}
    }
    Location lobbySpawn(){return new Location(lobby,.5,100,.5);}
    boolean managed(World w){return w!=null&&Arrays.asList(names).contains(w.getName());}
    boolean blocked(World w){return w!=null&&(w.getName().startsWith("mrgen_")||(locked&&managed(w)));}
    @EventHandler public void init(WorldInitEvent e){if(e.getWorld().getName().startsWith("mrgen_"))e.getWorld().setKeepSpawnInMemory(false);if(booted&&busy&&locked&&managed(e.getWorld())&&!internalLoad)isolationFailure("外部插件在重置期间加载了游戏地图，已中止后续切换");}
    void isolationFailure(String reason){locked=true;recoveryFailure=true;status="隔离保护: "+reason;getLogger().severe(status);}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        String sub=args.length==0?"status":args[0].toLowerCase(Locale.ROOT);
        if(!sender.isOp()||!sender.hasPermission("mapreset.admin")){sender.sendMessage("仅OP/最高管理员可使用此指令");return true;}
        if(sub.equals("play")){
            if(!(sender instanceof Player)){sender.sendMessage("play 供玩家使用");return true;}
            World w=Bukkit.getWorld(names[0]);if(locked||w==null){sender.sendMessage("游戏地图尚未就绪");return true;}
            ((Player)sender).teleport(w.getSpawnLocation());return true;
        }
        if(!sender.isOp()||!sender.hasPermission("mapreset.admin")){sender.sendMessage("无权限");return true;}
        try{
            if(sub.equals("status")){sender.sendMessage("MapReset 2.3: "+status+" | 隔离="+locked+" | 备用="+readyPool().size()+"/5 | 大厅默认="+primaryReady()+" | 事务="+transaction.pending());return true;}
            if(sub.equals("reloadstructures")){
                if(busy||locked)throw new IOException("任务/隔离中不能修改结构接口");reloadStructures();reconcilePoolThenPump();sender.sendMessage("已重新读取尺寸/第一层/入口，旧尺寸备用地图移入retired并重新补库");return true;
            }
            if(sub.equals("model")){if(busy||locked||args.length!=2||!args[1].matches("[a-j]"))throw new IOException("用法 mapreset model a..j；无任务时执行");reloadStructures();String modelId=args[1];new ModelReplacement(this,modelId);busy=true;locked=true;if(!guard.evacuate()){busy=false;locked=false;throw new IOException("无法撤离玩家");}World original=Bukkit.getWorld(names[0]);original.save();if(!Bukkit.unloadWorld(original,true)){busy=false;locked=false;throw new IOException("无法卸载主世界做替换前备份");}Path source=root.resolve(names[0]),backup=data.resolve("model-backups/"+System.currentTimeMillis()+"-"+modelId);status="模型替换前保存完整世界备份";
                background(()->{FilesEx.copy(root,source,backup);Path uid=source.resolve("uid.dat");if(Files.exists(uid))Files.copy(uid,backup.resolve("uid.dat"));return null;},v->{try{loadWorld(0);new ModelReplacement(this,modelId).start();}catch(Exception error){busy=false;isolationFailure(error.getMessage());log(error);}},error->{busy=false;isolationFailure(error.getMessage());log(error);});sender.sendMessage(status);return true;}
            if(sub.equals("setup")){Bootstrap.setup(this);sender.sendMessage("配置及玩家数据迁移准备完成。请手动完整重启一次；之后reset不会停服。");return true;}
            if(sub.equals("generate")){if(busy)throw new IOException("当前任务尚未结束");if(locked)throw new IOException("隔离/恢复中不能生成");pump(true);sender.sendMessage(status);return true;}
            if(sub.equals("reset")){
                if(busy||locked)throw new IOException("已有生成或重置任务，拒绝重复重置；请稍后重试");
                startReset();sender.sendMessage(status);return true;
            }
            if(sub.equals("check")){verifyActive(false);sender.sendMessage("三界更新标识、环境、UUID、种子（有记录时）、目录与主世界元数据自查通过");return true;}
            if(sub.equals("recover")||sub.equals("rollback")){recoverOnline(sub.equals("rollback"));sender.sendMessage(status);return true;}
            return false;
        }catch(Exception e){sender.sendMessage("无法执行: "+e.getMessage());return true;}
    }
    List<Path> readyPool()throws IOException{
        List<Path> result=new ArrayList<>();for(Path path:FilesEx.ready(pool)){
            YamlConfiguration info=YamlConfiguration.loadConfiguration(path.resolve("mapreset-coordinates.yml").toFile());
            if(info.getInt("format")==2&&structureRevision.equals(info.getString("structure-revision")))result.add(path);
        }return result;
    }
    void reconcilePoolThenPump(){
        if(busy||locked||provider==null)return;busy=true;status="核对备用地图结构版本";
        String revision=structureRevision;
        background(()->{
            for(Path path:FilesEx.ready(pool)){
                YamlConfiguration info=YamlConfiguration.loadConfiguration(path.resolve("mapreset-coordinates.yml").toFile());
                if(info.getInt("format")!=2||!revision.equals(info.getString("structure-revision")))FilesEx.move(root,path,data.resolve("reserves/retired/"+path.getFileName()+"-"+UUID.randomUUID()));
            }return null;
        },v->{busy=false;status=readyMessage();afterJob();},e->{busy=false;log(e);status="备用版本整理失败";});
    }
    void startReset()throws Exception{
        if(!primaryReady())throw new IOException("先执行setup并完整重启一次，让大厅成为默认世界");
        if(busy||locked||recoveryFailure)throw new IOException("已有任务或恢复隔离，先status/recover");
        List<Path> available=readyPool();if(available.isEmpty())throw new IOException("没有符合当前结构版本的已完成主世界备用图");
        if(!templateReady(netherTemplate)||!templateReady(endTemplate))throw new IOException("地狱/末地模板尚未完成");
        oldWorldIDs.clear();
        for(String name:names){World w=Bukkit.getWorld(name);if(w!=null){if(w==Bukkit.getWorlds().get(0))throw new IOException("不能卸载默认世界");if(!w.getWorldFolder().toPath().toAbsolutePath().normalize().equals(root.resolve(name)))throw new IOException("活动世界目录和配置不符");oldWorldIDs.add(w.getUID());}}
        locked=true;busy=true;status="撤离全部玩家到大厅";
        if(!guard.evacuate()){locked=false;busy=false;throw new IOException("有玩家传送被阻止，重置已取消，活动地图未改动");}
        final Path chosen=available.get(new Random().nextInt(available.size()));status="后台复制并校验三界模板";
        background(()->transaction.prepare(chosen,netherTemplate,endTemplate),id->{expectedReset=id;unloadAndSwap();},e->{busy=false;locked=false;log(e);status="准备失败，原地图未更换";});
    }
    void unloadAndSwap(){
        try{
            if(recoveryFailure)throw new IOException("检测到外部世界加载干扰");
            if(!guard.evacuate())throw new IOException("撤离检查失败");
            for(String name:names){World w=Bukkit.getWorld(name);if(w!=null){if(!w.getPlayers().isEmpty()||!Bukkit.unloadWorld(w,true))throw new IOException("无法卸载世界: "+name);}}
            for(String name:names)if(Bukkit.getWorld(name)!=null)throw new IOException("世界仍被加载: "+name);
            status="后台切换三界目录";
            background(()->{transaction.swapUnloaded();return null;},v->{
                try{loadExisting();finishLoadedReset();}catch(Exception ex){busy=false;isolationFailure(ex.getMessage());log(ex);}
            },e->{busy=false;isolationFailure(e.getMessage());log(e);});
        }catch(Exception e){busy=false;isolationFailure(e.getMessage());log(e);}
    }
    void finishLoadedReset()throws Exception{
        if(recoveryFailure)throw new IOException("存在外部干扰，需recover重新确认");
        status="自查三界更新和建筑入口";verifyActive(true);busy=true;
        background(()->{transaction.commit();transaction.cleanup();return null;},v->{
            try{verifyActive(false);locked=false;busy=false;recoveryFailure=false;status="在线重置完成，自查通过；玩家可 /mapreset play";writeAudit(true,status);afterJob();}
            catch(Exception ex){busy=false;isolationFailure(ex.getMessage());log(ex);}
        },e->{busy=false;isolationFailure("清理旧地图失败: "+e.getMessage());log(e);});
    }
    void verifyActive(boolean requireNewIDs)throws Exception{
        Set<UUID> ids=new HashSet<>();YamlConfiguration journal=transaction.pending()?transaction.journal():null;
        String id=expectedReset;
        for(int i=0;i<3;i++){
            World w=Bukkit.getWorld(names[i]);if(w==null||w.getEnvironment()!=environment(i))throw new IOException("三界未完整加载: "+names[i]);
            if(!w.getWorldFolder().toPath().toAbsolutePath().normalize().equals(root.resolve(names[i])))throw new IOException("世界目录不匹配");
            if(!ids.add(w.getUID())||(requireNewIDs&&oldWorldIDs.contains(w.getUID())))throw new IOException("世界UUID未更新/重复");
            YamlConfiguration identity=YamlConfiguration.loadConfiguration(root.resolve(names[i]).resolve(WorldTransaction.ID_FILE).toFile());
            if(id==null)id=identity.getString("reset-id");
            if(id==null||!id.equals(identity.getString("reset-id"))||identity.getInt("dimension",-1)!=i)throw new IOException("三界不是同一次地图更新");
            if(journal!=null&&journal.contains("seeds."+i)&&journal.getLong("seeds."+i)!=w.getSeed())throw new IOException("世界种子不匹配");
        }
        World overworld=Bukkit.getWorld(names[0]);YamlConfiguration layout=YamlConfiguration.loadConfiguration(root.resolve(names[0]).resolve("mapreset-coordinates.yml").toFile());
        if(layout.getInt("format")!=2||layout.getLong("seed")!=overworld.getSeed()||!structureRevision.equals(layout.getString("structure-revision")))throw new IOException("主世界生成元数据/结构版本未更新");
        if(Math.abs(overworld.getWorldBorder().getSize()-700)>.01||overworld.getWorldBorder().getCenter().getX()!=0||overworld.getWorldBorder().getCenter().getZ()!=0)throw new IOException("世界边界不正确");
        for(char ch='a';ch<='j';ch++){
            String k="buildings."+ch;if(!layout.isConfigurationSection(k))throw new IOException("缺少建筑坐标: "+ch);
            int x=layout.getInt(k+".min-x"),z=layout.getInt(k+".min-z"),width=layout.getInt(k+".width"),depth=layout.getInt(k+".depth");
            if(width<3||depth<3||x<MIN||z<MIN||x+width>MAX||z+depth>MAX)throw new IOException("建筑尺寸/坐标越界: "+ch);
            if(requireNewIDs&&ch<='c')for(String side:layout.getConfigurationSection(k+".entrances").getKeys(false)){
                String entry=k+".entrances."+side;int ex=layout.getInt(entry+".x"),ey=layout.getInt(entry+".y"),ez=layout.getInt(entry+".z");
                if(!overworld.getBlockAt(ex,ey,ez).getType().isSolid()||!overworld.getBlockAt(ex,ey+1,ez).isPassable()||!overworld.getBlockAt(ex,ey+2,ez).isPassable())throw new IOException("建筑入口地板/净空异常: "+ch);
            }
        }
        if(!templateReady(netherTemplate)||!templateReady(endTemplate))throw new IOException("固定维度模板被删除");expectedReset=id;
    }
    void writeAudit(boolean ok,String message){
        try{YamlConfiguration report=new YamlConfiguration();report.set("passed",ok);report.set("reset-id",expectedReset);report.set("checked-at",System.currentTimeMillis());report.set("message",message);report.set("worlds",Arrays.asList(names));report.set("reserve-target",5);report.set("reserve-count",readyPool().size());report.set("refill-state",readyPool().size()>=5?"COMPLETE":"REQUESTED");report.set("source-consumed",!transaction.pending());report.save(data.resolve("last-reset-check.yml").toFile());}catch(Exception e){log(e);}
    }
    void recoverOnline(boolean rollback)throws Exception{
        if(busy)throw new IOException("仍有任务执行中");if(!primaryReady())throw new IOException("大厅必须是默认世界");
        locked=true;busy=true;status="隔离下恢复地图事务";recoveryFailure=false;
        if(!guard.evacuate()){busy=false;throw new IOException("有玩家无法撤离");}
        for(String name:names){World w=Bukkit.getWorld(name);if(w!=null&&!Bukkit.unloadWorld(w,true)){busy=false;throw new IOException("仍无法卸载: "+name);}}
        boolean committed=transaction.pending()&&"COMMITTED".equals(transaction.journal().getString("state"));
        background(()->{if(rollback)transaction.rollbackUnloaded();else transaction.recoverAtStartup();return null;},v->{
            try{loadExisting();if(transaction.pending()){expectedReset=transaction.journal().getString("id");finishLoadedReset();}else{
                if(committed){verifyActive(false);writeAudit(true,"已恢复完成提交的地图更新");}
                else{expectedReset=null;writeAudit(false,"恢复原地图，取消本轮更新；未报告为更新成功");}
                busy=false;locked=false;status=committed?"更新恢复完成，自查通过":"已恢复原地图，本轮更新取消";afterJob();}}
            catch(Exception e){busy=false;isolationFailure(e.getMessage());log(e);}
        },e->{busy=false;isolationFailure(e.getMessage());log(e);});
    }
    boolean templateReady(Path folder){return Files.isRegularFile(folder.resolve("level.dat"))&&Files.isRegularFile(folder.resolve("READY"));}
    void pump(boolean force){
        if(stopping||busy||locked||recoveryFailure||provider==null||(!force&&!getConfig().getBoolean("generation.enabled",true)))return;
        try{
            if(!templateReady(netherTemplate)){startTemplate(netherTemplate,World.Environment.NETHER);return;}
            if(!templateReady(endTemplate)){startTemplate(endTemplate,World.Environment.THE_END);return;}
            if(readyPool().size()>=5){updateRefillAudit("COMPLETE");return;}
            busy=true;updateRefillAudit("RUNNING");status="生成备用主世界（700×700）";World w=newTemporary(World.Environment.NORMAL);generating=w;
            generationTask=new ReserveGenerator(this,w,pool.resolve("map-"+UUID.randomUUID()));generationTask.runTaskTimer(this,1,1);
        }catch(Exception e){generationFailed(generating,e);}
    }
    World newTemporary(World.Environment env){
        WorldCreator creator=new WorldCreator("mrgen_"+UUID.randomUUID().toString().replace("-","")).environment(env).seed(new Random().nextLong());
        // Use the vanilla generator for all game dimensions.
        internalLoad=true;World w;try{w=Bukkit.createWorld(creator);}finally{internalLoad=false;}
        if(w==null)throw new IllegalStateException("临时地图创建失败");w.setKeepSpawnInMemory(false);w.setAutoSave(true);return w;
    }
    void startTemplate(Path destination,World.Environment env){busy=true;status="建立固定维度模板: "+env;generating=newTemporary(env);exportGenerated(generating,destination);}
    void exportGenerated(World w,Path destination){
        try{
            YamlConfiguration info=new YamlConfiguration();info.set("seed",w.getSeed());info.set("environment",w.getEnvironment().name());info.save(w.getWorldFolder().toPath().resolve("mapreset-template.yml").toFile());
            w.save();Path source=w.getWorldFolder().toPath().toAbsolutePath().normalize();
            if(!w.getPlayers().isEmpty()||!Bukkit.unloadWorld(w,true))throw new IOException("临时地图无法卸载");
            generating=null;generationTask=null;status="后台保存备用地图";
            background(()->{
                Path stage=destination.resolveSibling(destination.getFileName()+".building");if(Files.exists(stage))FilesEx.delete(root,stage);
                FilesEx.copy(root,source,stage);if(!WorldTransaction.fingerprint(source).equals(WorldTransaction.fingerprint(stage)))throw new IOException("备用保存校验失败");
                Files.write(stage.resolve("READY"),Collections.singletonList("MapReset 2.3.0"));
                if(Files.exists(destination)){
                    try(java.util.stream.Stream<Path> stream=Files.list(destination)){if(stream.findAny().isPresent())throw new IOException("拒绝覆盖非空模板目录: "+destination);}
                    FilesEx.delete(root,destination);
                }
                FilesEx.move(root,stage,destination);FilesEx.delete(root,source);return null;
            },v->{busy=false;status="备用地图已完成";afterJob();},e->{busy=false;generationFailed(null,e);});
        }catch(Exception e){generationFailed(w,e);}
    }
    void updateRefillAudit(String state){try{java.io.File file=data.resolve("last-reset-check.yml").toFile();if(!file.exists())return;YamlConfiguration c=YamlConfiguration.loadConfiguration(file);c.set("refill-state",state);c.set("reserve-count",readyPool().size());c.set("refill-updated-at",System.currentTimeMillis());c.save(file);}catch(Exception e){log(e);}}
    void afterJob(){
        if(resetQueued&&!busy&&!locked){resetQueued=false;try{startReset();}catch(Exception e){status="排队重置失败: "+e.getMessage();log(e);}return;}
        Bukkit.getScheduler().runTaskLater(this,()->pump(false),60);
    }
    void generationFailed(World w,Exception e){
        if(generationTask!=null){generationTask.cancel();generationTask=null;}
        if(w!=null&&w.getPlayers().isEmpty())Bukkit.unloadWorld(w,true);generating=null;busy=false;status="生成失败（自动补库暂停）: "+e.getMessage();getConfig().set("generation.enabled",false);saveConfig();log(e);afterJob();
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void portal(PlayerPortalEvent e){
        World from=e.getFrom().getWorld();if(from==lobby){e.setCancelled(true);return;}int index=Arrays.asList(names).indexOf(from.getName());if(index<0)return;
        if(locked){e.setCancelled(true);return;}
        int destination=index==0?(e.getCause()==PlayerTeleportEvent.TeleportCause.END_PORTAL?2:1):0;World target=Bukkit.getWorld(names[destination]);if(target==null){e.setCancelled(true);return;}
        Location to=target.getSpawnLocation();if(e.getCause()==PlayerTeleportEvent.TeleportCause.NETHER_PORTAL){
            double factor=index==0?.125:8,x=e.getFrom().getX()*factor,z=e.getFrom().getZ()*factor;if(destination==0){x=Math.max(-340,Math.min(340,x));z=Math.max(-340,Math.min(340,z));}
            to=new Location(target,x,Math.min(120,Math.max(40,e.getFrom().getY())),z);
        }e.setTo(to);
    }
    <T> void background(Callable<T> work,Consumer<T> success,Consumer<Exception> failure){
        worker.execute(()->{try{T result=work.call();sync(()->success.accept(result));}catch(Exception e){sync(()->failure.accept(e));}});
    }
    void sync(Runnable action){if(!stopping)Bukkit.getScheduler().runTask(this,action);}
    void log(Exception e){getLogger().log(java.util.logging.Level.SEVERE,e.getMessage(),e);}
    @Override public void onDisable(){stopping=true;Bukkit.getScheduler().cancelTasks(this);worker.shutdown();try{if(!worker.awaitTermination(60,TimeUnit.SECONDS))getLogger().severe("文件任务尚未完成，勿强杀进程");}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
