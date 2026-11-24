package cn.mapreset;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static cn.mapreset.TerrainPlan.*;

public class TerrainPlanTest {
    StructureSpec spec(int w,int d){return new StructureSpec(w,d,11,4,5,null);}
    Surface flat=new Surface(){public int height(int x,int z){return 70;}public boolean water(int x,int z){return false;}};
    Map<String,StructureSpec> specs(){Map<String,StructureSpec> result=new LinkedHashMap<>();for(char id='a';id<='j';id++)result.put(""+id,spec(11+(id%3)*2,13));return result;}
    Site find(LayoutPlanner.Layout l,String id){return l.sites.stream().filter(s->s.id.equals(id)).findFirst().get();}
    @Test public void boundsIncludeWholeVariableFootprint(){assertFalse(new Site("f",-345,-345,spec(15,15)).inside());assertTrue(new Site("f",-330,-330,spec(15,15)).inside());}
    @Test public void layoutConstraintsAcrossSeeds(){
        for(long seed:new long[]{1,2,3,17,77,1234,7788,99871}){
            LayoutPlanner.Layout l=new LayoutPlanner(flat,specs(),seed,500,1.55).plan();assertEquals(10,l.sites.size());
            Site a=find(l,"a"),b=find(l,"b"),c=find(l,"c"),d=find(l,"d"),e=find(l,"e");
            assertTrue(Math.hypot(a.x,a.z)<=25);assertTrue(Math.hypot(b.x,b.z)>=180&&Math.hypot(b.x,b.z)<=250);
            assertEquals(-b.x,c.x);assertEquals(-b.z,c.z);assertEquals(-d.x,e.x);assertEquals(-d.z,e.z);
            assertTrue(Math.hypot(d.x,d.z)>=100&&Math.hypot(d.x,d.z)<=150);assertTrue(roadDistance(d,l.roads)>=30);assertTrue(roadDistance(e,l.roads)>=30);
            assertTrue(Math.hypot(find(l,"f").x-(-320),find(l,"f").z-(-320))<=15);assertTrue(Math.hypot(find(l,"g").x-(320),find(l,"g").z-(-320))<=15);assertTrue(Math.hypot(find(l,"h").x-(-320),find(l,"h").z-(320))<=15);assertTrue(Math.hypot(find(l,"i").x-(320),find(l,"i").z-(320))<=15);
            boolean near=false;for(Point anchor:Arrays.asList(new Point(325,0),new Point(-325,0),new Point(0,325),new Point(0,-325)))near|=l.j.distance(anchor)<=10;
            assertTrue(near);for(Site site:l.sites){assertTrue(site.inside());assertEquals(71,site.floorY);}
            Map<Long,RoadCell> cells=RoadGrade.reconcile(l.roads);
            for(RoadCell cell:cells.values())for(int[] n:RoadGrade.neighbors){RoadCell other=cells.get(key(cell.x+n[0],cell.z+n[1]));if(other!=null)assertTrue(Math.abs(other.y-cell.y)<=1);}
        }
    }
    @Test public void flatRoadIsGentlyCurvedAndDoorAligned(){
        Site a=new Site("a",0,0,spec(13,13)),b=new Site("b",210,50,spec(15,15));a.floorY=71;b.floorY=75;
        Road road=road(flat,a,b,Arrays.asList(a,b),45,1.55);assertFalse(road.curve.isEmpty());
        Point start=road.curve.get(0),end=road.curve.get(road.curve.size()-1);double max=0;
        for(Point q:road.curve){double d=Math.abs((q.x-start.x)*(end.z-start.z)-(q.z-start.z)*(end.x-start.x))/start.distance(end);max=Math.max(max,d);}
        assertTrue("must have a visible gentle bend",max>3);assertTrue(length(road.curve)<start.distance(end)*1.6);
        Map<Long,RoadCell> cells=RoadGrade.reconcile(Collections.singletonList(road));
        for(Point station:road.curve){int x=(int)Math.round(station.x),z=(int)Math.round(station.z);for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(!a.contains(x+dx,z+dz)&&!b.contains(x+dx,z+dz))assertTrue("continuous three-block core",cells.containsKey(key(x+dx,z+dz)));}
        for(Site s:Arrays.asList(a,b)){
            Point edge=s.outside(s.entrance(s==a?b:a),1);assertEquals(s.floorY,cells.get(key((int)edge.x,(int)edge.z)).y);
        }
        for(RoadCell cell:cells.values()){assertTrue(cell.width>=3&&cell.width<=5);assertFalse(a.contains(cell.x,cell.z));assertFalse(b.contains(cell.x,cell.z));}
        // The mathematical curve may have grid stair steps when rasterized, but no 90-degree curve knots.
        for(int i=8;i<road.curve.size()-8;i+=4){Point p=road.curve.get(i-4),q=road.curve.get(i),r=road.curve.get(i+4);double dot=(q.x-p.x)*(r.x-q.x)+(q.z-p.z)*(r.z-q.z);assertTrue(dot>=-.1);}
    }
    @Test public void mountainDetourIsCappedAndPassCutsTerrain(){
        Surface ridge=new Surface(){public int height(int x,int z){return Math.abs(x)<20&&Math.abs(z)<290?175:70;}public boolean water(int x,int z){return false;}};
        Site a=new Site("a",-130,0,spec(13,13)),b=new Site("b",130,0,spec(13,13));a.floorY=b.floorY=71;
        Road r=road(ridge,a,b,Arrays.asList(a,b),5,1.4);assertTrue(r.passCut);
        RoadGrade.reconcile(Collections.singletonList(r));Map<Long,RoadEarthworks.Column> edits=RoadEarthworks.plan(ridge,Collections.singletonList(r),Arrays.asList(a,b));
        assertTrue(edits.values().stream().anyMatch(c->Math.abs(c.x)<20&&c.surface<100));
        for(Site s:Arrays.asList(a,b))for(RoadEarthworks.Column col:edits.values())assertFalse(s.contains(col.x,col.z));
    }
    @Test public void gradesPinEntrancesAndLimitSteps(){
        int[] y=grade(new int[]{70,90,120,80,40,40,60},70,74);assertEquals(70,y[0]);assertEquals(74,y[6]);for(int i=1;i<y.length;i++)assertTrue(Math.abs(y[i]-y[i-1])<=1);
    }
    @Test(expected=IllegalArgumentException.class)public void tooShortGradeFails(){grade(new int[]{1,100},1,100);}
    @Test(expected=IllegalArgumentException.class)public void invalidFirstFloorFails(){new StructureSpec(13,13,11,2,5,null);}
    @Test public void structureSizeChangesAreAdapted(){
        Map<String,StructureSpec> specs=specs();specs.put("b",new StructureSpec(30,22,19,7,5,null));specs.put("c",new StructureSpec(26,20,17,6,5,null));
        LayoutPlanner.Layout layout=new LayoutPlanner(flat,specs,515,1000,1.55).plan();assertEquals(30,find(layout,"b").spec.width);assertEquals(7,find(layout,"b").spec.firstFloor);assertEquals(71,find(layout,"c").floorY);
    }
    @Test public void deterministicTerrainHasOnlyAllowedOreCodes(){
        for(long seed:new long[]{0,1234,-551}){
            TerrainModel m=new TerrainModel(seed),same=new TerrainModel(seed);
            for(int x=-350;x<350;x+=7)for(int z=-350;z<350;z+=11){int h=m.height(x,z);assertTrue(h>=1&&h<=245);assertEquals(h,same.height(x,z));for(int y=1;y<h-3;y+=3)assertTrue(m.ore(x,y,z)>=0&&m.ore(x,y,z)<=3);}
        }
    }
    @Test public void naturalTerrainLayoutsAndJAreValid(){
        for(long seed=20;seed<44;seed++){
            TerrainModel model=new TerrainModel(seed);Surface natural=new Surface(){public int height(int x,int z){return model.height(x,z);}public boolean water(int x,int z){return false;}};
            LayoutPlanner.Layout layout=new LayoutPlanner(natural,specs(),seed,600,1.55).plan();
            for(Site site:layout.sites){long sum=0;for(int x=site.minX();x<=site.maxX();x++)for(int z=site.minZ();z<=site.maxZ();z++)sum+=model.height(x,z);assertEquals((int)Math.round(sum/(double)(site.spec.width*site.spec.depth))+1,site.floorY);}
            Site b=find(layout,"b"),c=find(layout,"c");double chosen=Math.min(b.distanceTo(layout.j.x,layout.j.z),c.distanceTo(layout.j.x,layout.j.z));
            for(int[] anchor:new int[][]{{325,0},{-325,0},{0,325},{0,-325}})for(int dx=-10;dx<=10;dx++)for(int dz=-10;dz<=10;dz++)if(dx*dx+dz*dz<=100&&Math.hypot(layout.j.x-anchor[0],layout.j.z-anchor[1])<=10)
                assertTrue(chosen+.001>=Math.min(b.distanceTo(anchor[0]+dx,anchor[1]+dz),c.distanceTo(anchor[0]+dx,anchor[1]+dz)));
            Map<Long,RoadCell> cells=RoadGrade.reconcile(layout.roads);for(RoadCell cell:cells.values())for(int[] offset:RoadGrade.neighbors){RoadCell next=cells.get(key(cell.x+offset[0],cell.z+offset[1]));if(next!=null)assertTrue(Math.abs(next.y-cell.y)<=1);}
        }
    }
}
