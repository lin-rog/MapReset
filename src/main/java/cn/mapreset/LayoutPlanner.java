package cn.mapreset;

import java.util.*;
import static cn.mapreset.TerrainPlan.*;

/** Thread-safe when supplied an immutable terrain snapshot and immutable specs. */
public final class LayoutPlanner {
    public static final class Layout {
        public final List<Site> sites;public final List<Road> roads;public final Point j;public final int jY;
        Layout(List<Site> sites,List<Road> roads,Point j,int jY){this.sites=sites;this.roads=roads;this.j=j;this.jY=jY;}
    }
    final Surface terrain;final Map<String,StructureSpec> specs;final long seed;final int tries;final double maxDetour;
    Random random;List<Site> sites;List<Road> roads;
    public LayoutPlanner(Surface terrain,Map<String,StructureSpec> specs,long seed,int tries,double maxDetour){
        this.terrain=terrain;this.specs=specs;this.seed=seed;this.tries=Math.max(100,tries);this.maxDetour=Math.max(1.15,Math.min(2,maxDetour));
    }
    public Layout plan(){
        RuntimeException last=null;
        for(int retry=0;retry<8;retry++)try{
            random=new Random(seed+retry*10391L);sites=new ArrayList<>();roads=new ArrayList<>();
            choose("a",null,0,25,0,0,false);
            choose("b","c",180,250,0,0,false);
            choose("f",null,0,15,-320,-320,true);choose("g",null,0,15,320,-320,true);
            choose("h",null,0,15,-320,320,true);choose("i",null,0,15,320,320,true);
            Site a=find("a"),b=find("b"),c=find("c");
            roads.add(TerrainPlan.road(terrain,a,b,sites,seed,maxDetour));roads.add(TerrainPlan.road(terrain,a,c,sites,seed^777,maxDetour));
            choose("d","e",100,150,0,0,false);
            Point j=selectJ(b,c);Site js=site("j",(int)j.x,(int)j.z);average(js);sites.add(js);validate();return new Layout(sites,roads,j,js.floorY+1);
        }catch(RuntimeException e){last=e;}
        throw new IllegalStateException("8次规划均无法满足约束；请检查建筑尺寸和入口: "+(last==null?"":last.getMessage()),last);
    }
    Site find(String id){for(Site s:sites)if(s.id.equals(id))return s;throw new IllegalArgumentException(id);}
    Site site(String id,int x,int z){return new Site(id,x,z,Objects.requireNonNull(specs.get(id),id));}
    boolean free(Site s){
        if(!s.inside())return false;for(Site other:sites)if(s.overlaps(other,8))return false;
        return !(s.id.equals("d")||s.id.equals("e"))||roadDistance(s,roads)>=30.5;
    }
    double score(Site s){
        double sum=0,sq=0,water=0;int count=0,min=255,max=0;
        // Include a 24-block neighborhood to prefer buildings BESIDE plains, not just a flat footprint.
        for(int x=Math.max(MIN,s.minX()-24);x<=Math.min(MAX-1,s.maxX()+24);x+=6)for(int z=Math.max(MIN,s.minZ()-24);z<=Math.min(MAX-1,s.maxZ()+24);z+=6){
            int h=terrain.travelHeight(x,z);sum+=h;sq+=h*h;min=Math.min(min,h);max=Math.max(max,h);if(terrain.water(x,z))water++;count++;
        }
        return sq/count-Math.pow(sum/count,2)+(max-min)*.25+water*10;
    }
    void average(Site s){
        long sum=0;int highestWater=-1;
        for(int x=s.minX();x<=s.maxX();x++)for(int z=s.minZ();z<=s.maxZ();z++)sum+=terrain.travelHeight(x,z);
        // Include the doorway apron so a shoreline structure cannot have an underwater entrance.
        for(int x=Math.max(MIN,s.minX()-10);x<=Math.min(MAX-1,s.maxX()+10);x++)for(int z=Math.max(MIN,s.minZ()-10);z<=Math.min(MAX-1,s.maxZ()+10);z++)highestWater=Math.max(highestWater,terrain.waterLevel(x,z));
        s.floorY=Math.max((int)Math.round(sum/(double)(s.spec.width*s.spec.depth))+1,highestWater+1);
        if(s.floorY-s.spec.firstFloor<1||s.floorY-s.spec.firstFloor+s.spec.height>=255)throw new IllegalStateException(s.id+" 结构高度超出地图");
    }
    void choose(String id,String mirror,int lo,int hi,int cx,int cz,boolean corner){
        Site best=null,otherBest=null;double value=Double.POSITIVE_INFINITY;
        // Cheap preliminary geometry feasibility test avoids endless searches for oversized corner structures.
        
        for(int attempt=0;attempt<tries;attempt++){
            double angle=random.nextDouble()*Math.PI*2,r=Math.sqrt(lo*lo+random.nextDouble()*(hi*hi-lo*lo));
            int x=cx+(int)Math.round(Math.cos(angle)*r),z=cz+(int)Math.round(Math.sin(angle)*r);
            double radius=Math.hypot(x-cx,z-cz);if(radius<lo||radius>hi)continue;
            Site candidate=site(id,x,z);if(!free(candidate))continue;
            Site opposite=mirror==null?null:site(mirror,-x,-z);
            if(opposite!=null&&(!free(opposite)||candidate.overlaps(opposite,8)))continue;
            double cost=score(candidate)+(opposite==null?0:score(opposite));
            if(cost<value){value=cost;best=candidate;otherBest=opposite;}
        }
        if(best==null)throw new IllegalStateException(id+" 没有可用地表位置（尺寸、间隔或道路约束）");
        average(best);sites.add(best);if(otherBest!=null){average(otherBest);sites.add(otherBest);}
    }
    Point selectJ(Site b,Site c){
        int[][] anchors={{325,0},{-325,0},{0,325},{0,-325}};Point best=null;double value=-1;
        int[] anchor=anchors[random.nextInt(anchors.length)];for(int dx=-10;dx<=10;dx++)for(int dz=-10;dz<=10;dz++){
            if(dx*dx+dz*dz>100)continue;int x=anchor[0]+dx,z=anchor[1]+dz;
            if(!free(site("j",x,z)))continue;double score=Math.min(b.distanceTo(x,z),c.distanceTo(x,z));
            if(score>value+.0001||(Math.abs(score-value)<.0001&&random.nextBoolean())){value=score;best=new Point(x,z);}
        }return Objects.requireNonNull(best);
    }
    void validate(){
        if(sites.size()!=10)throw new IllegalStateException("建筑数量错误");
        for(Site s:sites){if(!s.inside())throw new IllegalStateException("建筑越界");if((s.id.equals("d")||s.id.equals("e"))&&roadDistance(s,roads)<30)throw new IllegalStateException("D/E距离道路不足30格");}
        if(find("b").x!=-find("c").x||find("b").z!=-find("c").z||find("d").x!=-find("e").x||find("d").z!=-find("e").z)throw new IllegalStateException("对称坐标不正确");
    }
}
