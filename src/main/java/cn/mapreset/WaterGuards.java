package cn.mapreset;

import java.util.*;
import static cn.mapreset.TerrainPlan.*;

/** Open-top, two-block glass rails outside the actual paving, including bends and junctions. */
final class WaterGuards {
    static final class Rail {
        final int x,z;int floor;
        Rail(int x,int z,int floor){this.x=x;this.z=z;this.floor=floor;}
    }
    static List<Rail> plan(Surface surface,Map<Long,RoadCell> paving,List<Site> sites){
        Map<Long,Rail> rails=new LinkedHashMap<>();
        for(RoadCell cell:paving.values()){
            boolean near=false;
            for(int dx=-5;dx<=5&&!near;dx++)for(int dz=-5;dz<=5;dz++)if(dx*dx+dz*dz<=25&&surface.water(cell.x+dx,cell.z+dz)){near=true;break;}
            if(!near)continue;
            for(int[] n:RoadGrade.neighbors){
                int x=cell.x+n[0],z=cell.z+n[1];long key=key(x,z);
                if(x<MIN||x>=MAX||z<MIN||z>=MAX||paving.containsKey(key))continue;
                boolean door=false;for(Site site:sites)if(site.distanceTo(x,z)<2){door=true;break;}if(door)continue;
                Rail old=rails.get(key);if(old==null)rails.put(key,new Rail(x,z,cell.y));else old.floor=Math.max(old.floor,cell.y);
            }
        }
        return new ArrayList<>(rails.values());
    }
}
