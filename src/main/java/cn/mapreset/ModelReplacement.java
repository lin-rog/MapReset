package cn.mapreset;
import org.bukkit.*;import org.bukkit.configuration.file.YamlConfiguration;import org.bukkit.scheduler.BukkitRunnable;import java.util.*;import java.io.*;
/** Replaces an existing reservation without moving its center, floor or logical road anchors. */
final class ModelReplacement extends BukkitRunnable {
 final MapResetPlugin p;final World world;final String id;final StructureProvider provider;final StructureSpec spec;final YamlConfiguration layout;
 final int cx,cz,floor,minX,minZ,oldX,oldZ,oldW,oldD;final List<int[]> columns=new ArrayList<>(),corridor=new ArrayList<>();int phase,index;
 ModelReplacement(MapResetPlugin p,String id)throws Exception{
  this.p=p;this.id=id;world=Bukkit.getWorld(p.names[0]);if(world==null)throw new IOException("主世界尚未启用");provider=p.provider;spec=provider.spec(id);if(spec==null)throw new IOException("未知建筑");
  layout=YamlConfiguration.loadConfiguration(world.getWorldFolder().toPath().resolve("mapreset-coordinates.yml").toFile());String k="buildings."+id;
  if(!layout.isConfigurationSection(k))throw new IOException("缺少该建筑的预留记录");cx=layout.getInt(k+".x");cz=layout.getInt(k+".z");floor=layout.getInt(k+".y");minX=cx-spec.width/2;minZ=cz-spec.depth/2;oldX=layout.getInt(k+".min-x");oldZ=layout.getInt(k+".min-z");oldW=layout.getInt(k+".width");oldD=layout.getInt(k+".depth");
  if(minX< -350||minZ< -350||minX+spec.width>350||minZ+spec.depth>350||floor-spec.firstFloor<1||floor-spec.firstFloor+spec.height>=255)throw new IOException("替换后模型越界，原图未修改");
  for(char other='a';other<='j';other++)if(!id.equals(""+other)){String o="buildings."+other;int x=layout.getInt(o+".min-x"),z=layout.getInt(o+".min-z"),w=layout.getInt(o+".width"),d=layout.getInt(o+".depth");if(minX<=x+w+2&&minX+spec.width+2>=x&&minZ<=z+d+2&&minZ+spec.depth+2>=z)throw new IOException("模型与建筑"+other+"重叠");}
  if(layout.isConfigurationSection(k+".entrances"))for(String side:layout.getConfigurationSection(k+".entrances").getKeys(false)){
   StructureSpec.Entry entry=spec.entrances.get(side);if(entry==null)throw new IOException("模型必须提供已预留的入口侧面: "+side);
   int x=layout.getInt(k+".entrances."+side+".x"),z=layout.getInt(k+".entrances."+side+".z"),tx=minX+entry.x,tz=minZ+entry.z;
   Set<Long> seen=new HashSet<>();while(true){for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){long key=TerrainPlan.key(x+dx,z+dz);if(seen.add(key))corridor.add(new int[]{x+dx,z+dz});}if(x==tx&&z==tz)break;if(x!=tx)x+=Integer.signum(tx-x);else z+=Integer.signum(tz-z);}
  }
  for(int[] cell:corridor)for(char other='a';other<='j';other++)if(!id.equals(""+other)){String o="buildings."+other;int x=layout.getInt(o+".min-x"),z=layout.getInt(o+".min-z"),w=layout.getInt(o+".width"),d=layout.getInt(o+".depth");if(cell[0]>=x&&cell[0]<x+w&&cell[1]>=z&&cell[1]<z+d)throw new IOException("Entrance connector overlaps another building");}
  if(id.equals("d")||id.equals("e"))for(String road:layout.getConfigurationSection("roads").getKeys(false))for(String cell:layout.getStringList("roads."+road+".blocks")){
   String[] v=cell.split(",");int x=Integer.parseInt(v[0]),z=Integer.parseInt(v[2]);double distance=Math.hypot(Math.max(Math.max(minX-x,x-(minX+spec.width-1)),0),Math.max(Math.max(minZ-z,z-(minZ+spec.depth-1)),0));if(distance<30)throw new IOException("替换尺寸导致D/E距道路不足30格");
  }
  for(int x=Math.min(minX,oldX);x<Math.max(minX+spec.width,oldX+oldW);x++)for(int z=Math.min(minZ,oldZ);z<Math.max(minZ+spec.depth,oldZ+oldD);z++)columns.add(new int[]{x,z});
 }
 void start()throws Exception{p.busy=true;p.locked=true;if(!p.guard.evacuate()){p.busy=false;p.locked=false;throw new IOException("无法撤离玩家");}p.status="保持预留坐标和接口，替换模型 "+id;runTaskTimer(p,1,1);}
 public void run(){try{
  int budget=Math.max(1,p.getConfig().getInt("generation.columns-per-tick",24));
  if(phase==0){for(int count=0;count<budget&&index<columns.size();count++,index++){
   int[] c=columns.get(index);int top=Math.max(world.getHighestBlockYAt(c[0],c[1]),floor+spec.height);for(int y=floor-spec.firstFloor;y<=Math.min(254,top);y++)world.getBlockAt(c[0],y,c[1]).setType(Material.AIR,false);
   if(c[0]>=minX&&c[0]<minX+spec.width&&c[1]>=minZ&&c[1]<minZ+spec.depth){
    for(int y=floor;y>=1;y--){Material m=world.getBlockAt(c[0],y,c[1]).getType();if(floor-y>=5&&VanillaScan.ground(m))break;world.getBlockAt(c[0],y,c[1]).setType(Material.STONE_BRICKS,false);}
    provider.placeColumn(world,id,minX,floor-spec.firstFloor,minZ,c[0]-minX,c[1]-minZ);
    for(int y=floor-4;y<=floor;y++)world.getBlockAt(c[0],y,c[1]).setType(Material.STONE_BRICKS,false);
   }else{
    for(int y=floor-spec.firstFloor;y<floor;y++)world.getBlockAt(c[0],y,c[1]).setType(Material.DIRT,false);
    world.getBlockAt(c[0],floor,c[1]).setType(Material.GRASS_BLOCK,false);
   }
  }if(index==columns.size()){index=0;phase=1;}return;}
  if(phase==1){for(int count=0;count<budget&&index<corridor.size();count++,index++){
   int[] c=corridor.get(index);if(c[0]< -350||c[0]>=350||c[1]< -350||c[1]>=350)continue;
   world.getBlockAt(c[0],floor,c[1]).setType(Material.STONE_BRICKS,false);for(int y=1;y<=3;y++)world.getBlockAt(c[0],floor+y,c[1]).setType(Material.AIR,false);
   for(int y=floor-1;y>=1&&!VanillaScan.ground(world.getBlockAt(c[0],y,c[1]).getType());y--)world.getBlockAt(c[0],y,c[1]).setType(Material.STONE_BRICKS,false);
  }if(index<corridor.size())return;phase=2;}
  String k="buildings."+id;layout.set(k+".reserved-width",oldW);layout.set(k+".reserved-depth",oldD);layout.set(k+".min-x",minX);layout.set(k+".min-z",minZ);layout.set(k+".width",spec.width);layout.set(k+".depth",spec.depth);layout.set(k+".height",spec.height);layout.set(k+".first-floor-local-y",spec.firstFloor);layout.set(k+".origin-y",floor-spec.firstFloor);layout.set(k+".placeholder",false);
  for(StructureSpec.Entry e:spec.entrances.values()){layout.set(k+".model-entrances."+e.side+".x",minX+e.x);layout.set(k+".model-entrances."+e.side+".y",floor+1);layout.set(k+".model-entrances."+e.side+".z",minZ+e.z);}
  layout.set("structure-revision",p.structureRevision);layout.save(world.getWorldFolder().toPath().resolve("mapreset-coordinates.yml").toFile());world.save();p.busy=false;p.locked=false;p.status="模型替换完成，中心/第一层/原接口未移动";cancel();p.reconcilePoolThenPump();
 }catch(Exception e){cancel();p.busy=false;p.isolationFailure("模型替换失败，请检查模型和地图: "+e.getMessage());p.log(e);}}
}
