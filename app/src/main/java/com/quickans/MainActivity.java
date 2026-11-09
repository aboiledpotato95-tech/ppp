package com.quickans;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONObject;

public class MainActivity extends Activity {
  WebView w; ValueCallback<Uri[]> cb; Uri cam;
  PdfRenderer pr; ParcelFileDescriptor pfd;

  void js(final String s) { runOnUiThread(new Runnable() { @Override public void run() { w.evaluateJavascript(s, null); } }); }
  static String q(String s) { return JSONObject.quote(s == null ? "" : s); }

  // Lets the page turn a PDF into page pictures using Android's own PDF renderer.
  public class Pdf {
    @JavascriptInterface public synchronized int open(String b64) {
      try {
        close();
        File f = new File(getCacheDir(), "in.pdf");
        FileOutputStream o = new FileOutputStream(f);
        o.write(Base64.decode(b64, Base64.DEFAULT));
        o.close();
        pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        pr = new PdfRenderer(pfd);
        return pr.getPageCount();
      } catch (Exception e) { return -1; }
    }
    @JavascriptInterface public synchronized String page(int i) {
      try {
        PdfRenderer.Page p = pr.openPage(i);
        float s = 1400f / p.getWidth();
        if (p.getHeight() * s > 3000f) s = 3000f / p.getHeight();   // keep very tall pages from using too much memory
        int W = Math.max(1, Math.round(p.getWidth() * s));
        int H = Math.max(1, Math.round(p.getHeight() * s));
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.WHITE);
        Matrix m = new Matrix();
        m.postScale(s, s);
        p.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        p.close();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 80, o);
        b.recycle();
        return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
      } catch (Throwable e) { return ""; }
    }
    @JavascriptInterface public String paste() {
      final String[] r = { "" };
      final java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
      runOnUiThread(new Runnable() {
        @Override public void run() {
          try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData d = cm.getPrimaryClip();
            if (d != null && d.getItemCount() > 0) {
              CharSequence t = d.getItemAt(0).coerceToText(MainActivity.this);
              if (t != null) r[0] = t.toString();
            }
          } catch (Throwable e) { }
          l.countDown();
        }
      });
      try { l.await(2, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { }
      return r[0];
    }
    @JavascriptInterface public void copy(final String s) {
      runOnUiThread(new Runnable() {
        @Override public void run() {
          try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("answers", s));
          } catch (Throwable e) { }
        }
      });
    }
    @JavascriptInterface public synchronized void close() {
      try { if (pr != null) pr.close(); if (pfd != null) pfd.close(); } catch (Exception e) { }
      pr = null; pfd = null;
    }
  }

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    w = new WebView(this); setContentView(w);
    w.setBackgroundColor(Color.parseColor("#0d1117")); // dark from the first frame, no white flash
    WebSettings s = w.getSettings();
    s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
    s.setAllowUniversalAccessFromFileURLs(true); // lets the local page call the AI APIs
    w.addJavascriptInterface(new Pdf(), "Android");
    w.setWebViewClient(new WebViewClient());
    w.setWebChromeClient(new WebChromeClient() {
      @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> c, FileChooserParams p) {
        if (cb != null) cb.onReceiveValue(null);
        cb = c;
        try {
          if (p.isCaptureEnabled()) { // "Camera" button
            cam = Uri.parse("content://com.quickans.cam/p" + System.currentTimeMillis() + ".jpg");
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, cam);
            i.setClipData(ClipData.newRawUri("", cam));
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, 2);
          } else {                    // "Photos / files" button: photos, PDF, Word, text; many at once
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
              "image/*", "application/pdf", "text/plain", "application/msword",
              "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
              "application/vnd.oasis.opendocument.text"});
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(Intent.createChooser(i, "Add photos or files"), 1);
          }
        } catch (Exception e) { cb = null; return false; }
        return true;
      }
    });
    w.loadUrl("file:///android_asset/index.html");
  }
  @Override protected void onActivityResult(int r, int c, Intent d) {
    if (cb == null) return;
    if (r == 2) cb.onReceiveValue(c == RESULT_OK ? new Uri[]{cam} : null);
    else cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(c, d));
    cb = null;
  }
  @Override protected void onDestroy() {
    try { if (pr != null) pr.close(); if (pfd != null) pfd.close(); } catch (Exception e) { }
    super.onDestroy();
  }
}
