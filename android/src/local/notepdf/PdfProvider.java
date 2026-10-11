package local.notepdf;
import android.content.*;import android.database.*;import android.net.Uri;import android.os.*;import android.provider.OpenableColumns;import java.io.*;
public final class PdfProvider extends ContentProvider {
 public boolean onCreate(){return true;}
 File file(Uri uri)throws FileNotFoundException{String n=uri.getLastPathSegment();if(n==null||!n.matches("[a-f0-9-]{36}\\.pdf"))throw new FileNotFoundException();File f=new File(getContext().getFilesDir(),"results/"+n);if(!f.isFile())throw new FileNotFoundException();return f;}
 public String getType(Uri uri){return "application/pdf";}
 public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("Read only");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
 public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){try{File f=file(uri);String[] cols=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor c=new MatrixCursor(cols);Object[] row=new Object[cols.length];for(int i=0;i<cols.length;i++)row[i]=OpenableColumns.SIZE.equals(cols[i])?f.length():OpenableColumns.DISPLAY_NAME.equals(cols[i])?"NotePDF.pdf":null;c.addRow(row);return c;}catch(Exception e){return null;}}
 public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
