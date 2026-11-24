package cn.mapreset;

import java.util.*;

/** Pure map planning. All world-plane coordinates are X/Z; +350 edge is exclusive. */
public final class TerrainPlan {
    public static final int MIN=-350,MAX=350;
    public static final class Point {
        public final double x,z;
        public Point(double x,double z){this.x=x;this.z=z;}
        public double distance(Point b){return Math.hypot(x-b.x,z-b.z);}
    }
    public interface Surface {
        int height(int x,int z);boolean water(int x,int z);
        default int waterLevel(int x,int z){return water(x,z)?height(x,z):-1;}
        default int travelHeight(int x,int z){return Math.max(height(x,z),waterLevel(x,z));}
    }
    public static final class Site {
        public final String id;public final int x,z;public final StructureSpec spec;public int floorY;
        public Site(String id,int x,int z,StructureSpec spec){this.id=id;this.x=x;this.z=z;this.spec=spec;}
        public int minX(){return x-spec.width/2;}public int minZ(){return z-spec.depth/2;}
        public int maxX(){return minX()+spec.width-1;}public int maxZ(){return minZ()+spec.depth-1;}
        public boolean inside(){return minX()>=MIN&&minZ()>=MIN&&maxX()<MAX&&maxZ()<MAX;}
        public boolean contains(int px,int pz){return px>=minX()&&px<=maxX()&&pz>=minZ()&&pz<=maxZ();}
        public boolean overlaps(Site b,int pad){return minX()-pad<=b.maxX()&&maxX()+pad>=b.minX()&&minZ()-pad<=b.maxZ()&&maxZ()+pad>=b.minZ();}
        public double distanceTo(double px,double pz){return Math.hypot(Math.max(Math.max(minX()-px,px-maxX()),0),Math.max(Math.max(minZ()-pz,pz-maxZ()),0));}
        public boolean cornerFits(int cx,int cz,int radius){for(int xx:new int[]{minX(),maxX()})for(int zz:new int[]{minZ(),maxZ()})if(Math.hypot(xx-cx,zz-cz)>radius)return false;return inside();}
        public StructureSpec.Entry entrance(Site toward){
            StructureSpec.Entry best=null;double dist=Double.POSITIVE_INFINITY;
            for(StructureSpec.Entry e:spec.entrances.values()){
                double d=Math.hypot(minX()+e.x-toward.x,minZ()+e.z-toward.z);if(d<dist){dist=d;best=e;}
            }return Objects.requireNonNull(best,"建筑没有入口");
        }
        public Point outside(StructureSpec.Entry e,int offset){
            int dx=e.side.equals("east")?offset:e.side.equals("west")?-offset:0;
            int dz=e.side.equals("south")?offset:e.side.equals("north")?-offset:0;
            return new Point(minX()+e.x+dx,minZ()+e.z+dz);
        }
    }
    public static final class RoadCell {
        public final int x,z,width;public int y;public double tx,tz;public boolean stair,slab;
        RoadCell(int x,int z,int y,int width,double tx,double tz){this.x=x;this.z=z;this.y=y;this.width=width;this.tx=tx;this.tz=tz;}
    }
    public static final class Road {
        public final Site a,b;public final List<Point> curve;public final Map<Long,RoadCell> cells;public final boolean passCut;
        public Road(Site a,Site b,List<Point> curve,Map<Long,RoadCell> cells,boolean passCut){this.a=a;this.b=b;this.curve=curve;this.cells=cells;this.passCut=passCut;}
    }
    public static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
    public static double length(List<Point> p){double d=0;for(int i=1;i<p.size();i++)d+=p.get(i).distance(p.get(i-1));return d;}
    public static int[] grade(int[] raw,int first,int last){
        if(raw.length<2||Math.abs(last-first)>raw.length-1)throw new IllegalArgumentException("路长不足以衔接入口高度");
        int[] y=raw.clone();y[0]=first;
        for(int i=1;i<y.length;i++)y[i]=clamp(y[i],y[i-1]-1,y[i-1]+1);
        y[y.length-1]=last;
        for(int i=y.length-2;i>=0;i--)y[i]=clamp(y[i],y[i+1]-1,y[i+1]+1);
        if(y[0]!=first)throw new IllegalStateException("道路入口高度不一致");return y;
    }
    private static int clamp(int a,int lo,int hi){return Math.max(lo,Math.min(hi,a));}
    private static boolean blocked(double x,double z,List<Site> sites,double pad){
        if(x<MIN+pad||z<MIN+pad||x>=MAX-pad||z>=MAX-pad)return true;
        for(Site s:sites)if(s.distanceTo(x,z)<=pad)return true;return false;
    }
    private static List<Point> astar(Surface surface,Point from,Point to,List<Site> sites,double slope,double corridor){
        final int step=2,n=351,headings=8,total=n*n*headings;
        final int[][] moves={{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1},{0,-1},{1,-1}};
        double[] dist=new double[total];Arrays.fill(dist,Double.POSITIVE_INFINITY);
        int[] prev=new int[total];Arrays.fill(prev,-1);boolean[] closed=new boolean[total];
        int sx=clamp((int)Math.round((from.x-MIN)/step),0,n-1),sz=clamp((int)Math.round((from.z-MIN)/step),0,n-1);
        int ex=clamp((int)Math.round((to.x-MIN)/step),0,n-1),ez=clamp((int)Math.round((to.z-MIN)/step),0,n-1);
        PriorityQueue<double[]> queue=new PriorityQueue<>(Comparator.comparingDouble(a->a[1]));
        for(int h=0;h<headings;h++){int start=(sx*n+sz)*headings+h;dist[start]=0;queue.add(new double[]{start,0});}
        double dx=to.x-from.x,dz=to.z-from.z,straight=Math.max(1,Math.hypot(dx,dz));int end=-1;
        while(!queue.isEmpty()){
            int u=(int)queue.poll()[0];if(closed[u])continue;closed[u]=true;
            int cell=u/headings,heading=u%headings,ux=cell/n,uz=cell%n,x=MIN+ux*step,z=MIN+uz*step;
            if(ux==ex&&uz==ez){end=u;break;}
            for(int h=0;h<headings;h++){
                int ax=moves[h][0],az=moves[h][1],vx=ux+ax,vz=uz+az;if(vx<0||vz<0||vx>=n||vz>=n)continue;
                // No longitudinal backtracking: a mountain detour must not loop around a doorway.
                if(ax*dx+az*dz<-.0001)continue;
                int xx=MIN+vx*step,zz=MIN+vz*step;
                if(blocked(xx,zz,sites,5)||blocked((x+xx)/2.0,(z+zz)/2.0,sites,5))continue;
                double side=Math.abs((xx-from.x)*dz-(zz-from.z)*dx)/straight;if(side>corridor)continue;
                double progress=((xx-from.x)*dx+(zz-from.z)*dz)/straight;if(progress>straight+2)continue;
                int dh=Math.abs(surface.travelHeight(xx,zz)-surface.travelHeight(x,z));
                int turn=Math.min(Math.abs(h-heading),headings-Math.abs(h-heading));
                double cost=Math.hypot(ax,az)*step+slope*dh*dh+turn*turn*1.5+(surface.water(xx,zz)?.35:0)+side*.015+Math.max(0,surface.travelHeight(xx,zz)-92)*.05;
                int v=(vx*n+vz)*headings+h;
                if(dist[u]+cost<dist[v]){dist[v]=dist[u]+cost;prev[v]=u;queue.add(new double[]{v,dist[v]+Math.hypot(vx-ex,vz-ez)*step});}
            }
        }
        if(end<0)throw new IllegalStateException("尺寸/地形约束下无法修路");
        LinkedList<Point> out=new LinkedList<>();for(int v=end;v!=-1;v=prev[v]){int c=v/headings;out.addFirst(new Point(MIN+(c/n)*step,MIN+(c%n)*step));}
        out.addFirst(from);out.addLast(to);return out;
    }
    // A single broad arc crosses flat water directly; no forced mid-point and no seabed chasing.
    private static List<Point> gentleArc(Point from,Point to,double bend){
        double length=from.distance(to),dx=to.x-from.x,dz=to.z-from.z;
        if(length<1)throw new IllegalStateException("道路入口距离过短");
        int count=(int)Math.ceil(length/.65);List<Point> out=new ArrayList<>();
        for(int i=0;i<=count;i++){double t=i/(double)count,offset=bend*Math.pow(Math.sin(Math.PI*t),2);out.add(new Point(from.x+dx*t-dz/length*offset,from.z+dz*t+dx/length*offset));}
        return out;
    }
    private static boolean gentleTerrain(Surface surface,List<Point> curve,List<Site> sites,int endpointHigh){
        Point previous=curve.get(0);int previousY=surface.travelHeight((int)Math.round(previous.x),(int)Math.round(previous.z));
        for(Point q:curve){if(blocked(q.x,q.z,sites,5))return false;int y=surface.travelHeight((int)Math.round(q.x),(int)Math.round(q.z));if(y>endpointHigh+16||Math.abs(y-previousY)>3)return false;previousY=y;}
        return true;
    }
    private static List<Point> simplify(List<Point> p,double tolerance){
        if(p.size()<3)return new ArrayList<>(p);double max=0;int where=0;Point a=p.get(0),b=p.get(p.size()-1);
        for(int i=1;i<p.size()-1;i++){double d=segmentDistance(p.get(i),a,b);if(d>max){max=d;where=i;}}
        if(max<=tolerance)return new ArrayList<>(Arrays.asList(a,b));
        List<Point> out=simplify(p.subList(0,where+1),tolerance);out.remove(out.size()-1);out.addAll(simplify(p.subList(where,p.size()),tolerance));return out;
    }
    private static double segmentDistance(Point p,Point a,Point b){
        double dx=b.x-a.x,dz=b.z-a.z,l=dx*dx+dz*dz,t=l==0?0:Math.max(0,Math.min(1,((p.x-a.x)*dx+(p.z-a.z)*dz)/l));
        return Math.hypot(p.x-a.x-t*dx,p.z-a.z-t*dz);
    }
    private static List<Point> smooth(List<Point> p){
        List<Point> out=simplify(p,2.5);
        for(int k=0;k<4;k++){
            List<Point> next=new ArrayList<>();next.add(out.get(0));
            for(int i=0;i<out.size()-1;i++){Point a=out.get(i),b=out.get(i+1);next.add(new Point(.75*a.x+.25*b.x,.75*a.z+.25*b.z));next.add(new Point(.25*a.x+.75*b.x,.25*a.z+.75*b.z));}
            next.add(out.get(out.size()-1));out=next;
        }return sample(out,.65);
    }
    private static List<Point> sample(List<Point> p,double spacing){
        List<Point> out=new ArrayList<>();out.add(p.get(0));
        for(int i=1;i<p.size();i++){
            Point a=p.get(i-1),b=p.get(i);int count=Math.max(1,(int)Math.ceil(a.distance(b)/spacing));
            for(int j=1;j<=count;j++)out.add(new Point(a.x+(b.x-a.x)*j/count,a.z+(b.z-a.z)*j/count));
        }return out;
    }
    public static Road road(Surface surface,Site a,Site b,List<Site> all,long seed,double maxRatio){
        StructureSpec.Entry ae=a.entrance(b),be=b.entrance(a);Point from=a.outside(ae,10),to=b.outside(be,10);
        double direct=from.distance(to),dx=to.x-from.x,dz=to.z-from.z;
        double side=(TerrainModel.hash(seed^b.id.hashCode())&1)==0?1:-1;
        List<Site> obstacles=new ArrayList<>(all);boolean cut=false;
        List<Point> curve=gentleArc(from,to,side*Math.min(9,direct*.08));
        if(!gentleTerrain(surface,curve,obstacles,Math.max(a.floorY,b.floorY))){
            List<Point> nodes;
            try{
                nodes=astar(surface,from,to,obstacles,14,Math.max(32,direct*.45));
                if(length(nodes)>direct*maxRatio)throw new IllegalStateException("绕行过长");
                for(Point q:nodes)if(surface.travelHeight((int)Math.round(q.x),(int)Math.round(q.z))>Math.max(a.floorY,b.floorY)+28)throw new IllegalStateException("需要自然山口");
            }catch(IllegalStateException e){cut=true;nodes=astar(surface,from,to,obstacles,.08,Math.max(18,direct*.12));}
            curve=smooth(nodes);
        }
        for(Point q:curve)if(blocked(q.x,q.z,obstacles,3.3))throw new IllegalStateException("平滑道路碰到建筑，请调整尺寸或选址");
        if(length(curve)>direct*maxRatio+.01)throw new IllegalStateException("道路绕行超过上限");
        // Round the doorway joins as well as the middle of the path. Fixed endpoints stay on the doors.
        List<Point> combined=new ArrayList<>();combined.add(a.outside(ae,1));combined.add(from);combined.addAll(curve);combined.add(to);combined.add(b.outside(be,1));
        curve=smooth(combined);
        for(Point q:curve){
            if(q.x<MIN+3||q.x>=MAX-3||q.z<MIN+3||q.z>=MAX-3)throw new IllegalStateException("道路越界");
            for(Site site:all)if(site!=a&&site!=b&&site.distanceTo(q.x,q.z)<3.3)throw new IllegalStateException("道路平滑后触及预留建筑");
            if(a.contains((int)Math.round(q.x),(int)Math.round(q.z))||b.contains((int)Math.round(q.x),(int)Math.round(q.z)))throw new IllegalStateException("道路入口弯曲进入建筑");
        }
        Map<Long,RoadCell> cells=RoadSections.pave(surface,a,b,all,curve,cut);
        return new Road(a,b,curve,cells,cut);
    }
    public static double roadDistance(Site site,List<Road> roads){
        double best=Double.POSITIVE_INFINITY;for(Road r:roads)for(RoadCell cell:r.cells.values())best=Math.min(best,site.distanceTo(cell.x,cell.z));return best;
    }
}
