package cn.mapreset;

import java.util.*;
import static cn.mapreset.TerrainPlan.*;

/** Reconciles junctions and guarantees <=1-block height difference between adjacent paved cells. */
public final class RoadGrade {
    static int[][] neighbors={{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
    public static Map<Long,RoadCell> reconcile(List<Road> roads){
        return reconcile(roads,null);
    }
    public static Map<Long,RoadCell> reconcile(List<Road> roads,Surface surface){
        Map<Long,RoadCell> cells=new LinkedHashMap<>();Map<Long,Integer> anchors=new HashMap<>();
        for(Road road:roads){
            for(Map.Entry<Long,RoadCell> entry:road.cells.entrySet()){
                RoadCell existing=cells.get(entry.getKey());if(existing==null){existing=entry.getValue();cells.put(entry.getKey(),existing);}entry.setValue(existing);
            }
        }
        for(Road road:roads)for(Site site:new Site[]{road.a,road.b}){
            Site other=site==road.a?road.b:road.a;StructureSpec.Entry door=site.entrance(other);
            for(int distance=1;distance<=5;distance++){
                Point q=site.outside(door,distance);for(int offset=-2;offset<=2;offset++){
                    int x=(int)q.x+((door.side.equals("north")||door.side.equals("south"))?offset:0),z=(int)q.z+((door.side.equals("east")||door.side.equals("west"))?offset:0);
                    long key=key(x,z);if(!cells.containsKey(key))continue;
                    Integer old=anchors.put(key,site.floorY);if(old!=null&&old!=site.floorY)throw new IllegalStateException("建筑入口高度约束冲突");
                }
            }
        }
        Map<Long,Integer> lowerSeeds=new HashMap<>(anchors);
        if(surface!=null)for(RoadCell cell:cells.values())if(surface.waterLevel(cell.x,cell.z)>=0)lowerSeeds.merge(key(cell.x,cell.z),surface.waterLevel(cell.x,cell.z)+1,Math::max);
        Map<Long,Integer> upper=propagate(cells,anchors,false),negative=propagate(cells,lowerSeeds,true);
        Map<Long,Integer> initial=new HashMap<>();
        for(Map.Entry<Long,RoadCell> entry:cells.entrySet()){
            int max=upper.getOrDefault(entry.getKey(),250),min=-negative.getOrDefault(entry.getKey(),-1);
            if(min>max)throw new IllegalStateException("路口无法在不改变入口高度时衔接");
            initial.put(entry.getKey(),Math.max(min,Math.min(max,entry.getValue().y)));
        }
        Map<Long,Integer> heights=propagate(cells,initial,false);
        for(Map.Entry<Long,RoadCell> entry:cells.entrySet()){
            RoadCell cell=entry.getValue();int y=heights.get(entry.getKey());
            if(cell.y!=y||anchors.containsKey(entry.getKey()))cell.slab=false;
            cell.y=y;
        }
        for(RoadCell cell:cells.values()){
            int dx=Math.abs(cell.tx)>=Math.abs(cell.tz)?(cell.tx>=0?1:-1):0;
            int dz=dx==0?(cell.tz>=0?1:-1):0;
            RoadCell back=cells.get(key(cell.x-dx,cell.z-dz)),front=cells.get(key(cell.x+dx,cell.z+dz));
            cell.stair=false;
            // Half-height terraces cover oblique risers. Stairs join the higher full row to the lower terrace.
            if(!cell.slab){
                double backTop=back==null?Double.NaN:back.y+(back.slab?.5:1);
                double frontTop=front==null?Double.NaN:front.y+(front.slab?.5:1);
                if(backTop>=cell.y&&backTop<cell.y+1){cell.stair=true;cell.tx=dx;cell.tz=dz;}
                else if(frontTop>=cell.y&&frontTop<cell.y+1){cell.stair=true;cell.tx=-dx;cell.tz=-dz;}
            }
        }

        return cells;
    }
    static Map<Long,Integer> propagate(Map<Long,RoadCell> cells,Map<Long,Integer> seeds,boolean negative){
        Map<Long,Integer> result=new HashMap<>();PriorityQueue<long[]> queue=new PriorityQueue<>(Comparator.comparingLong(a->a[1]));
        for(Map.Entry<Long,Integer> seed:seeds.entrySet()){int y=negative?-seed.getValue():seed.getValue();result.put(seed.getKey(),y);queue.add(new long[]{seed.getKey(),y});}
        while(!queue.isEmpty()){
            long[] item=queue.poll();long key=item[0];int value=(int)item[1];if(value!=result.get(key))continue;
            RoadCell cell=cells.get(key);if(cell==null)continue;
            for(int[] n:neighbors){long next=key(cell.x+n[0],cell.z+n[1]);if(!cells.containsKey(next))continue;int d=value+1;
                if(d<result.getOrDefault(next,Integer.MAX_VALUE)){result.put(next,d);queue.add(new long[]{next,d});}
            }
        }return result;
    }
}
