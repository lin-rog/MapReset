package cn.mapreset;
import org.bukkit.*;import java.util.concurrent.*;import java.util.function.*;
final class AsyncChunks {
 @SuppressWarnings("unchecked") static void load(World w,int x,int z,MapResetPlugin p,Consumer<Chunk> done,Consumer<Exception> error){
  try{
   java.lang.reflect.Method method=w.getClass().getMethod("getChunkAtAsync",int.class,int.class,boolean.class);
   CompletableFuture<Chunk> future=(CompletableFuture<Chunk>)method.invoke(w,x,z,true);
   future.whenComplete((chunk,failure)->p.sync(()->{if(failure!=null)error.accept(new Exception(failure));else done.accept(chunk);}));
  }catch(Exception e){error.accept(new IllegalStateException("需要支持getChunkAtAsync的Paper 1.16.5，不使用同步生成降级",e));}
 }
}
