package local.notepdf;
import android.app.*;import android.content.*;import android.net.Uri;import android.os.*;import android.graphics.pdf.PdfRenderer;import android.graphics.*;import java.io.*;import java.util.*;import java.util.concurrent.*;
public class SmokeTests extends Instrumentation {
 ConvertService service;Activity activity;Bundle args;StringBuilder report=new StringBuilder();
 @Override public void onCreate(Bundle b){super.onCreate(b);args=b;start();}
 void check(boolean ok,String text){if(!ok)throw new AssertionError(text);report.append("PASS: ").append(text).append('\n');}
 @Override public void onStart(){Bundle result=new Bundle();try{
  activity=startActivitySync(new Intent().setClassName("local.notepdf","local.notepdf.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  CountDownLatch latch=new CountDownLatch(1);Context ctx=getTargetContext();
  ServiceConnection c=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder b){service=((ConvertService.LocalBinder)b).get();latch.countDown();}public void onServiceDisconnected(ComponentName n){}};
  runOnMainSync(()->ctx.bindService(new Intent(ctx,ConvertService.class),c,Context.BIND_AUTO_CREATE));check(latch.await(10,TimeUnit.SECONDS),"service bound");
  if(args!=null&&"true".equals(args.getString("restore"))){check(service.jobs.size()>=3,"results restored after process restart");check(service.jobs.stream().anyMatch(j->j.complete&&j.pages==3),"handwriting PDF retained");}
  else {
   ConvertService.Job embedded=convert("embedded.sdocx");check(embedded.complete&&embedded.pages==21,"embedded PDF: 21 pages");
   ConvertService.Job handwritten=convert("handwritten.sdocx");check(handwritten.complete&&handwritten.pages==3,"handwriting: 3 pages");
   Uri shared=Uri.parse("content://local.notepdf.pdf/"+handwritten.id+".pdf");
   try(ParcelFileDescriptor fd=ctx.getContentResolver().openFileDescriptor(shared,"r");PdfRenderer pdf=new PdfRenderer(fd);PdfRenderer.Page page=pdf.openPage(0)){
    Bitmap bm=Bitmap.createBitmap(600,850,Bitmap.Config.ARGB_8888);bm.eraseColor(Color.WHITE);page.render(bm,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);int nonwhite=0;for(int y=0;y<850;y+=4)for(int x=0;x<600;x+=4)if(bm.getPixel(x,y)!=Color.WHITE)nonwhite++;check(nonwhite>200,"PDF provider and rendered page nonempty");bm.recycle();
   }
   File export=new File(ctx.getExternalFilesDir(null),"handwriting-export.pdf");try(InputStream in=ctx.getContentResolver().openInputStream(shared);OutputStream out=new FileOutputStream(export)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}check(export.length()==service.result(handwritten).length(),"PDF export retains exact byte length");
   ConvertService.Job corrupt=convert("corrupt.sdocx");check(!corrupt.complete&&corrupt.status.contains("실패"),"corrupt input rejected without crash");
   int before=service.jobs.size();startJob("handwritten.sdocx");long end=SystemClock.elapsedRealtime()+10000;while(service.jobs.size()==before&&SystemClock.elapsedRealtime()<end)Thread.sleep(100);service.cancel();waitDone(before);check(!service.jobs.get(before).complete,"cancellation discards partial PDF");
   ConvertService.Job recovery=convert("embedded.sdocx");check(recovery.complete,"conversion works after error and cancellation");
   runOnMainSync(()->{((MainActivity)activity).selected=handwritten;((MainActivity)activity).refresh();});
   runOnMainSync(()->activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
   Thread.sleep(1000);
   java.util.concurrent.atomic.AtomicBoolean drawn=new java.util.concurrent.atomic.AtomicBoolean(false);long drawEnd=SystemClock.elapsedRealtime()+60000;while(SystemClock.elapsedRealtime()<drawEnd){runOnMainSync(()->drawn.set(((MainActivity)activity).image.getDrawable()!=null));if(drawn.get())break;Thread.sleep(200);}
   check(drawn.get(),"tablet PDF preview displayed");waitForIdleSync();
   Bitmap screenshot=getUiAutomation().takeScreenshot();if(screenshot!=null){try(OutputStream out=new FileOutputStream(new File(ctx.getExternalFilesDir(null),"tablet-test.png"))){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}screenshot.recycle();}
   check(activity.getResources().getConfiguration().screenWidthDp>=700,"wide tablet layout launched");
   report.append("export=").append(export.getPath()).append('\n');
  }
  runOnMainSync(()->ctx.unbindService(c));result.putString("stream",report.toString());finish(Activity.RESULT_OK,result);
 }catch(Throwable e){result.putString("stream",report+"FAIL: "+e.toString()+"\n");finish(Activity.RESULT_CANCELED,result);}}
 File fixture(String name)throws IOException{File f=new File(getTargetContext().getFilesDir(),name);try(InputStream in=getContext().getAssets().open(name);OutputStream out=new FileOutputStream(f)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}return f;}
 void startJob(String name)throws Exception{File f=fixture(name);ArrayList<Uri> uris=new ArrayList<>();uris.add(Uri.fromFile(f));Intent i=new Intent(getTargetContext(),ConvertService.class).setAction(ConvertService.START).putParcelableArrayListExtra("uris",uris);runOnMainSync(()->getTargetContext().startForegroundService(i));}
 void waitDone(int before)throws Exception{long end=SystemClock.elapsedRealtime()+180000;while(SystemClock.elapsedRealtime()<end){if(service.jobs.size()>before&&!service.busy)return;Thread.sleep(150);}throw new AssertionError("conversion timed out");}
 ConvertService.Job convert(String name)throws Exception{int before=service.jobs.size();startJob(name);waitDone(before);return service.jobs.get(before);}
}
