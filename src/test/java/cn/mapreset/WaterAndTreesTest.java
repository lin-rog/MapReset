package cn.mapreset;

import org.bukkit.*;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.Assert.*;
import static cn.mapreset.TerrainPlan.*;

public class WaterAndTreesTest {
    StructureSpec spec(){return new StructureSpec(13,13,11,4,5,null);}
    Surface ocean=new Surface(){
        public int height(int x,int z){return 22+(int)(Math.abs(x*13L+z*7L)%19);}
        public boolean water(int x,int z){return true;}
        public int waterLevel(int x,int z){return 63;}
    };
    @Test public void snapshotKeepsWaterSurfaceSeparateFromSeabed(){
        ChunkSnapshot chunk=(ChunkSnapshot)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ChunkSnapshot.class},(p,m,a)->{
            if(m.getName().equals("getHighestBlockYAt"))return 64;
            if(m.getName().equals("getBlockType")){int y=(Integer)a[1];return y<=35?Material.STONE:y<=62?Material.KELP_PLANT:y==63?Material.WATER:Material.AIR;}
            throw new UnsupportedOperationException(m.getName());
        });
        VanillaScan scan=VanillaScan.scan(chunk,0,0);assertEquals(256,scan.surface.size());
        for(int[] v:scan.surface){assertEquals(35,v[2]);assertEquals(1,v[3]);assertEquals(63,v[4]);}
    }
    @Test public void buildingsUseHighestWaterIncludingDoorApron(){
        Surface shore=new Surface(){public int height(int x,int z){return 35;}public boolean water(int x,int z){return x>=8;}public int waterLevel(int x,int z){return water(x,z)?63:-1;}};
        Site a=new Site("a",0,0,spec());new LayoutPlanner(shore,Collections.emptyMap(),1,100,1.5).average(a);
        assertEquals("Water beside the entrance raises the whole first floor",64,a.floorY);
        Site b=new Site("b",100,0,spec());new LayoutPlanner(ocean,Collections.emptyMap(),1,100,1.5).average(b);assertEquals(64,b.floorY);
    }
    @Test public void deepWaterAndJaggedShoreDoNotProduceZigzag(){
        for(boolean shore:new boolean[]{false,true}){
            Surface terrain=shore?new Surface(){public int height(int x,int z){return water(x,z)?25:63;}public boolean water(int x,int z){return x%29<15&&Math.abs(z)<15;}public int waterLevel(int x,int z){return water(x,z)?63:-1;}}:ocean;
            Site a=new Site("a",-120,0,spec()),b=new Site("b",120,0,spec());a.floorY=b.floorY=64;
            Road r=road(terrain,a,b,Arrays.asList(a,b),45,1.5);
            assertTrue(length(r.curve)<250);int reversals=0,previous=0;double maxBend=0;
            for(int i=1;i<r.curve.size();i++){
                Point p=r.curve.get(i-1),q=r.curve.get(i);assertTrue("No backtracking",q.x>=p.x-1e-6);maxBend=Math.max(maxBend,Math.abs(q.z));
                double dz=q.z-p.z;if(Math.abs(dz)>1e-6){int sign=dz>0?1:-1;if(previous!=0&&previous!=sign)reversals++;previous=sign;}
            }
            assertTrue("One broad bend rather than a Z",reversals<=1);assertTrue(maxBend>3&&maxBend<=9.01);
            Map<Long,RoadCell> cells=RoadGrade.reconcile(Collections.singletonList(r),terrain);
            for(RoadCell c:cells.values()){assertTrue(c.y>terrain.waterLevel(c.x,c.z));for(int[] n:RoadGrade.neighbors){RoadCell other=cells.get(key(c.x+n[0],c.z+n[1]));if(other!=null)assertTrue(Math.abs(c.y-other.y)<=1);}}
        }
    }
    @Test public void glassRailsStayOnBothSidesOutsidePavingAndDoors(){
        Map<Long,RoadCell> cells=new LinkedHashMap<>();for(int x=-40;x<=40;x++)for(int z=-2;z<=2;z++)cells.put(key(x,z),new RoadCell(x,z,64,5,1,0));
        Site entrance=new Site("a",-47,0,spec());
        List<WaterGuards.Rail> rails=WaterGuards.plan(ocean,cells,Collections.singletonList(entrance));Set<Long> keys=new HashSet<>();
        for(WaterGuards.Rail rail:rails){assertFalse(cells.containsKey(key(rail.x,rail.z)));assertTrue(entrance.distanceTo(rail.x,rail.z)>=2);assertEquals(64,rail.floor);keys.add(key(rail.x,rail.z));}
        for(int x=-30;x<=30;x++){assertTrue(keys.contains(key(x,-3)));assertTrue(keys.contains(key(x,3)));}
        Surface dry=new Surface(){public int height(int x,int z){return 63;}public boolean water(int x,int z){return false;}};
        assertTrue(WaterGuards.plan(dry,cells,Collections.emptyList()).isEmpty());
    }
    @Test public void shorelineEarthworksDoNotFillWholeLake(){
        Site a=new Site("a",-80,0,spec()),b=new Site("b",80,0,spec());a.floorY=b.floorY=64;
        Road road=road(ocean,a,b,Arrays.asList(a,b),1,1.5);Map<Long,RoadCell> cells=RoadGrade.reconcile(Collections.singletonList(road),ocean);
        Map<Long,RoadEarthworks.Column> edits=RoadEarthworks.plan(ocean,Collections.singletonList(road),Arrays.asList(a,b));
        for(RoadEarthworks.Column col:edits.values())assertTrue("Only paving may replace wet terrain",cells.containsKey(key(col.x,col.z)));
    }
    static class Blocks implements TreeCleaner.Grid {
        Map<Long,Material> blocks=new HashMap<>();int reads;
        void put(int x,int y,int z,Material m){blocks.put(TreeCleaner.key(x,y,z),m);}
        public Material get(int x,int y,int z){reads++;return blocks.getOrDefault(TreeCleaner.key(x,y,z),Material.AIR);}
        public void clear(int x,int y,int z){blocks.remove(TreeCleaner.key(x,y,z));}
    }
    @Test public void cuttingOnlyCrownFindsAndRemovesWholeTreeInSmallBatches(){
        Blocks grid=new Blocks();for(int y=65;y<=72;y++)grid.put(0,y,0,Material.OAK_LOG);
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)if(Math.abs(x)+Math.abs(z)<=4&&!(x==0&&z==0))grid.put(x,72,z,Material.OAK_LEAVES);
        grid.put(20,65,0,Material.BIRCH_LOG);grid.put(20,66,0,Material.BIRCH_LEAVES);grid.put(0,64,0,Material.DIRT);
        TreeCleaner cleaner=new TreeCleaner(grid);cleaner.seed(3,72,0,Material.OAK_LEAVES);int ticks=0;
        while(true){int reads=grid.reads;boolean done=cleaner.step(7);assertTrue("Bounded work each tick",grid.reads-reads<=7*27);if(done)break;assertTrue(++ticks<10000);}
        assertTrue(ticks>1);assertTrue(cleaner.removed>20);assertEquals(Material.DIRT,grid.get(0,64,0));
        assertEquals(Material.BIRCH_LOG,grid.get(20,65,0));assertEquals(Material.BIRCH_LEAVES,grid.get(20,66,0));
        assertFalse(grid.blocks.containsValue(Material.OAK_LOG));assertFalse(grid.blocks.containsValue(Material.OAK_LEAVES));
    }
    @Test public void disconnectedLeavesAndDiagonalBranchesAreRemoved(){
        Blocks grid=new Blocks();grid.put(-1,67,-1,Material.SPRUCE_LOG);grid.put(0,68,0,Material.SPRUCE_LOG);grid.put(1,69,1,Material.SPRUCE_LOG);
        for(int x=2;x<=7;x++)grid.put(x,69,1,Material.SPRUCE_LEAVES);
        grid.put(30,70,0,Material.OAK_LEAVES);
        TreeCleaner cleaner=new TreeCleaner(grid);cleaner.seed(-1,67,-1,Material.SPRUCE_LOG);cleaner.seed(30,70,0,Material.OAK_LEAVES);
        for(int i=0;!cleaner.step(100);i++)assertTrue(i<1000);assertTrue(grid.blocks.isEmpty());
    }
}
