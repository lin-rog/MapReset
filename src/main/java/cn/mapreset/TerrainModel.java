package cn.mapreset;

/** Seeded, continuous surface with plains, low hills and occasional mountains. */
public final class TerrainModel {
    private final long seed;
    public TerrainModel(long seed) { this.seed = seed; }
    public int height(int x, int z) {
        double broad = noise(x / 175.0, z / 175.0, 11);
        double plain = 70 + 4 * noise(x / 75.0, z / 75.0, 23) + 1.3 * noise(x / 24.0, z / 24.0, 31);
        double mountains = Math.pow(Math.max(0, (broad - .30) / .70), 2) * 88;
        return (int) Math.round(plain + mountains + Math.max(0, broad) * 7);
    }
    public double noise(double x, double z, long salt) {
        int ix=(int)Math.floor(x), iz=(int)Math.floor(z); double fx=x-ix, fz=z-iz;
        fx=fx*fx*fx*(fx*(fx*6-15)+10); fz=fz*fz*fz*(fz*(fz*6-15)+10);
        double a=unit(ix,iz,salt),b=unit(ix+1,iz,salt),c=unit(ix,iz+1,salt),d=unit(ix+1,iz+1,salt);
        return (a+(b-a)*fx)*(1-fz)+(c+(d-c)*fx)*fz;
    }
    public double unit(int x,int z,long salt) { return ((hash(seed ^ ((long)x*341873128712L) ^ ((long)z*132897987541L) ^ salt)>>>11)*0x1.0p-53)*2-1; }
    public static long hash(long v) {v=(v^(v>>>30))*0xbf58476d1ce4e5b9L;v=(v^(v>>>27))*0x94d049bb133111ebL;return v^(v>>>31);}
    /** 0=stone, 1=coal, 2=redstone, 3=lapis. No caves and no other ores. */
    public int ore(int x,int y,int z) {
        long h=hash(seed ^ ((long)Math.floorDiv(x,3)*57467) ^ ((long)Math.floorDiv(y,2)*10391) ^ ((long)Math.floorDiv(z,3)*789221));
        int v=(int)((h&Long.MAX_VALUE)%1000);
        if(y<128&&v<24)return 1;
        if(y<17&&v>=24&&v<39)return 2;
        if(y<33&&v>=39&&v<47)return 3;
        return 0;
    }
}
