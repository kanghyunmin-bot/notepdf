package local.notepdf;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class ConvertService extends Service {
    public static final String START="local.notepdf.START", CANCEL="local.notepdf.CANCEL";
    public static final class Job {
        public String id,name,status="대기 중",details=""; public int pages; public boolean complete;
        public Job(String id,String name){this.id=id;this.name=name;}
    }
    public final class LocalBinder extends Binder {public ConvertService get(){return ConvertService.this;}}
    final ArrayList<Job> jobs=new ArrayList<>();
    final Handler main=new Handler(Looper.getMainLooper());
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    volatile boolean busy=false,cancelled=false; volatile java.lang.Process process;
    Runnable listener;
    public File result(Job j){return new File(new File(getFilesDir(),"results"),j.id+".pdf");}
    @Override public void onCreate(){super.onCreate();new File(getFilesDir(),"results").mkdirs();load();
        NotificationChannel c=new NotificationChannel("convert","노트 변환",NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(c);
    }
    @Override public IBinder onBind(Intent i){return new LocalBinder();}
    public void listen(Runnable r){listener=r;if(r!=null)r.run();}
    void notifyUI(){main.post(()->{if(listener!=null)listener.run();});}
    Notification notification(String text){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ConvertService.class).setAction(CANCEL),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"convert").setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("NotePDF 변환 중").setContentText(text).setContentIntent(open).setOngoing(true).addAction(android.R.drawable.ic_menu_close_clear_cancel,"취소",stop).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null)return START_NOT_STICKY;
        if(CANCEL.equals(intent.getAction())){cancel();return START_NOT_STICKY;}
        if(!START.equals(intent.getAction())||busy)return START_NOT_STICKY;
        ArrayList<Uri> uris=intent.getParcelableArrayListExtra("uris");
        if(uris==null||uris.isEmpty()){stopSelf();return START_NOT_STICKY;}
        int mode=intent.getIntExtra("mode",0);busy=true;cancelled=false;
        startForeground(17,notification("파일을 준비하고 있습니다."));
        worker.execute(()->{
            try {
                for(Uri uri:uris){
                    if(cancelled)break;
                    String name=displayName(uri); Job job=new Job(UUID.randomUUID().toString(),name);
                    synchronized(jobs){jobs.add(job);} notifyUI();
                    File stage=new File(getCacheDir(),job.id);stage.mkdirs();
                    try{
                        if(!name.toLowerCase(Locale.ROOT).endsWith(".sdocx"))throw new IOException("Samsung Notes 파일(.sdocx)을 선택해 주세요.");
                        job.status="파일 준비 중…";notifyUI();
                        File input=new File(stage,"input.sdocx");
                        try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(input)){
                            if(in==null)throw new IOException("원본을 열 수 없습니다.");byte[] b=new byte[65536];long total=0;int n;
                            while((n=in.read(b))!=-1){if(cancelled)throw new IOException("취소되었습니다.");total+=n;if(total>512L*1024*1024)throw new IOException("512MB 이하의 노트만 지원합니다.");out.write(b,0,n);}
                        }
                        if(cancelled)throw new IOException("취소되었습니다.");
                        job.status="벡터 PDF 변환 중…";notifyUI();
                        getSystemService(NotificationManager.class).notify(17,notification(name));
                        File output=new File(stage,"output.pdf"),log=new File(stage,"log.txt");
                        ArrayList<String> cmd=new ArrayList<>();cmd.add(new File(getApplicationInfo().nativeLibraryDir,"libnotepdf_engine.so").getPath());
                        cmd.add("--detailed-errors");if(mode==1){cmd.add("--basic-split");cmd.add("iso-portrait");}if(mode==2)cmd.add("--auto-split");
                        cmd.add(input.getPath());cmd.add(output.getPath());
                        ProcessBuilder pb=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log);
                        pb.environment().put("RAYON_NUM_THREADS","2");
                        process=pb.start();long deadline=SystemClock.elapsedRealtime()+600000;
                        while(!process.waitFor(150,TimeUnit.MILLISECONDS)){
                            if(cancelled||SystemClock.elapsedRealtime()>deadline){process.destroyForcibly();process.waitFor();throw new IOException(cancelled?"취소되었습니다.":"10분을 넘겨 변환을 중단했습니다.");}
                        }
                        job.details=readTail(log,6000);
                        if(cancelled)throw new IOException("취소되었습니다.");
                        if(process.exitValue()!=0)throw new IOException("파일을 변환하지 못했습니다. 원본 형식이나 손상을 확인해 주세요.\n"+job.details);
                        try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(output,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer pdf=new PdfRenderer(fd)){
                            job.pages=pdf.getPageCount();if(job.pages<1)throw new IOException("PDF 페이지가 없습니다.");
                        }
                        if(cancelled)throw new IOException("취소되었습니다.");
                        if(!output.renameTo(result(job)))throw new IOException("완성된 PDF를 보관하지 못했습니다.");
                        job.complete=true;job.status=job.pages+"페이지 · "+(job.details.contains("WARN")||job.details.contains("ERROR")?"완료 · 내용 확인 필요":"완료");
                    }catch(Exception e){job.status=cancelled?"취소됨":"변환 실패";job.details=e.getMessage()==null?e.toString():e.getMessage();}
                    finally{java.lang.Process p=process;if(p!=null&&p.isAlive())p.destroyForcibly();process=null;removeTree(stage);save();notifyUI();}
                }
            }finally{busy=false;stopForeground(STOP_FOREGROUND_REMOVE);notifyUI();stopSelf();}
        });notifyUI();return START_NOT_STICKY;
    }
    public void cancel(){cancelled=true;java.lang.Process p=process;if(p!=null)p.destroyForcibly();notifyUI();}
    @Override public void onDestroy(){cancel();worker.shutdownNow();super.onDestroy();}
    @Override public void onTimeout(int startId,int type){cancel();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    String displayName(Uri uri){try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}String s=uri.getLastPathSegment();return s==null?"note.sdocx":s;}
    static String readTail(File file,int limit)throws IOException{try(RandomAccessFile f=new RandomAccessFile(file,"r")){int n=(int)Math.min(f.length(),limit);f.seek(f.length()-n);byte[] b=new byte[n];f.readFully(b);return new String(b,StandardCharsets.UTF_8);}}
    static void removeTree(File f){File[] children=f.listFiles();if(children!=null)for(File c:children)removeTree(c);f.delete();}
    void save(){try{JSONArray a=new JSONArray();synchronized(jobs){for(Job j:jobs){JSONObject o=new JSONObject();o.put("id",j.id);o.put("name",j.name);o.put("status",j.status);o.put("details",j.details);o.put("pages",j.pages);o.put("complete",j.complete);a.put(o);}}
        android.util.AtomicFile f=new android.util.AtomicFile(new File(getFilesDir(),"jobs.json"));FileOutputStream out=f.startWrite();try{out.write(a.toString().getBytes(StandardCharsets.UTF_8));f.finishWrite(out);}catch(Exception e){f.failWrite(out);}
    }catch(Exception ignored){}}
    void load(){try{File f=new File(getFilesDir(),"jobs.json");if(!f.exists())return;JSONArray a=new JSONArray(readTail(f,2*1024*1024));for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);Job j=new Job(o.getString("id"),o.getString("name"));if(!j.id.matches("[a-f0-9-]{36}"))continue;j.complete=o.optBoolean("complete")&&result(j).exists();j.status=j.complete?o.getString("status"):"이전 작업 미완료 · 파일을 다시 추가하세요";j.pages=o.optInt("pages");j.details=o.optString("details");jobs.add(j);}}catch(Exception ignored){}}
}
