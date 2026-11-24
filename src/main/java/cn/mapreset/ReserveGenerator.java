package cn.mapreset;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.Slab;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitRunnable;
import java.nio.file.*;
import java.util.*;
import static cn.mapreset.TerrainPlan.*;

/** Main-thread world mutations are budgeted by chunks/columns; pure planning runs on worker. */
final class ReserveGenerator extends BukkitRunnable implements Surface {
    final MapResetPlugin p;final World world;final Path destination;final StructureProvider structures;final String revision;
    final Map<String,StructureSpec> specs=new LinkedHashMap<>();final int[][] heights=new int[700][700];final boolean[][] wet=new boolean[700][700];final int[][] waterLevels=new int[700][700];
    int phase=0,chunk=0,editIndex=0,roadIndex=0,siteIndex=0,column=0;boolean cancelled=false;
    LayoutPlanner.Layout layout;List<RoadEarthworks.Column> earthworks;List<RoadCell> paving;Map<Long,RoadCell> roadCells;
    boolean scanBusy;VanillaScan scan;int scanOffset;
    List<WaterGuards.Rail> rails;List<int[]> treeColumns;TreeCleaner trees;int treeIndex,railIndex;
    static long seedFor(World w){return w.getSeed();}
    final long seed;final int candidateAttempts;final double maxDetour;
    ReserveGenerator(MapResetPlugin p,World w,Path destination){
        this.p=p;world=w;this.destination=destination;structures=p.getConfig().getBoolean("placeholders.enabled",true)?new PlaceholderStructures(p.provider,seedFor(w),p.getConfig().getInt("placeholders.min-size",9),p.getConfig().getInt("placeholders.max-size",19)):p.provider;revision=p.structureRevision;
        for(int[] row:waterLevels)Arrays.fill(row,-1);
        seed=w.getSeed();candidateAttempts=p.getConfig().getInt("generation.candidate-attempts",1200);maxDetour=2.0;
        for(char id='a';id<='j';id++)specs.put(""+id,structures.spec(""+id));
        w.getWorldBorder().setCenter(0,0);w.getWorldBorder().setSize(700);w.setKeepSpawnInMemory(false);w.setGameRule(GameRule.DO_MOB_SPAWNING,false);w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false);w.setGameRule(GameRule.RANDOM_TICK_SPEED,0);
    }
    public int height(int x,int z){return heights[Math.max(0,Math.min(699,x-MIN))][Math.max(0,Math.min(699,z-MIN))];}
    public boolean water(int x,int z){return waterLevel(x,z)>=0;}
    public int waterLevel(int x,int z){return x<MIN||x>=MAX||z<MIN||z>=MAX?-1:waterLevels[x-MIN][z-MIN];}
    int naturalSurface(int x,int z){
        int y=world.getHighestBlockYAt(x,z);while(y>1&&!VanillaScan.ground(world.getBlockAt(x,y,z).getType()))y--;return y;
    }
    void beginPlanning(){
        phase=1;p.status="规划预留位置、接口和道路";
        p.background(()->{
            LayoutPlanner.Layout planned=new LayoutPlanner(this,specs,seed,candidateAttempts,maxDetour).plan();
            Map<Long,RoadCell> cells=RoadGrade.reconcile(planned.roads,this);
            Map<Long,RoadEarthworks.Column> edits=RoadEarthworks.plan(this,planned.roads,planned.sites);
            return new Object[]{planned,cells,new ArrayList<>(edits.values()),WaterGuards.plan(this,cells,planned.sites)};
        },result->{
            if(cancelled)return;layout=(LayoutPlanner.Layout)result[0];
            @SuppressWarnings("unchecked") Map<Long,RoadCell> cells=(Map<Long,RoadCell>)result[1];roadCells=cells;paving=new ArrayList<>(cells.values());
            @SuppressWarnings("unchecked") List<RoadEarthworks.Column> edits=(List<RoadEarthworks.Column>)result[2];earthworks=edits;
            @SuppressWarnings("unchecked") List<WaterGuards.Rail> guards=(List<WaterGuards.Rail>)result[3];rails=guards;
            Map<Long,int[]> affected=new LinkedHashMap<>();
            for(RoadEarthworks.Column c:earthworks)affected.put(key(c.x,c.z),new int[]{c.x,c.z});
            for(Site site:layout.sites)for(int x=site.minX();x<=site.maxX();x++)for(int z=site.minZ();z<=site.maxZ();z++)affected.put(key(x,z),new int[]{x,z});
            for(WaterGuards.Rail rail:rails)affected.put(key(rail.x,rail.z),new int[]{rail.x,rail.z});
            treeColumns=new ArrayList<>(affected.values());trees=new TreeCleaner(new TreeCleaner.Grid(){
                public Material get(int x,int y,int z){return world.getBlockAt(x,y,z).getType();}
                public void clear(int x,int y,int z){world.getBlockAt(x,y,z).setType(Material.AIR,false);}
            });
            try{saveReservations();}catch(Exception e){cancel();p.generationFailed(world,e);return;}
            phase=7;p.status="扫描受地形修整影响的整棵树木";
        },e->{if(!cancelled){cancel();p.generationFailed(world,e);}});
    }
    void saveReservations()throws Exception{
        YamlConfiguration c=new YamlConfiguration();c.set("stage","RESERVED");
        for(Site site:layout.sites){String k="buildings."+site.id;c.set(k+".x",site.x);c.set(k+".z",site.z);c.set(k+".first-floor-block-y",site.floorY);c.set(k+".first-floor-top-y",site.floorY+1);c.set(k+".width",site.spec.width);c.set(k+".depth",site.spec.depth);c.set(k+".height",site.spec.height);
            for(StructureSpec.Entry e:site.spec.entrances.values()){String ek=k+".entrances."+e.side;c.set(ek+".x",site.minX()+e.x);c.set(ek+".y",site.floorY+1);c.set(ek+".z",site.minZ()+e.z);}}
        c.save(world.getWorldFolder().toPath().resolve("mapreset-reservations.yml").toFile());
    }
    @Override public void cancel(){cancelled=true;super.cancel();}
    public void run(){
        try{
            int budget=Math.max(1,p.getConfig().getInt("generation.columns-per-tick",24));
            if(phase==0){
                if(scanBusy)return;
                if(chunk==44*44){beginPlanning();return;}
                int cx=-22+chunk/44,cz=-22+chunk%44;scanBusy=true;
                AsyncChunks.load(world,cx,cz,p,(loaded)->{
                    org.bukkit.ChunkSnapshot snapshot=loaded.getChunkSnapshot(true,false,false);
                    p.background(()->VanillaScan.scan(snapshot,cx,cz),result->{scan=result;scanOffset=0;phase=6;scanBusy=false;},e->{scanBusy=false;cancel();p.generationFailed(world,e);});
                },e->{scanBusy=false;cancel();p.generationFailed(world,e);});return;
            }
            if(phase==6){
                int edits=0;while(scanOffset<scan.ores.size()&&edits++<2048){int[] v=scan.ores.get(scanOffset++);world.getBlockAt(v[0],v[1],v[2]).setType(Material.STONE,false);}
                if(scanOffset==scan.ores.size()){
                    for(int[] v:scan.surface){heights[v[0]-MIN][v[1]-MIN]=v[2];wet[v[0]-MIN][v[1]-MIN]=v[3]!=0;waterLevels[v[0]-MIN][v[1]-MIN]=v[4];}
                    world.unloadChunkRequest(scan.cx,scan.cz);chunk++;scan=null;phase=0;p.status="原版生成及矿石替换 "+chunk+"/1936区块";
                }return;
            }
            if(phase==1)return;
            if(phase==7){
                for(int n=0;n<budget&&treeIndex<treeColumns.size();n++,treeIndex++){
                    int[] c=treeColumns.get(treeIndex);int top=Math.min(255,world.getHighestBlockYAt(c[0],c[1]));
                    for(int y=height(c[0],c[1])+1;y<=top;y++)trees.seed(c[0],y,c[1],world.getBlockAt(c[0],y,c[1]).getType());
                }
                if(treeIndex==treeColumns.size()){phase=8;p.status="完整清理受影响树干和树冠";}return;
            }
            if(phase==8){
                if(trees.step(Math.max(1,p.getConfig().getInt("generation.tree-nodes-per-tick",256)))){phase=2;p.status="树木清理完成，开始修整地形";}return;
            }
            if(phase==9){
                for(int n=0;n<budget&&railIndex<rails.size();n++,railIndex++){
                    WaterGuards.Rail rail=rails.get(railIndex);foundation(rail.x,rail.floor-1,rail.z,1);
                    for(int y=rail.floor;y<=rail.floor+2;y++)world.getBlockAt(rail.x,y,rail.z).setType(Material.GLASS,false);
                }
                if(railIndex==rails.size()){phase=4;p.status="后建建筑：按各自第一层和入口高度放置";}return;
            }
            if(phase==2){
                for(int k=0;k<budget&&editIndex<earthworks.size();k++,editIndex++){
                    RoadEarthworks.Column edit=earthworks.get(editIndex);reshape(edit.x,edit.surface,edit.z);
                }
                if(editIndex==earthworks.size()){phase=3;p.status="先修路：铺设3–5格混合路面及上下坡楼梯";}return;
            }
            if(phase==3){
                for(int k=0;k<budget&&roadIndex<paving.size();k++,roadIndex++){
                    RoadCell cell=paving.get(roadIndex);foundation(cell.x,cell.y-1,cell.z,1);
                    if(cell.slab){
                        Material material=Material.matchMaterial((mix(cell.x,cell.z)?p.stairD:p.stairC).name().replace("_STAIRS","_SLAB"));
                        if(material==null)throw new IllegalStateException("楼梯材料缺少对应台阶");
                        Slab slab=(Slab)material.createBlockData();slab.setType(Slab.Type.BOTTOM);slab.setWaterlogged(false);world.getBlockAt(cell.x,cell.y,cell.z).setBlockData(slab,false);
                    }else if(cell.stair){
                        Stairs stairs=(Stairs)(mix(cell.x,cell.z)?p.stairD:p.stairC).createBlockData();
                        BlockFace face=Math.abs(cell.tx)>=Math.abs(cell.tz)?(cell.tx>0?BlockFace.EAST:BlockFace.WEST):(cell.tz>0?BlockFace.SOUTH:BlockFace.NORTH);
                        stairs.setFacing(face);stairs.setHalf(Bisected.Half.BOTTOM);stairs.setShape(Stairs.Shape.STRAIGHT);stairs.setWaterlogged(false);world.getBlockAt(cell.x,cell.y,cell.z).setBlockData(stairs,false);
                    }else world.getBlockAt(cell.x,cell.y,cell.z).setType(mix(cell.x,cell.z)?p.roadB:p.roadA,false);
                    for(int dy=1;dy<=3;dy++)world.getBlockAt(cell.x,cell.y+dy,cell.z).setType(Material.AIR,false);
                }
                if(roadIndex==paving.size()){phase=9;p.status="沿临水道路两侧安装玻璃护栏（不封顶）";}return;
            }
            if(phase==4){
                if(siteIndex==layout.sites.size()){phase=5;p.status="检查建筑和道路连接、坐标与尺寸";return;}
                Site site=layout.sites.get(siteIndex);int originY=site.floorY-site.spec.firstFloor;
                for(int k=0;k<budget&&column<site.spec.width*site.spec.depth;k++,column++){
                    int lx=column/site.spec.depth,lz=column%site.spec.depth,x=site.minX()+lx,z=site.minZ()+lz,top=world.getHighestBlockYAt(x,z);
                    for(int y=originY;y<=Math.min(254,Math.max(top,originY+site.spec.height));y++)world.getBlockAt(x,y,z).setType(Material.AIR,false);
                    foundation(x,site.floorY,z,site.spec.foundationLayers);
                    structures.placeColumn(world,site.id,site.minX(),originY,site.minZ(),lx,lz);
                    for(int y=site.floorY-4;y<=site.floorY;y++)world.getBlockAt(x,y,z).setType(Material.STONE_BRICKS,false);

                }
                if(column==site.spec.width*site.spec.depth){siteIndex++;column=0;}return;
            }
            if(phase==5){validateAndSave();cancel();}
        }catch(Exception e){cancel();p.generationFailed(world,e);}
    }
    static boolean forbiddenOre(Material material){return material==Material.IRON_ORE||material==Material.GOLD_ORE||material==Material.DIAMOND_ORE||material==Material.EMERALD_ORE;}
    boolean mix(int x,int z){double ratio=p.getConfig().getDouble("blocks.road-b-ratio",.35);long hash=TerrainModel.hash(world.getSeed()^key(x,z));return (hash>>>11)*0x1.0p-53<ratio;}
    void reshape(int x,int y,int z){
        int current=naturalSurface(x,z),top=world.getHighestBlockYAt(x,z);
        for(int yy=y+1;yy<=top;yy++)world.getBlockAt(x,yy,z).setType(Material.AIR,false);
        if(y>current)for(int yy=current+1;yy<y;yy++)world.getBlockAt(x,yy,z).setType(yy>=y-3?Material.DIRT:Material.STONE,false);
        world.getBlockAt(x,y,z).setType(Material.GRASS_BLOCK,false);
        for(int yy=Math.max(1,y-2);yy<y;yy++)world.getBlockAt(x,yy,z).setType(Material.DIRT,false);
    }
    void foundation(int x,int top,int z,int required){
        int max=Math.max(5,Math.min(255,p.getConfig().getInt("generation.foundation-max-depth",255)));
        for(int y=top;y>=1;y--){
            Material material=world.getBlockAt(x,y,z).getType();String name=material.name();
            if(top-y>=required&&material.isSolid()&&!name.endsWith("_LOG")&&!name.endsWith("_LEAVES"))return;
            if(top-y>=max)throw new IllegalStateException("地基超过最大深度，地图不进入备用库");world.getBlockAt(x,y,z).setType(p.foundation,false);
        }
    }
    void validateAndSave()throws Exception{
        Site a=null;YamlConfiguration c=new YamlConfiguration();c.set("format",2);c.set("structure-revision",revision);c.set("seed",world.getSeed());c.set("border.min",MIN);c.set("border.max-exclusive",MAX);
        for(Site site:layout.sites){
            if(site.id.equals("a"))a=site;String key="buildings."+site.id;
            c.set(key+".x",site.x);c.set(key+".y",site.floorY);c.set(key+".z",site.z);c.set(key+".min-x",site.minX());c.set(key+".min-z",site.minZ());c.set(key+".width",site.spec.width);c.set(key+".depth",site.spec.depth);c.set(key+".height",site.spec.height);
            c.set(key+".first-floor-top-y",site.floorY+1);c.set(key+".origin-y",site.floorY-site.spec.firstFloor);c.set(key+".first-floor-local-y",site.spec.firstFloor);c.set(key+".foundation-layers",site.spec.foundationLayers);
            for(StructureSpec.Entry entry:site.spec.entrances.values()){
                int x=site.minX()+entry.x,z=site.minZ()+entry.z;String e=key+".entrances."+entry.side;
                c.set(e+".x",x);c.set(e+".y",site.floorY);c.set(e+".z",z);
                if(!world.getBlockAt(x,site.floorY,z).getType().isSolid()||!world.getBlockAt(x,site.floorY+1,z).isPassable()||!world.getBlockAt(x,site.floorY+2,z).isPassable())throw new IllegalStateException(site.id+" 入口被建筑接口堵塞");
            }
            if((site.id.equals("d")||site.id.equals("e"))&&roadDistance(site,layout.roads)<30)throw new IllegalStateException("D/E距离道路不足30格");
        }
        List<String> guardCoordinates=new ArrayList<>();
        for(WaterGuards.Rail rail:rails){
            if(roadCells.containsKey(key(rail.x,rail.z)))throw new IllegalStateException("护栏侵占路面");
            for(int y=rail.floor;y<=rail.floor+2;y++)if(world.getBlockAt(rail.x,y,rail.z).getType()!=Material.GLASS)throw new IllegalStateException("临水护栏不完整");
            guardCoordinates.add(rail.x+","+rail.floor+","+rail.z);
        }
        c.set("water-rails.columns",guardCoordinates);c.set("water-rails.height-above-road",2);c.set("water-rails.roof",false);c.set("validation.removed-tree-blocks",trees.removed);
        for(RoadCell cell:roadCells.values()){
            if(cell.y<=waterLevel(cell.x,cell.z))throw new IllegalStateException("路面低于原水面");
            for(int dy=1;dy<=3;dy++)if(!world.getBlockAt(cell.x,cell.y+dy,cell.z).isPassable()||world.getBlockAt(cell.x,cell.y+dy,cell.z).isLiquid())throw new IllegalStateException("道路净空被阻挡");
        }
        for(Site site:layout.sites)for(int x=site.minX();x<=site.maxX();x++)for(int z=site.minZ();z<=site.maxZ();z++)if(site.floorY<=waterLevel(x,z))throw new IllegalStateException("建筑第一层低于原水面");
        for(Road road:layout.roads){
            for(Point q:road.curve)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
                int x=(int)Math.round(q.x)+dx,z=(int)Math.round(q.z)+dz;boolean building=false;
                for(Site site:layout.sites)if(site.contains(x,z)){building=true;break;}
                if(!building){RoadCell cell=roadCells.get(key(x,z));if(cell==null||!world.getBlockAt(x,cell.y,z).getType().isSolid())throw new IllegalStateException("道路3格连续铺面有缺口: "+x+","+z);}
            }

            for(Site site:new Site[]{road.a,road.b}){
                StructureSpec.Entry entry=site.entrance(site==road.a?road.b:road.a);Point edge=site.outside(entry,1);RoadCell cell=roadCells.get(key((int)edge.x,(int)edge.z));
                if(cell==null||cell.y!=site.floorY||!world.getBlockAt(cell.x,cell.y,cell.z).getType().isSolid())throw new IllegalStateException("道路未与建筑第一层连通: "+site.id);
            }
            String k="roads."+road.a.id+"-"+road.b.id;List<String> points=new ArrayList<>();for(RoadCell cell:road.cells.values())points.add(cell.x+","+cell.y+","+cell.z+","+cell.width+","+cell.stair+","+cell.slab);
            c.set(k+".blocks",points);c.set(k+".mountain-pass",road.passCut);
        }
        c.set("j.x",(int)layout.j.x);c.set("j.z",(int)layout.j.z);c.set("j.y",layout.jY);
        if(a==null)throw new IllegalStateException("缺少A");world.setSpawnLocation(a.x,a.floorY+1,a.z);world.setGameRule(GameRule.SPAWN_RADIUS,0);world.setGameRule(GameRule.DO_MOB_SPAWNING,true);world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,true);world.setGameRule(GameRule.RANDOM_TICK_SPEED,3);
        c.set("validation.passed",true);c.save(world.getWorldFolder().toPath().resolve("mapreset-coordinates.yml").toFile());p.exportGenerated(world,destination);
    }
}
