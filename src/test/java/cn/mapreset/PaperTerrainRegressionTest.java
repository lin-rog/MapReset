package cn.mapreset;
import org.junit.Test;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import static org.junit.Assert.*;
import static cn.mapreset.TerrainPlan.*;

/** Height/water data exported from a real Paper 794 world, seed 12345. */
public class PaperTerrainRegressionTest {
    @Test public void realMountainApproachDoesNotLoopAroundBuilding()throws Exception{
        int[][] heights=new int[700][700],water=new int[700][700];
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new GZIPInputStream(getClass().getResourceAsStream("/paper-12345-terrain.dat.gz"))))){
            for(int x=0;x<700;x++)for(int z=0;z<700;z++){heights[x][z]=in.readInt();water[x][z]=in.readInt();}
        }
        Surface surface=new Surface(){
            public int height(int x,int z){return heights[Math.max(0,Math.min(699,x+350))][Math.max(0,Math.min(699,z+350))];}
            public boolean water(int x,int z){return waterLevel(x,z)>=0;}
            public int waterLevel(int x,int z){return x<MIN||x>=MAX||z<MIN||z>=MAX?-1:water[x+350][z+350];}
        };
        Map<String,StructureSpec> specs=new LinkedHashMap<>();
        try(BufferedReader in=new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/paper-12345-specs.csv"),"UTF-8"))){
            for(String line;(line=in.readLine())!=null;){String[] a=line.split(",");specs.put(a[0],new StructureSpec(Integer.parseInt(a[1]),Integer.parseInt(a[2]),Integer.parseInt(a[3]),Integer.parseInt(a[4]),Integer.parseInt(a[5]),null));}
        }
        LayoutPlanner.Layout layout=new LayoutPlanner(surface,specs,12345,1200,2).plan();RoadGrade.reconcile(layout.roads,surface);
        for(Road road:layout.roads){
            Point first=road.curve.get(0),last=road.curve.get(road.curve.size()-1);double dx=last.x-first.x,dz=last.z-first.z,length=first.distance(last);
            for(int i=1;i<road.curve.size();i++){
                Point p=road.curve.get(i-1),q=road.curve.get(i);double progress=((q.x-p.x)*dx+(q.z-p.z)*dz)/length;
                assertTrue("Route must not turn back around the entrance: "+road.b.id+" progress="+progress,progress>=-.02);
            }
        }
    }
}
