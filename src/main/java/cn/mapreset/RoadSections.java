package cn.mapreset;

import java.util.*;
import static cn.mapreset.TerrainPlan.*;

/** One continuous longitudinal profile, projected across the full road width. */
final class RoadSections {
    static Map<Long,RoadCell> pave(Surface surface,Site a,Site b,List<Site> sites,List<Point> curve,boolean cut){
        int n=curve.size();double[] distance=new double[n],raw=new double[n],water=new double[n];
        for(int i=0;i<n;i++){
            Point q=curve.get(i);if(i>0)distance[i]=distance[i-1]+q.distance(curve.get(i-1));
            Point before=curve.get(Math.max(0,i-3)),after=curve.get(Math.min(n-1,i+3));double dx=after.x-before.x,dz=after.z-before.z,len=Math.max(.001,Math.hypot(dx,dz));
            double sum=0;int wet=-1;
            for(int offset=-2;offset<=2;offset++){int x=(int)Math.round(q.x-dz/len*offset),z=(int)Math.round(q.z+dx/len*offset);sum+=surface.travelHeight(x,z)+1;wet=Math.max(wet,surface.waterLevel(x,z));}
            raw[i]=sum/5;water[i]=wet+1;
        }
        double total=distance[n-1];if(total<1)throw new IllegalStateException("道路过短");
        double[] profile=new double[n];
        // A 24-block window suppresses every-block terrain noise without flattening whole hills.
        for(int i=0;i<n;i++){
            double sum=0,weight=0;
            for(int j=0;j<n;j++){double d=Math.abs(distance[j]-distance[i]);if(d>12)continue;double w=13-d;sum+=raw[j]*w;weight+=w;}
            profile[i]=sum/weight;
            if(cut)profile[i]=Math.min(profile[i],a.floorY+(b.floorY-a.floorY)*distance[i]/total+8);
        }
        // Fixed entrance landings, at most one block rise per three horizontal blocks.
        double[] lower=new double[n],upper=new double[n];double slope=Math.max(1.0/3,(Math.abs(b.floorY-a.floorY)+.01)/Math.max(1,total-14));
        if(slope>.5)throw new IllegalStateException("入口高差超出可用坡道长度");
        for(int i=0;i<n;i++){
            double from=Math.max(0,distance[i]-7),to=Math.max(0,total-distance[i]-7);
            upper[i]=Math.min(a.floorY+from*slope,b.floorY+to*slope);
            lower[i]=Math.max(water[i],Math.max(a.floorY-from*slope,b.floorY-to*slope));
        }
        for(int i=1;i<n;i++)lower[i]=Math.max(lower[i],lower[i-1]-(distance[i]-distance[i-1])*slope);
        for(int i=n-2;i>=0;i--)lower[i]=Math.max(lower[i],lower[i+1]-(distance[i+1]-distance[i])*slope);
        for(int i=0;i<n;i++){
            if(lower[i]>upper[i]+.001)throw new IllegalStateException("道路水位/楼层无法在缓坡约束内连接");
            profile[i]=Math.max(lower[i],Math.min(upper[i],profile[i]));
        }
        for(int i=1;i<n;i++)profile[i]=Math.min(profile[i],profile[i-1]+(distance[i]-distance[i-1])*slope);
        for(int i=n-2;i>=0;i--)profile[i]=Math.min(profile[i],profile[i+1]+(distance[i+1]-distance[i])*slope);
        Set<Long> candidates=new LinkedHashSet<>();
        for(Point q:curve)for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)candidates.add(key((int)Math.round(q.x)+dx,(int)Math.round(q.z)+dz));
        Map<Long,RoadCell> cells=new LinkedHashMap<>();
        for(long packed:candidates){
            int x=(int)(packed>>32),z=(int)packed;boolean building=false;for(Site s:sites)if(s.contains(x,z)){building=true;break;}if(building)continue;
            double best=Double.POSITIVE_INFINITY,tBest=0;int index=0;
            for(int i=0;i<n-1;i++){
                Point u=curve.get(i),v=curve.get(i+1);double dx=v.x-u.x,dz=v.z-u.z,len=dx*dx+dz*dz;
                double t=len<1e-10?0:Math.max(0,Math.min(1,((x-u.x)*dx+(z-u.z)*dz)/len));
                double d=Math.hypot(x-u.x-dx*t,z-u.z-dz*t);
                if(d<best){best=d;index=i;tBest=t;}
            }
            // Five-block strip. A 3x3 supercover of the centerline is strictly contained in this radius.
            if(best>2.49)continue;
            if(x<MIN||x>=MAX||z<MIN||z>=MAX)throw new IllegalStateException("路面越界");
            double level=Math.round(2*(profile[index]+(profile[index+1]-profile[index])*tBest))/2d;
            int y=(int)Math.ceil(level);
            Point u=curve.get(Math.max(0,index-4)),v=curve.get(Math.min(n-1,index+5));double dx=v.x-u.x,dz=v.z-u.z;
            RoadCell cell=new RoadCell(x,z,y,5,dx,dz);cell.slab=y-level>.1;cells.put(packed,cell);
        }
        return cells;
    }
}
