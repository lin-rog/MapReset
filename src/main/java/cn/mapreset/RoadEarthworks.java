package cn.mapreset;

import java.util.*;
import static cn.mapreset.TerrainPlan.*;

/** One nearest-road cross-section per terrain column; overlapping samples never repeatedly cut it down. */
public final class RoadEarthworks {
    public static final class Column {public final int x,z;public int surface;Column(int x,int z,int y){this.x=x;this.z=z;surface=y;}}
    public static Map<Long,Column> plan(Surface terrain,List<Road> roads,List<Site> sites){
        Map<Long,Column> edits=new LinkedHashMap<>();Map<Long,Double> distances=new HashMap<>(),influences=new HashMap<>();
        for(Road road:roads)for(int i=0;i<road.curve.size();i+=3){
            Point q=road.curve.get(i);RoadCell nearest=null;double close=Double.POSITIVE_INFINITY;
            for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){
                RoadCell c=road.cells.get(key((int)Math.round(q.x)+dx,(int)Math.round(q.z)+dz));
                if(c!=null){double d=Math.hypot(c.x-q.x,c.z-q.z);if(d<close){close=d;nearest=c;}}
            }
            if(nearest==null)continue;int floor=nearest.y-1;
            int difference=Math.abs(terrain.height(nearest.x,nearest.z)-floor);
            int radius=Math.min(48,Math.max(10,10+(int)Math.ceil(difference*.65)));
            for(int x=Math.max(MIN,nearest.x-radius);x<=Math.min(MAX-1,nearest.x+radius);x++)for(int z=Math.max(MIN,nearest.z-radius);z<=Math.min(MAX-1,nearest.z+radius);z++){
                if(terrain.water(x,z))continue;double distance=Math.hypot(x-q.x,z-q.z);if(distance>radius)continue;
                boolean reserved=false;for(Site s:sites)if(s.contains(x,z)){reserved=true;break;}if(reserved)continue;
                long key=key(x,z);if(distance>=distances.getOrDefault(key,Double.POSITIVE_INFINITY))continue;distances.put(key,distance);
                double t=Math.max(0,Math.min(1,(distance-3)/(radius-3.0)));double strength=1-t*t*(3-2*t);
                int original=terrain.height(x,z),target=(int)Math.round(original+(floor-original)*strength);
                influences.put(key,strength);
                if(target!=original)edits.put(key,new Column(x,z,Math.max(4,Math.min(245,target))));else edits.remove(key);
            }
        }
        for(Site site:sites)for(int x=Math.max(MIN,site.minX()-24);x<=Math.min(MAX-1,site.maxX()+24);x++)for(int z=Math.max(MIN,site.minZ()-24);z<=Math.min(MAX-1,site.maxZ()+24);z++){
            double d=site.distanceTo(x,z);if(d==0||d>24||terrain.water(x,z))continue;
            boolean other=false;for(Site s:sites)if(s.contains(x,z)){other=true;break;}if(other)continue;
            double t=Math.min(1,Math.max(0,(d-5)/19)),strength=1-t*t*(3-2*t);long key=key(x,z);
            // Near the road its cross-section wins; a building cannot create a wall at a road edge.
            if(strength<=influences.getOrDefault(key,0d))continue;
            int original=terrain.height(x,z),target=(int)Math.round(original+(site.floorY-1-original)*strength);
            if(target!=original)edits.put(key,new Column(x,z,target));else edits.remove(key);
            influences.put(key,strength);
        }
        for(Road road:roads)for(RoadCell cell:road.cells.values())edits.put(key(cell.x,cell.z),new Column(cell.x,cell.z,cell.y-1));
        return edits;
    }
}
