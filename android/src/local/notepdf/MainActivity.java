package local.notepdf;

import android.Manifest;
import android.app.*;import android.content.*;import android.content.res.Configuration;import android.graphics.*;import android.graphics.drawable.GradientDrawable;import android.graphics.pdf.PdfRenderer;import android.net.Uri;import android.os.*;import android.view.*;import android.widget.*;import java.io.*;import java.util.*;import java.util.concurrent.*;

public final class MainActivity extends Activity {
 final int INK=Color.rgb(23,40,59),TEAL=Color.rgb(0,128,124),BG=Color.rgb(242,246,248);
 ConvertService service; boolean bound; LinearLayout rows,body;TextView status,previewTitle,pageLabel;Button pick,save,open,stop,prev,next;Spinner mode;ImageView image;
 ConvertService.Job selected;String rendered="",exportId;int pageIndex=0;long generation=0;Bitmap bitmap;
 ExecutorService render=Executors.newSingleThreadExecutor();ArrayList<Uri> incoming;
 final ServiceConnection connection=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder b){service=((ConvertService.LocalBinder)b).get();bound=true;service.listen(()->refresh());handleIncoming();}public void onServiceDisconnected(ComponentName n){bound=false;service=null;}};
 @Override public void onCreate(Bundle state){super.onCreate(state);if(state!=null)exportId=state.getString("exportId");build();accept(getIntent());bindService(new Intent(this,ConvertService.class),connection,BIND_AUTO_CREATE);}
 @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putString("exportId",exportId);}
 @Override public void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);accept(intent);handleIncoming();}
 @Override public void onConfigurationChanged(Configuration config){super.onConfigurationChanged(config);rendered="";build();refresh();}
 @Override protected void onDestroy(){generation++;if(service!=null)service.listen(null);if(bound)unbindService(connection);render.shutdown();super.onDestroy();}
 int dp(float x){return (int)(x*getResources().getDisplayMetrics().density+0.5f);}
 TextView text(String s,int size,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(INK);if(bold)v.setTypeface(null,1);return v;}
 LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
 Button button(String s,Runnable action){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(TEAL);b.setOnClickListener(v->action.run());return b;}
 LinearLayout card(){LinearLayout c=column();c.setPadding(dp(18),dp(18),dp(18),dp(18));GradientDrawable d=new GradientDrawable();d.setColor(Color.WHITE);d.setCornerRadius(dp(20));c.setBackground(d);return c;}
 void build(){
  LinearLayout root=column();root.setBackgroundColor(BG);root.setPadding(dp(20),dp(16),dp(20),dp(12));setContentView(root);
  root.setOnApplyWindowInsetsListener((v,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars());root.setPadding(dp(20)+i.left,dp(12)+i.top,dp(20)+i.right,dp(12)+i.bottom);}return insets;});
  LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.app_icon);head.addView(logo,new LinearLayout.LayoutParams(dp(54),dp(54)));
  LinearLayout title=column();title.setPadding(dp(12),0,0,0);title.addView(text("NotePDF",27,true));title.addView(text("삼성 노트를, 선명한 PDF로.",13,false));head.addView(title,new LinearLayout.LayoutParams(0,-2,1));Button help=button("안내",()->help());head.addView(help);root.addView(head);
  body=new LinearLayout(this);boolean wide=getResources().getConfiguration().screenWidthDp>=700;body.setOrientation(wide?0:1);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,0,1);bp.topMargin=dp(16);root.addView(body,bp);
  LinearLayout files=card();LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(wide?dp(320):-1,wide?-1:dp(240));body.addView(files,fp);
  files.addView(text("변환할 노트",18,true));TextView tip=text(".sdocx 파일 · 인터넷 연결 불필요",12,false);tip.setPadding(0,dp(5),0,dp(8));files.addView(tip);
  ScrollView list=new ScrollView(this);rows=column();list.addView(rows);files.addView(list,new LinearLayout.LayoutParams(-1,0,1));
  mode=new Spinner(this);mode.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"긴 노트: 원래 길이 유지","긴 노트: A4 세로 분할","긴 노트: 삽입 PDF 기준"}));files.addView(mode);
  pick=button("＋ 노트 선택",()->choose());files.addView(pick,new LinearLayout.LayoutParams(-1,dp(48)));
  LinearLayout preview=card();LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(wide?0:-1,wide?-1:0,1);if(wide)pp.leftMargin=dp(14);else pp.topMargin=dp(12);body.addView(preview,pp);
  previewTitle=text("PDF 미리보기",18,true);preview.addView(previewTitle);image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription("변환된 PDF 페이지 미리보기");preview.addView(image,new LinearLayout.LayoutParams(-1,0,1));
  LinearLayout pager=new LinearLayout(this);pager.setGravity(Gravity.CENTER_VERTICAL);prev=button("이전",()->{if(pageIndex>0){pageIndex--;rendered="";showPreview();}});next=button("다음",()->{if(selected!=null&&pageIndex+1<selected.pages){pageIndex++;rendered="";showPreview();}});pageLabel=text("변환 후 확인할 수 있습니다",12,false);pageLabel.setGravity(Gravity.CENTER);pager.addView(prev);pager.addView(pageLabel,new LinearLayout.LayoutParams(0,-2,1));pager.addView(next);preview.addView(pager);
  LinearLayout actions=new LinearLayout(this);save=button("PDF 저장",()->export());open=button("PDF 열기",()->openPDF());actions.addView(save,new LinearLayout.LayoutParams(0,dp(48),1));actions.addView(open,new LinearLayout.LayoutParams(0,dp(48),1));preview.addView(actions);
  status=text("노트를 선택하면 변환을 시작합니다.",13,false);status.setPadding(0,dp(12),0,0);root.addView(status);
  stop=button("변환 취소",()->{if(service!=null)service.cancel();});root.addView(stop);stop.setVisibility(View.GONE);
  TextView foot=text("필기·도형은 벡터로 저장됩니다. 원래 이미지의 해상도는 유지됩니다.",11,false);root.addView(foot);refresh();
 }
 void refresh(){
  if(rows==null)return;rows.removeAllViews();boolean running=service!=null&&service.busy;pick.setEnabled(!running&&bound);mode.setEnabled(!running);stop.setVisibility(running?View.VISIBLE:View.GONE);
  if(service!=null){synchronized(service.jobs){for(ConvertService.Job j:service.jobs){Button row=button(j.name+"\n"+j.status,()->select(j));row.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);row.setTextSize(13);row.setTextColor(j.complete?TEAL:INK);rows.addView(row,new LinearLayout.LayoutParams(-1,-2));}
   if(selected==null&&!service.jobs.isEmpty())selected=service.jobs.get(service.jobs.size()-1);}}
  status.setText(running?"변환 중입니다. 앱을 나가도 알림에서 진행 상태를 확인할 수 있습니다.":"노트를 선택해 변환하세요. 완성된 PDF는 ‘PDF 저장’으로 내보낼 수 있습니다.");showPreview();
 }
 void select(ConvertService.Job j){selected=j;pageIndex=0;rendered="";if(!j.complete&&!j.details.isEmpty())new AlertDialog.Builder(this).setTitle(j.status).setMessage(j.details).setPositiveButton("확인",null).show();showPreview();}
 void showPreview(){
  boolean ready=selected!=null&&selected.complete&&service!=null;save.setEnabled(ready);open.setEnabled(ready);prev.setEnabled(ready&&pageIndex>0);next.setEnabled(ready&&pageIndex+1<selected.pages);
  if(!ready){previewTitle.setText("PDF 미리보기");pageLabel.setText("변환 후 확인할 수 있습니다");image.setImageDrawable(null);rendered="";return;}
  previewTitle.setText(selected.name);pageLabel.setText((pageIndex+1)+" / "+selected.pages);
  final File file=service.result(selected);final String key=selected.id+":"+pageIndex;if(key.equals(rendered))return;rendered=key;long token=++generation;final int page=pageIndex;
  render.execute(()->{Bitmap b=null;try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer pdf=new PdfRenderer(fd);PdfRenderer.Page p=pdf.openPage(page)){
    float scale=Math.min(1800f/p.getWidth(),2400f/p.getHeight());b=Bitmap.createBitmap(Math.max(1,(int)(p.getWidth()*scale)),Math.max(1,(int)(p.getHeight()*scale)),Bitmap.Config.ARGB_8888);b.eraseColor(Color.WHITE);p.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
    final Bitmap result=b;runOnUiThread(()->{if(token!=generation||isDestroyed()){result.recycle();return;}image.setImageBitmap(result);Bitmap old=bitmap;bitmap=result;if(old!=null&&!old.isRecycled())old.recycle();});
  }catch(Exception e){if(b!=null)b.recycle();runOnUiThread(()->{if(token==generation)pageLabel.setText("미리보기를 열지 못했습니다. PDF 열기를 이용해 주세요.");});}});
 }
 void choose(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);startActivityForResult(i,1);}
 void accept(Intent i){if(i==null)return;incoming=new ArrayList<>();if(Intent.ACTION_SEND.equals(i.getAction())){Uri u=i.getParcelableExtra(Intent.EXTRA_STREAM);if(u!=null)incoming.add(u);}else if(Intent.ACTION_SEND_MULTIPLE.equals(i.getAction()))incoming=i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);}
 void handleIncoming(){if(bound&&incoming!=null&&!incoming.isEmpty()){ArrayList<Uri> files=incoming;incoming=null;start(files);}}
 void start(ArrayList<Uri> uris){if(service==null||service.busy){toast("현재 변환이 끝난 뒤 다시 선택해 주세요.");return;}if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},8);
  selected=null;pageIndex=0;rendered="";Intent i=new Intent(this,ConvertService.class).setAction(ConvertService.START).putParcelableArrayListExtra("uris",uris).putExtra("mode",mode.getSelectedItemPosition()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);ClipData clip=ClipData.newRawUri("notes",uris.get(0));for(int n=1;n<uris.size();n++)clip.addItem(new ClipData.Item(uris.get(n)));i.setClipData(clip);startForegroundService(i);
 }
 void export(){if(selected==null||!selected.complete)return;exportId=selected.id;String name=selected.name.replaceFirst("(?i)\\.sdocx$","")+"_고화질.pdf";startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf").putExtra(Intent.EXTRA_TITLE,name),2);}
 void openPDF(){if(selected==null)return;Uri uri=Uri.parse("content://local.notepdf.pdf/"+selected.id+".pdf");try{startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}catch(ActivityNotFoundException e){toast("PDF 뷰어가 없습니다. PDF 저장 후 삼성 노트 등에서 열어 주세요.");}}
 @Override public void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null)return;
  if(request==1){ArrayList<Uri> uris=new ArrayList<>();if(data.getClipData()!=null)for(int n=0;n<data.getClipData().getItemCount();n++)uris.add(data.getClipData().getItemAt(n).getUri());else if(data.getData()!=null)uris.add(data.getData());if(!uris.isEmpty())start(uris);}
  if(request==2&&exportId!=null&&data.getData()!=null){File source=new File(getFilesDir(),"results/"+exportId+".pdf");Uri dest=data.getData();new Thread(()->{try(InputStream in=new FileInputStream(source);OutputStream out=getContentResolver().openOutputStream(dest,"w")){if(out==null)throw new IOException("저장 위치를 열 수 없습니다.");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.flush();runOnUiThread(()->toast("PDF를 저장했습니다."));}catch(Exception e){runOnUiThread(()->toast("저장 실패: "+e.getMessage()));}}).start();}
 }
 void help(){new AlertDialog.Builder(this).setTitle("NotePDF · 갤럭시 탭").setMessage("삼성 노트에서 Samsung Notes 파일(.sdocx)로 저장하거나 공유 → NotePDF를 선택하세요.\n\n필기와 도형을 벡터 PDF로 변환합니다. 타이핑한 본문·일부 펜 효과·그림 도구는 누락되거나 달라질 수 있습니다. 변환 후 원본과 비교하세요.\n\n긴 노트 설정은 페이지 구분이 없는 노트에만 적용됩니다.\n\n원본은 변경하지 않습니다. 네트워크 권한이 없고 파일을 서버로 보내지 않습니다. 결과는 앱 안에 보관되므로 앱을 지우기 전에 PDF 저장을 눌러 내보내세요.\n\n엔진: sdocx2pdf 0.4.0 (MIT). Samsung과 관계없는 독립 앱입니다.").setPositiveButton("확인",null).setNeutralButton("변환 내역",(d,w)->{if(selected!=null)new AlertDialog.Builder(this).setTitle("변환 내역").setMessage(selected.details).setPositiveButton("확인",null).show();}).setNegativeButton("라이선스",(d,w)->licenses()).show();}
 void licenses(){try{String[] names=getAssets().list("Licenses");new AlertDialog.Builder(this).setTitle("오픈소스 라이선스").setItems(names,(d,n)->{try(InputStream in=getAssets().open("Licenses/"+names[n])){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int count;while((count=in.read(b))!=-1)out.write(b,0,count);TextView t=text(out.toString("UTF-8"),12,false);t.setPadding(dp(16),dp(16),dp(16),dp(16));ScrollView v=new ScrollView(this);v.addView(t);new AlertDialog.Builder(this).setTitle(names[n]).setView(v).setPositiveButton("닫기",null).show();}catch(Exception e){toast(e.toString());}}).show();}catch(Exception e){toast(e.toString());}}
 void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}
