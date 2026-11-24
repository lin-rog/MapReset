package cn.mapreset.probe;

import cn.mapreset.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import java.lang.reflect.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import static cn.mapreset.TerrainPlan.*;

/** Test-only observer. Runs the production generator, exports blocks from the real Paper world. */
public class RoadProbe extends JavaPlugin {
    MapResetPlugin plugin;BukkitRunnable generator;World world;Path output;long started;int lastPhase=-1,lastChunk=-1;boolean capturing;long seed;Path raw;
    public void onEnable(){plugin=(MapResetPlugin)Bukkit.getPluginManager().getPlugin("MapReset");getCommand("roadprobe").setExecutor(this);}
    static Field field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f;}
    static Object get(Object o,String name)throws Exception{return field(o,name).get(o);}
    static void set(Object o,String name,Object v)throws Exception{field(o,name).set(o,v);}
    public boolean onCommand(CommandSender s,Command c,String label,String[] args){
        try{
            if(args.length!=2||!args[0].equals("start")){s.sendMessage("roadprobe start <seed>");return true;}
            if(generator!=null)throw new IllegalStateException("Test already started");seed=Long.parseLong(args[1]);raw=getDataFolder().toPath().resolve("raw-"+seed);started=System.currentTimeMillis();
            output=getDataFolder().toPath().resolve("seed-"+seed+"-verified");Files.createDirectories(output);
            if(Files.isRegularFile(raw.resolve("terrain.dat"))&&!Files.exists(Paths.get("mrtest_"+seed))){copy(raw.resolve("world"),Paths.get("mrtest_"+seed));}
            set(plugin,"internalLoad",true);
            try{world=new WorldCreator("mrtest_"+seed).seed(seed).createWorld();}finally{set(plugin,"internalLoad",false);}
            world.setKeepSpawnInMemory(false);set(plugin,"busy",true);set(plugin,"generating",world);
            Class<?> type=Class.forName("cn.mapreset.ReserveGenerator",true,plugin.getClass().getClassLoader());
            Constructor<?> constructor=type.getDeclaredConstructor(MapResetPlugin.class,World.class,Path.class);constructor.setAccessible(true);
            generator=(BukkitRunnable)constructor.newInstance(plugin,world,((Path)get(plugin,"pool")).resolve("integration-"+seed+"-verified"));set(plugin,"generationTask",generator);
            if(Files.isRegularFile(raw.resolve("terrain.dat"))){
                try(DataInputStream in=new DataInputStream(new BufferedInputStream(Files.newInputStream(raw.resolve("terrain.dat"))))){
                    int[][] heights=(int[][])get(generator,"heights"),water=(int[][])get(generator,"waterLevels");
                    for(int x=0;x<700;x++)for(int z=0;z<700;z++){heights[x][z]=in.readInt();water[x][z]=in.readInt();}
                }
                set(generator,"chunk",1936);getLogger().info("PROBE reusing complete, unmodified real-world terrain snapshot");
            }
            new BukkitRunnable(){public void run(){try{
                int phase=(int)get(generator,"phase"),chunk=(int)get(generator,"chunk");
                if(phase!=lastPhase&&phase!=0&&phase!=6||chunk/128!=lastChunk/128){
                    getLogger().info("PROBE phase="+phase+" chunks="+chunk+" elapsed="+(System.currentTimeMillis()-started)/1000+"s");lastPhase=phase;lastChunk=chunk;
                }
                if(phase==1&&!Files.isRegularFile(raw.resolve("terrain.dat"))){checkpoint();cancel();return;}
                if(phase==5&&!capturing){capturing=true;generator.cancel();capture();cancel();}
                if((boolean)get(generator,"cancelled")&&!capturing){getLogger().severe("PROBE GENERATION FAILED: "+get(plugin,"status"));Files.write(output.resolve("FAILED.txt"),get(plugin,"status").toString().getBytes("UTF-8"));cancel();}
            }catch(Exception e){getLogger().log(java.util.logging.Level.SEVERE,"Probe failed",e);cancel();}}}.runTaskTimer(this,1,1);
            generator.runTaskTimer(plugin,1,1);s.sendMessage("Started real Paper generation seed="+seed);
        }catch(Exception e){getLogger().log(java.util.logging.Level.SEVERE,"Probe start failed",e);s.sendMessage(e.toString());}return true;
    }
    void checkpoint()throws Exception{
        generator.cancel();Files.createDirectories(raw);
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(raw.resolve("terrain.dat"))))){
            int[][] heights=(int[][])get(generator,"heights"),water=(int[][])get(generator,"waterLevels");
            for(int x=0;x<700;x++)for(int z=0;z<700;z++){out.writeInt(heights[x][z]);out.writeInt(water[x][z]);}
        }
        world.save();Path folder=world.getWorldFolder().toPath();if(!Bukkit.unloadWorld(world,true))throw new IOException("Cannot unload test world for snapshot");
        set(plugin,"generating",null);Path target=raw.resolve("world").toAbsolutePath().normalize(),root=Paths.get("").toAbsolutePath().normalize();
        if(!folder.toAbsolutePath().normalize().startsWith(root)||!target.startsWith(root))throw new IOException("Snapshot path escaped test server");
        Files.move(folder,target);generator=null;getLogger().info("PROBE saved unmodified real-world snapshot; starting production road stages");
        onCommand(Bukkit.getConsoleSender(),getCommand("roadprobe"),"roadprobe",new String[]{"start",Long.toString(seed)});
    }
    static void copy(Path from,Path to)throws IOException{
        try(java.util.stream.Stream<Path> paths=Files.walk(from)){
            for(Path file:(Iterable<Path>)paths::iterator){Path dest=to.resolve(from.relativize(file));if(Files.isDirectory(file))Files.createDirectories(dest);else Files.copy(file,dest);}
        }
    }
    @SuppressWarnings("unchecked") void capture()throws Exception{
        Map<Long,RoadCell> cells=(Map<Long,RoadCell>)get(generator,"roadCells");
        LayoutPlanner.Layout layout=(LayoutPlanner.Layout)get(generator,"layout");
        try(PrintWriter out=new PrintWriter(Files.newBufferedWriter(output.resolve("roads.csv")))){
            out.println("x,y,z,material,facing,stair");
            for(RoadCell cell:cells.values()){
                Block block=world.getBlockAt(cell.x,cell.y,cell.z);String facing=block.getBlockData() instanceof Stairs?((Stairs)block.getBlockData()).getFacing().name():"NONE";
                out.println(cell.x+","+cell.y+","+cell.z+","+block.getType()+","+facing+","+(block.getBlockData() instanceof Stairs));
            }
        }
        try(PrintWriter out=new PrintWriter(Files.newBufferedWriter(output.resolve("curves.csv")))){
            out.println("road,x,z");for(Road r:layout.roads)for(Point q:r.curve)out.println(r.a.id+"-"+r.b.id+","+q.x+","+q.z);
        }
        try(PrintWriter out=new PrintWriter(Files.newBufferedWriter(output.resolve("walk-collision.csv")))){
            out.println("road,lane,index,x,z,feetY,step");
            for(Road r:layout.roads)for(int lane=-1;lane<=1;lane++){
                double last=Double.NaN;int index=0;
                for(int i=1;i<r.curve.size()-1;i++){
                    Point a=r.curve.get(i-1),b=r.curve.get(i),next=r.curve.get(i+1);double dx=next.x-a.x,dz=next.z-a.z,len=Math.max(.001,Math.hypot(dx,dz));
                    for(int sample=0;sample<4;sample++){
                        double t=sample/4d,x=a.x+(b.x-a.x)*t-dz/len*lane+.5,z=a.z+(b.z-a.z)*t+dx/len*lane+.5;
                        double y=feet(x,z,cells),step=Double.isNaN(last)?0:y-last;
                        out.println(r.a.id+"-"+r.b.id+","+lane+","+(index++)+","+x+","+z+","+y+","+step);last=y;
                    }
                }
            }
        }
        Set<Long> columns=new LinkedHashSet<>();for(RoadCell cell:cells.values())for(int dx=-18;dx<=18;dx++)for(int dz=-18;dz<=18;dz++)columns.add(key(cell.x+dx,cell.z+dz));
        List<Long> list=new ArrayList<>(columns);PrintWriter out=new PrintWriter(Files.newBufferedWriter(output.resolve("surface.csv")));out.println("x,y,z,material,data");
        PrintWriter groundOut=new PrintWriter(Files.newBufferedWriter(output.resolve("ground.csv")));groundOut.println("x,y,z,material,data");
        PrintWriter voxels=new PrintWriter(Files.newBufferedWriter(output.resolve("voxels.csv")));voxels.println("x,y,z,material,data");
        new BukkitRunnable(){int index;
            public void run(){try{
                for(int count=0;count<256&&index<list.size();count++,index++){
                    long packed=list.get(index);int x=(int)(packed>>32),z=(int)packed;
                    int y=Math.min(254,world.getHighestBlockYAt(x,z));while(y>1&&world.getBlockAt(x,y,z).getType().isAir())y--;
                    Block b=world.getBlockAt(x,y,z);out.println(x+","+y+","+z+","+b.getType()+",\""+b.getBlockData().getAsString()+"\"");
                    int ground=y;while(ground>1&&!natural(world.getBlockAt(x,ground,z).getType()))ground--;
                    Block g=world.getBlockAt(x,ground,z);groundOut.println(x+","+ground+","+z+","+g.getType()+",\""+g.getBlockData().getAsString()+"\"");
                    for(int yy=ground+1;yy<=y;yy++){Block v=world.getBlockAt(x,yy,z);if(v.getType().isSolid()||v.getType()==Material.WATER)voxels.println(x+","+yy+","+z+","+v.getType()+",\""+v.getBlockData().getAsString()+"\"");}

                }
                if(index==list.size()){
                    out.close();groundOut.close();voxels.close();world.save();Files.write(output.resolve("timing.txt"),Arrays.asList("milliseconds="+(System.currentTimeMillis()-started),"roadCells="+cells.size(),"surfaceColumns="+list.size()));
                    Method validate=generator.getClass().getDeclaredMethod("validateAndSave");validate.setAccessible(true);validate.invoke(generator);
                    getLogger().info("PROBE CAPTURED AND GENERATOR VALIDATED: "+output);cancel();
                }
            }catch(Exception e){out.close();groundOut.close();voxels.close();getLogger().log(java.util.logging.Level.SEVERE,"Capture failed",e);cancel();}}
        }.runTaskTimer(this,1,1);
    }
    static boolean natural(Material m){String n=m.name();return m==Material.GRASS_BLOCK||m==Material.DIRT||m==Material.STONE||m==Material.SAND||m==Material.GRAVEL||m==Material.CLAY||m==Material.PODZOL||m==Material.COARSE_DIRT||m==Material.BEDROCK||m==Material.GRANITE||m==Material.ANDESITE||m==Material.DIORITE||m==Material.SNOW_BLOCK||n.endsWith("TERRACOTTA");}
    double feet(double x,double z,Map<Long,RoadCell> cells){
        int bx=(int)Math.floor(x),bz=(int)Math.floor(z);RoadCell cell=cells.get(key(bx,bz));if(cell==null)return Double.NaN;
        Block block=world.getBlockAt(bx,cell.y,bz);double height=Double.NEGATIVE_INFINITY;
        org.bukkit.craftbukkit.v1_16_R3.block.CraftBlock craft=(org.bukkit.craftbukkit.v1_16_R3.block.CraftBlock)block;
        net.minecraft.server.v1_16_R3.WorldServer handle=((org.bukkit.craftbukkit.v1_16_R3.CraftWorld)world).getHandle();
        for(net.minecraft.server.v1_16_R3.AxisAlignedBB box:craft.getNMS().getCollisionShape(handle,craft.getPosition()).d())if(x-bx>=box.minX-1e-6&&x-bx<=box.maxX+1e-6&&z-bz>=box.minZ-1e-6&&z-bz<=box.maxZ+1e-6)height=Math.max(height,cell.y+box.maxY);
        return height;
    }
}
