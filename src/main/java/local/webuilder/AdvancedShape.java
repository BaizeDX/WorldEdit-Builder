package local.webuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

final class AdvancedShape {
    final BlockPos a,b,c,low,high;
    final Mode mode;
    final double radius;
    final Vec3 ab,ac,normal;
    final double dominant;

    AdvancedShape(Mode mode, BlockPos a, BlockPos b, BlockPos c) {
        this.mode=mode; this.a=a; this.b=b; this.c=c;
        ab=Vec3.atLowerCornerOf(b.subtract(a)); ac=Vec3.atLowerCornerOf(c.subtract(a));
        normal=ab.cross(ac);
        dominant=Math.max(Math.abs(normal.x),Math.max(Math.abs(normal.y),Math.abs(normal.z)));
        radius=ab.length();
        if (mode.triangle()) {
            if (dominant==0) throw new IllegalArgumentException("三角形需要三个不共线的顶点。");
            low=new BlockPos(Math.min(a.getX(),Math.min(b.getX(),c.getX())),Math.min(a.getY(),Math.min(b.getY(),c.getY())),Math.min(a.getZ(),Math.min(b.getZ(),c.getZ())));
            high=new BlockPos(Math.max(a.getX(),Math.max(b.getX(),c.getX())),Math.max(a.getY(),Math.max(b.getY(),c.getY())),Math.max(a.getZ(),Math.max(b.getZ(),c.getZ())));
        } else {
            if (radius==0) throw new IllegalArgumentException("半径须大于零。");
            int r=(int)Math.round(radius);
            low=new BlockPos(a.getX()-r,mode.sphere()?a.getY()-r:Math.min(a.getY(),c.getY()),a.getZ()-r);
            high=new BlockPos(a.getX()+r,mode.sphere()?a.getY()+r:Math.max(a.getY(),c.getY()),a.getZ()+r);
        }
    }
    static boolean edge(BlockPos p, BlockPos a, BlockPos b) {
        BlockPos delta=b.subtract(a), offset=p.subtract(a);
        long n=length(delta), i=length(offset);
        return i<=n && RegionMath.lineCoordinate(0,delta.getX(),i,n)==offset.getX()
            && RegionMath.lineCoordinate(0,delta.getY(),i,n)==offset.getY()
            && RegionMath.lineCoordinate(0,delta.getZ(),i,n)==offset.getZ();
    }
    static int length(BlockPos p) { return Math.max(Math.abs(p.getX()),Math.max(Math.abs(p.getY()),Math.abs(p.getZ()))); }
    boolean includes(BlockPos p) {
        if (mode.triangle()) {
            if (edge(p,a,b)||edge(p,b,c)||edge(p,c,a)) return true;
            if (mode==Mode.EDGES) return false;
            Vec3 q=Vec3.atLowerCornerOf(p.subtract(a));
            double plane=q.dot(normal);
            if (Math.abs(plane)>dominant*0.5) return false;
            // Snap to the plane along its dominant normal axis before testing barycentric coordinates.
            if (Math.abs(normal.x)==dominant) q=q.add(-plane/normal.x,0,0);
            else if (Math.abs(normal.y)==dominant) q=q.add(0,-plane/normal.y,0);
            else q=q.add(0,0,-plane/normal.z);
            double u=q.cross(ac).dot(normal), v=ab.cross(q).dot(normal);
            return u>=0 && v>=0 && u+v<=normal.lengthSqr();
        }
        double x=Math.abs(p.getX()-a.getX()), z=Math.abs(p.getZ()-a.getZ());
        double y=mode.sphere()?Math.abs(p.getY()-a.getY()):0;
        double squared=x*x+y*y+z*z, limit=(radius+0.5)*(radius+0.5);
        if (squared>=limit) return false;
        if (mode==Mode.SPHERE || mode==Mode.CYLINDER) return true;
        if (mode==Mode.CAPPED && (p.getY()==low.getY() || p.getY()==high.getY())) return true;
        return squared+2*Math.max(x,Math.max(y,z))+1>=limit;
    }
}
