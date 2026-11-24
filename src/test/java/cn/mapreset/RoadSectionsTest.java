package cn.mapreset;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static cn.mapreset.TerrainPlan.*;

public class RoadSectionsTest {
    StructureSpec spec(){return new StructureSpec(13,13,11,4,5,null);}
    @Test public void straightCrossSectionsAreLevelAndStairsOnlyOnRiseRows(){
        Surface terrain=new Surface(){public int height(int x,int z){return 70+(int)Math.round(x*.18)+((z%3+3)%3);}public boolean water(int x,int z){return false;}};
        Site a=new Site("a",0,0,spec()),b=new Site("b",180,0,spec());a.floorY=71;b.floorY=103;
        List<Point> path=new ArrayList<>();for(int x=7;x<=173;x++)path.add(new Point(x,0));
        Map<Long,RoadCell> cells=RoadSections.pave(terrain,a,b,Arrays.asList(a,b),path,false);
        Road r=new Road(a,b,path,cells,false);RoadGrade.reconcile(Collections.singletonList(r),terrain);
        for(int x=8;x<173;x++){
            int y=cells.get(key(x,0)).y;
            for(int z=-2;z<=2;z++){RoadCell c=cells.get(key(x,z));assertEquals("Flat cross section",y,c.y);if(c.stair){assertEquals(1,c.tx,0);assertEquals(0,c.tz,0);RoadCell behind=cells.get(key(x-1,z));assertTrue(behind.y+(behind.slab?.5:1)<c.y+1);}}
        }
    }
    @Test public void diagonalSlopeCanBeWalkedWithoutOneBlockVerticalEdges(){
        Surface terrain=new Surface(){public int height(int x,int z){return 70+(int)Math.round(x*.15+z*.1);}public boolean water(int x,int z){return false;}};
        Site a=new Site("a",0,0,spec()),b=new Site("b",180,70,spec());a.floorY=71;b.floorY=105;
        Road road=road(terrain,a,b,Arrays.asList(a,b),42,1.6);Map<Long,RoadCell> cells=RoadGrade.reconcile(Collections.singletonList(road),terrain);
        int cliffs=0;
        for(int lane=-1;lane<=1;lane++){
            double previous=Double.NaN;
            for(int i=1;i<road.curve.size()-1;i++){
                Point a1=road.curve.get(i-1),b1=road.curve.get(i),c=road.curve.get(i+1);double dx=c.x-a1.x,dz=c.z-a1.z,len=Math.hypot(dx,dz);
                for(int j=0;j<4;j++){
                    double t=j/4d,x=a1.x+(b1.x-a1.x)*t-dz/len*lane+.5,z=a1.z+(b1.z-a1.z)*t+dx/len*lane+.5;
                    RoadCell cell=cells.get(key((int)Math.floor(x),(int)Math.floor(z)));assertNotNull(cell);
                    double h=cell.y+(cell.slab?.5:1);
                    if(cell.stair){boolean high=cell.tx>0?x-Math.floor(x)>=.5:cell.tx<0?x-Math.floor(x)<=.5:cell.tz>0?z-Math.floor(z)>=.5:z-Math.floor(z)<=.5;h=cell.y+(high?1:.5);}
                    if(!Double.isNaN(previous)&&Math.abs(h-previous)>.6)cliffs++;previous=h;
                }
            }
        }
        assertEquals("Three walking lanes must not contain full-block risers",0,cliffs);
    }
}
