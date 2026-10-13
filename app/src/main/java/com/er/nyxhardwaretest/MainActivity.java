package com.er.nyxhardwaretest;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.ImageFormat;
import android.graphics.YuvImage;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import android.media.ToneGenerator;
import android.media.AudioManager;
import android.widget.Toast;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import com.google.mlkit.vision.text.Text;
import java.util.ArrayList;
import java.util.Comparator;
import androidx.activity.ComponentActivity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends ComponentActivity {
    private PreviewView preview;
    private LinearLayout root;
    private boolean scannerVisible = false;
    private ToneGenerator tones;
    private String lastDuplicateHU = "";
    private long lastDuplicateTime = 0;
    private int frameNumber = 0;
    private long lastHuChange = 0;
    private TextView status, fields, count;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private BarcodeScanner barcodeScanner;
    private TextRecognizer textRecognizer;
    private DB db;
    private String currentHU = "";
    private String np = "", dado = "", maquina = "", cantidad = "", fecha = "";
    private volatile boolean busy = false; private boolean saved = false;
    private long lastProcessed = 0;
    private static final Pattern HU_CFM = Pattern.compile("\\bCFM[A-Z0-9]{7,18}\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HU_NUM = Pattern.compile("(?<!\\d)5\\d{8,11}(?!\\d)");

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        db = new DB();
        tones = new ToneGenerator(AudioManager.STREAM_MUSIC, 90);
        barcodeScanner = BarcodeScanning.getClient();
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        showList();
    }
    private TextView label(String text, int size) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(size);
        v.setTextColor(Color.rgb(22, 42, 60)); v.setPadding(12,12,12,12); return v;
    }
    private Button button(String text, Runnable action) {
        Button b = new Button(this); b.setText(text); b.setOnClickListener(v -> action.run()); return b;
    }
    private void showList() {
        scannerVisible = false;
        try { ProcessCameraProvider.getInstance(this).addListener(() -> {
            try { ProcessCameraProvider.getInstance(this).get().unbindAll(); } catch(Exception ignored) {}
        }, ContextCompat.getMainExecutor(this)); } catch(Exception ignored) {}
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(14,14,14,14); root.setBackgroundColor(Color.rgb(245,247,250));
        root.addView(label("ETIQUETA SCANNER V4",22));
        root.addView(button("ABRIR ESCÁNER", () -> showScanner()));
        root.addView(button("EXPORTAR CSV", () -> exportCsv()));
        root.addView(label("REGISTROS GUARDADOS: " + db.total(),18));
        ScrollView scroll = new ScrollView(this); LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL); scroll.addView(rows);
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT hu,np,dado,maquina,cantidad,fecha,escaneado FROM etiquetas ORDER BY id DESC LIMIT 250",null)) {
            while (c.moveToNext()) {
                StringBuilder line = new StringBuilder();
                line.append("HU: ").append(c.getString(0)).append("   NP: ").append(c.getString(1));
                line.append("\nDado: ").append(c.getString(2)).append("   Máquina: ").append(c.getString(3));
                line.append("\nCantidad: ").append(c.getString(4)).append("   Fecha: ").append(c.getString(5));
                line.append("\nRegistrado: ").append(c.getString(6));
                TextView row = label(line.toString(),14); rows.addView(row);
                View divider = new View(this); divider.setBackgroundColor(0xffd9e0e6);
                rows.addView(divider,new LinearLayout.LayoutParams(-1,1));
            }
        }
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }
    private void showScanner() {
        scannerVisible = true; currentHU=""; np=""; dado=""; maquina=""; cantidad=""; fecha=""; saved=false;
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(8,8,8,8); root.setBackgroundColor(Color.rgb(245,247,250));
        root.addView(button("← REGRESAR AL LISTADO", () -> showList()));
        preview = new PreviewView(this);
        root.addView(preview,new LinearLayout.LayoutParams(-1,0,6));
        status = label("Esperando etiqueta...",17); root.addView(status);
        fields = label("",15); root.addView(fields);
        count = label("",13); root.addView(count);
        setContentView(root); refresh();
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA},10);
        else startCamera();
    }
    private void playNew() { if(tones != null) tones.startTone(ToneGenerator.TONE_PROP_BEEP,150); }
    private void playDuplicate() {
        if(tones != null) {
            tones.startTone(ToneGenerator.TONE_PROP_NACK,350);
        }
    }
    private void playError() { if(tones != null) tones.startTone(ToneGenerator.TONE_SUP_ERROR,550); }
    @Override public void onBackPressed() {
        if (scannerVisible) showList(); else super.onBackPressed();
    }
    private void refresh() {
        if (!scannerVisible || fields == null) return;
        fields.setText("HU: " + dash(currentHU) + "\nNP: " + dash(np) + "    Dado: " + dash(dado) + "\nMáquina: " + dash(maquina) + "\nCantidad: " + dash(cantidad) + "    Fecha: " + dash(fecha));
        count.setText("Registros guardados: " + db.total());
    }
    private String dash(String s) { return s.isEmpty() ? "—" : s; }
    @Override public void onRequestPermissionsResult(int req,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(req,permissions,grants);
        if (req == 10 && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else status.setText("Se requiere permiso de cámara");
    }
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get(); provider.unbindAll();
                Preview p = new Preview.Builder().build(); p.setSurfaceProvider(preview.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(executor, this::analyze);
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, p, analysis);
            } catch (Exception e) { status.setText("Error cámara: " + e.getMessage()); }
        }, ContextCompat.getMainExecutor(this));
    }
    @androidx.camera.core.ExperimentalGetImage
    private void analyze(ImageProxy proxy) {
        if (!scannerVisible || busy || System.currentTimeMillis()-lastProcessed < 220) {proxy.close();return;}
        if (proxy.getImage()==null) {proxy.close();return;}
        busy = true; lastProcessed = System.currentTimeMillis();
        InputImage image = InputImage.fromMediaImage(proxy.getImage(), proxy.getImageInfo().getRotationDegrees());
        // First pass: native camera frame. Only one frame is processed at a time.
        com.google.android.gms.tasks.Task<List<Barcode>> bt = barcodeScanner.process(image);
        com.google.android.gms.tasks.Task<com.google.mlkit.vision.text.Text> tt = textRecognizer.process(image);
        com.google.android.gms.tasks.Tasks.whenAllComplete(bt,tt).addOnCompleteListener(task -> {
            StringBuilder codes = new StringBuilder();
            if (bt.isSuccessful()) for (Barcode b : bt.getResult())
                if (b.getRawValue()!=null) codes.append(b.getRawValue()).append("\n");
            Text recognized = tt.isSuccessful() ? tt.getResult() : null;
            String original = recognized == null ? "" : recognized.getText();
            String rawCodes = codes.toString();
            // Update field state BEFORE deciding whether another OCR pass is worthwhile.
            main.post(() -> {
                if (!scannerVisible) { proxy.close(); busy=false; return; }
                process(rawCodes, original, recognized);
                // Fallback is useful only while a known, unsaved HU has missing fields.
                // It is never run for a completed or duplicated HU.
                boolean needsMore = !currentHU.isEmpty() && !saved && !allFieldsReady();
                boolean retry = needsMore && (++frameNumber % 4 == 0);
                if (!retry) { proxy.close(); busy=false; return; }
                Bitmap enhanced = null;
                try { enhanced = makeEnhancedBitmap(proxy); } catch(Exception ignored) {}
                proxy.close();
                if (enhanced == null) { busy=false; return; }
                final Bitmap fallback = enhanced;
                InputImage alt = InputImage.fromBitmap(fallback,0);
                textRecognizer.process(alt).addOnCompleteListener(second -> {
                    String alternative = second.isSuccessful() ? second.getResult().getText() : "";
                    fallback.recycle(); busy=false;
                    if (scannerVisible && !saved && !alternative.isEmpty()) {
                        // Do not use the old barcode if another HU has taken over.
                        process(rawCodes, alternative, second.isSuccessful() ? second.getResult() : null);
                    }
                });
            });
        });
    }
    private boolean allFieldsReady() {
        return !np.isEmpty() && !dado.isEmpty() && !maquina.isEmpty()
                && !cantidad.isEmpty() && !fecha.isEmpty();
    }

    // Extract the Y plane (luminance) from YUV_420_888 and increase contrast.
    // This is grayscale, NOT infrared. Rotation comes from CameraX metadata.
    private Bitmap makeEnhancedBitmap(ImageProxy proxy) {
        android.media.Image img = proxy.getImage(); if(img==null) return null;
        android.media.Image.Plane plane = img.getPlanes()[0];
        java.nio.ByteBuffer buffer = plane.getBuffer().duplicate();
        int width=proxy.getWidth(),height=proxy.getHeight();
        int rowStride=plane.getRowStride(),pixelStride=plane.getPixelStride();
        int[] pixels=new int[width*height];
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
            int v=buffer.get(y*rowStride+x*pixelStride)&255;
            // Gentle contrast stretch, preserving thin strokes.
            v=Math.max(0,Math.min(255,(v-128)*13/10+128));
            pixels[y*width+x]=Color.rgb(v,v,v);
        }
        Bitmap bmp=Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);
        int rotation=proxy.getImageInfo().getRotationDegrees();
        if(rotation==0) return bmp;
        Matrix matrix=new Matrix();matrix.postRotate(rotation);
        Bitmap rotated=Bitmap.createBitmap(bmp,0,0,width,height,matrix,true);
        if(rotated!=bmp) bmp.recycle();
        return rotated;
    }
    private void process(String codes,String ocr,Text recognized) {
        String hu = findHU(codes);
        if (hu.isEmpty()) hu = findHU(ocr);
        if (hu.isEmpty()) return; // Never mix fields from an unidentified label.
        if (db.exists(hu)) {
            long now = System.currentTimeMillis();
            if (!hu.equals(lastDuplicateHU) || now-lastDuplicateTime > 6000) {
                lastDuplicateHU=hu; lastDuplicateTime=now;
                status.setText("⚠ ESTE HU YA FUE ESCANEADO: " + hu + "\n" + db.scannedAt(hu));
                status.setTextColor(0xffaa5500); playDuplicate();
            }
            currentHU=hu; np=""; dado=""; maquina=""; cantidad=""; fecha=""; saved=true;
            refresh(); return;
        }
        if (!hu.equals(currentHU)) {
            currentHU=hu; np=""; dado=""; maquina=""; cantidad=""; fecha=""; saved=false;
            status.setText("Leyendo HU: " + hu);
            status.setTextColor(Color.rgb(22,42,60));
        }
        if (saved) { refresh(); return; }
        String text = ocr.toUpperCase(Locale.ROOT).replace('\r','\n');
        String found;
        // Each completed field is locked until the HU changes.
        // Regex and spatial DIE matching are skipped for fields already obtained.
        if (np.isEmpty()) {
            found = match(text,"(?:YPN|PART\\s*(?:NO|NUMBER|N[O°])|NP|P/N)\\s*[:#.-]?\\s*(S?[17]\\d{7,11})");
            if (found.isEmpty()) found = match(text,"(?<![A-Z0-9])([17]\\d{7,9})(?!\\d)");
            if (!found.isEmpty()) np=found;
        }
        if (maquina.isEmpty()) {
            found = match(text,"(?:MACHINE|MAQUINA|MÁQUINA|PROD\\s*LINE|WC)\\s*[:#.-]?\\s*([A-Z0-9]{6,12})");
            if (found.isEmpty()) found = match(text,"\\b([EFG][0-9A-Z]{7,9})\\b");
            if (!found.isEmpty()) maquina=found;
        }
        if (dado.isEmpty()) {
            found = findDie(recognized);
            if (found.isEmpty()) found = match(text,"(?:NO\\.?\\s*DE\\s*DADO|DADO|DIE\\s*#?|MOLD)\\s*[:#.-]?\\s*([A-Z]{1,3}[0-9]{1,3})");
            if (!found.isEmpty()) dado=found;
        }
        if (cantidad.isEmpty()) {
            found = match(text,"(?:QUANTITY|QTY|CANTIDAD|UNIDAD)\\s*[:#.-]?\\s*([0-9][0-9,.]{0,7})");
            if (!found.isEmpty()) cantidad=found.replaceAll("[^0-9]","");
        }
        if (fecha.isEmpty()) {
            // DATE, not INJ DATE; small labels use Fecha de Lote.
            found = match(text,"(?:FECHA\\s*DE\\s*LOTE|(?<!INJ )\\bDATE)\\s*[:#.-]?\\s*([A-Z]{3,9}\\s+\\d{1,2},?\\s+\\d{4}|\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{4}|\\d{4}[/.-]\\d{1,2}[/.-]\\d{1,2})");
            if (!found.isEmpty()) fecha=normalizeDate(found);
        }
        if (allFieldsReady()) {
            boolean inserted = db.insert(currentHU,np,dado,maquina,cantidad,fecha);
            saved=true;
            if (inserted) {
                status.setText("✓ REGISTRO GUARDADO: " + currentHU);
                status.setTextColor(0xff167a39); playNew();
            } else if (db.exists(currentHU)) {
                status.setText("⚠ ESTE HU YA FUE ESCANEADO: " + currentHU);
                status.setTextColor(0xffaa5500); playDuplicate();
            } else {
                status.setText("ERROR AL GUARDAR: " + currentHU);
                status.setTextColor(Color.RED); playError(); saved=false;
            }
        }
        refresh();
    }
    // Prefer nearby OCR lines (right for boxes, below for molding cards).
    private String findDie(Text result) {
        if (result == null) return "";
        ArrayList<Text.Line> lines = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) lines.addAll(block.getLines());
        Pattern dieValue = Pattern.compile("^[A-Z]{1,3}[0-9]{1,3}$");
        for (Text.Line label : lines) {
            String labelText = label.getText().toUpperCase(Locale.ROOT).trim();
            if (!labelText.matches(".*(?:DIE|DADO).*")) continue;
            String inline = match(labelText,"(?:DIE\\s*#?|DADO\\s*:?|NO\\.?\\s*DE\\s*DADO\\s*:?)\\s*([A-Z]{1,3}[0-9]{1,3})");
            if (!inline.isEmpty()) return inline;
            Rect anchor = label.getBoundingBox();
            if (anchor == null) continue;
            Text.Line best = null; double bestScore = Double.MAX_VALUE;
            for (Text.Line candidate : lines) {
                if (candidate == label) continue;
                String value = candidate.getText().toUpperCase(Locale.ROOT).replaceAll("\\s+", "").trim();
                if (!dieValue.matcher(value).matches()) continue;
                Rect r = candidate.getBoundingBox(); if (r == null) continue;
                int dx = r.centerX() - anchor.centerX(); int dy = r.centerY() - anchor.centerY();
                boolean right = dx > 0 && dx < anchor.width()*5 && Math.abs(dy) < Math.max(anchor.height(),r.height())*2;
                boolean below = dy > 0 && dy < anchor.height()*5 && Math.abs(dx) < Math.max(anchor.width(),r.width())*2;
                if (!right && !below) continue;
                double score = Math.hypot(dx,dy); if (score < bestScore) {bestScore=score;best=candidate;}
            }
            if (best != null) return best.getText().toUpperCase(Locale.ROOT).replaceAll("\\s+", "").trim();
        }
        return "";
    }
    private void exportCsv() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE,"Etiquetas_" + new SimpleDateFormat("yyyyMMdd_HHmm",Locale.US).format(new Date()) + ".csv");
        startActivityForResult(intent,42);
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if (requestCode != 42 || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
            if (out == null) throw new IllegalStateException("No se pudo abrir el destino");
            out.write("HU,NP,Dado,Maquina,Cantidad,Fecha,Escaneado\r\n".getBytes(StandardCharsets.UTF_8));
            try (Cursor c = db.getReadableDatabase().rawQuery("SELECT hu,np,dado,maquina,cantidad,fecha,escaneado FROM etiquetas ORDER BY id",null)) {
                while (c.moveToNext()) {
                    StringBuilder line = new StringBuilder();
                    for (int i=0;i<7;i++) {
                        if (i>0) line.append(',');
                        String value = c.getString(i); if(value==null) value="";
                        line.append('"').append(value.replace("\"","\"\"")).append('"');
                    }
                    line.append("\r\n"); out.write(line.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            status.setText("CSV exportado correctamente");
        } catch(Exception e) {status.setText("Error al exportar: " + e.getMessage());}
    }
    private String findHU(String input) {
        String clean = input.toUpperCase(Locale.ROOT).replace("*", "");
        Matcher m=HU_CFM.matcher(clean); if(m.find()) return m.group();
        m=HU_NUM.matcher(clean); return m.find()?m.group():"";
    }
    private String match(String s,String regex) { Matcher m=Pattern.compile(regex,Pattern.CASE_INSENSITIVE).matcher(s); return m.find()?m.group(1).trim():""; }
    private String normalizeDate(String value) {
        String[] formats={"MMM d, yyyy","MMM d yyyy","MMMM d, yyyy","MMMM d yyyy","MM/dd/yyyy","M/d/yyyy","yyyy-MM-dd","dd/MM/yyyy"};
        for(String fmt:formats) try {
            SimpleDateFormat f=new SimpleDateFormat(fmt,Locale.ENGLISH); f.setLenient(false);
            Date d=f.parse(value); if(d!=null) return new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(d);
        } catch(Exception ignored) {}
        return "";
    }
    private void showHistory() {
        Cursor c=db.getReadableDatabase().rawQuery("SELECT hu,np,maquina,fecha FROM etiquetas ORDER BY id DESC LIMIT 30",null);
        StringBuilder s=new StringBuilder();
        while(c.moveToNext()) s.append(c.getString(0)).append(" | ").append(c.getString(1)).append(" | ").append(c.getString(2)).append(" | ").append(c.getString(3)).append("\n");
        c.close(); new android.app.AlertDialog.Builder(this).setTitle("Últimos registros").setMessage(s.length()==0?"Sin registros":s.toString()).setPositiveButton("Cerrar",null).show();
    }
    private class DB extends SQLiteOpenHelper {
        DB(){super(MainActivity.this,"etiquetas_v1.db",null,1);}
        @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE etiquetas (id INTEGER PRIMARY KEY AUTOINCREMENT,hu TEXT NOT NULL UNIQUE,np TEXT NOT NULL,dado TEXT NOT NULL,maquina TEXT NOT NULL,cantidad INTEGER NOT NULL,fecha TEXT NOT NULL,escaneado TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");}
        @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){}
        boolean insert(String hu,String np,String dado,String maquina,String cantidad,String fecha){
            ContentValues v=new ContentValues();v.put("hu",hu);v.put("np",np);v.put("dado",dado);v.put("maquina",maquina);v.put("cantidad",Integer.parseInt(cantidad));v.put("fecha",fecha);
            return getWritableDatabase().insertWithOnConflict("etiquetas",null,v,SQLiteDatabase.CONFLICT_IGNORE)!=-1;
        }
        boolean exists(String hu) {
            try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM etiquetas WHERE hu=? LIMIT 1",new String[]{hu})) {
                return c.moveToFirst();
            }
        }
        String scannedAt(String hu) {
            try(Cursor c=getReadableDatabase().rawQuery("SELECT escaneado FROM etiquetas WHERE hu=? LIMIT 1",new String[]{hu})) {
                return c.moveToFirst()?"Registrado: "+c.getString(0):"";
            }
        }
        int total(){Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM etiquetas",null);int n=c.moveToFirst()?c.getInt(0):0;c.close();return n;}
    }
    @Override protected void onDestroy(){super.onDestroy();barcodeScanner.close();textRecognizer.close();executor.shutdown(); if(tones!=null) tones.release(); db.close();}
}
