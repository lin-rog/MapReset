package cn.mapreset;

import org.bukkit.Material;
import java.util.*;

/** Incremental cleanup before earthworks: find trunks through cut foliage, then remove whole trunks/crowns. */
final class TreeCleaner {
    interface Grid {Material get(int x,int y,int z);void clear(int x,int y,int z);}
    private final Grid grid;
    private final Map<Long,Integer> discoveryDepth=new HashMap<>(),leafDepth=new HashMap<>();
    private final Set<Long> woodSeen=new HashSet<>();
    private final ArrayDeque<long[]> discovery=new ArrayDeque<>();
    private final PriorityQueue<long[]> leaves=new PriorityQueue<>((a,b)->Long.compare(b[1],a[1]));
    private final ArrayDeque<Long> wood=new ArrayDeque<>();
    private int phase;int removed;
    TreeCleaner(Grid grid){this.grid=grid;}
    static boolean log(Material material){String s=material.name();return s.endsWith("_LOG")||s.endsWith("_WOOD");}
    static boolean leaf(Material material){return material.name().endsWith("_LEAVES");}
    static long key(int x,int y,int z){return ((long)(x+512)<<20)|((long)(z+512)<<8)|y;}
    static int x(long key){return (int)(key>>20)-512;}
    static int z(long key){return (int)((key>>8)&4095)-512;}
    static int y(long key){return (int)(key&255);}
    static boolean inside(int x,int y,int z){return x>=-382&&x<=381&&z>=-382&&z<=381&&y>=1&&y<=255;}
    void seed(int x,int y,int z,Material material){
        if(phase!=0)throw new IllegalStateException("Tree seeds must precede editing");
        if(log(material))addWood(x,y,z);else if(leaf(material))enqueue(discovery,discoveryDepth,x,y,z,6);
    }
    private void addWood(int x,int y,int z){if(inside(x,y,z)){long key=key(x,y,z);if(woodSeen.add(key))wood.add(key);}}
    private void enqueue(Queue<long[]> queue,Map<Long,Integer> depths,int x,int y,int z,int depth){
        if(!inside(x,y,z))return;long key=key(x,y,z);
        if(depth>depths.getOrDefault(key,-1)){depths.put(key,depth);queue.add(new long[]{key,depth});}
    }
    boolean step(int budget){
        for(int i=0;i<Math.max(1,budget);i++){
            if(phase==0){
                if(discovery.isEmpty()){phase=1;i--;continue;}
                long[] item=discovery.remove();int x=x(item[0]),y=y(item[0]),z=z(item[0]),depth=(int)item[1];
                if(depth!=discoveryDepth.get(item[0]))continue;
                Material type=grid.get(x,y,z);if(log(type)){addWood(x,y,z);continue;}if(!leaf(type))continue;
                // Clear detached foliage too, even when no trunk can be reached.
                enqueue(leaves,leafDepth,x,y,z,0);
                if(depth>0)for(int[] n:FACES)enqueue(discovery,discoveryDepth,x+n[0],y+n[1],z+n[2],depth-1);
            }else if(phase==1){
                if(wood.isEmpty()){phase=2;i--;continue;}
                long key=wood.remove();int x=x(key),y=y(key),z=z(key);if(!log(grid.get(x,y,z)))continue;
                for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)for(int dz=-1;dz<=1;dz++){
                    if(dx==0&&dy==0&&dz==0||!inside(x+dx,y+dy,z+dz))continue;
                    if(log(grid.get(x+dx,y+dy,z+dz)))addWood(x+dx,y+dy,z+dz);
                }
                for(int[] n:FACES)enqueue(leaves,leafDepth,x+n[0],y+n[1],z+n[2],6);
                grid.clear(x,y,z);removed++;
            }else{
                if(leaves.isEmpty())return true;
                long[] item=leaves.remove();int x=x(item[0]),y=y(item[0]),z=z(item[0]),depth=(int)item[1];
                if(depth!=leafDepth.get(item[0])||!leaf(grid.get(x,y,z)))continue;
                if(depth>0)for(int[] n:FACES)enqueue(leaves,leafDepth,x+n[0],y+n[1],z+n[2],depth-1);
                grid.clear(x,y,z);removed++;
            }
            if(woodSeen.size()+discoveryDepth.size()+leafDepth.size()>2000000)throw new IllegalStateException("Tree cleanup exceeds safe budget; map is not ready");
        }
        return phase==2&&leaves.isEmpty();
    }
    private static final int[][] FACES={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
}
