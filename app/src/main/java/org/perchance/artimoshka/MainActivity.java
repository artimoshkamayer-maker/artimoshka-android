package org.perchance.artimoshka;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Тонкая WebView-оболочка над генератором Artimoshka Ai.
 * Сам генератор грузится из интернета (https://perchance.org/artimoshka-ai),
 * поэтому APK не нужно пересобирать при обновлениях генератора.
 */
public class MainActivity extends Activity {

  private static final String APP_URL = "https://perchance.org/artimoshka-ai";
  private static final int FILE_CHOOSER_REQUEST = 1001;
  private static final int CAMERA_PERMISSION_REQUEST = 1002;

  private WebView webView;
  private ValueCallback<Uri[]> filePathCallback;
  private Uri cameraUri;
  private boolean cameraRequested;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    FrameLayout root = new FrameLayout(this);
    webView = new WebView(this);
    root.addView(webView, new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT));
    setContentView(root);

    WebSettings s = webView.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setDatabaseEnabled(true);
    s.setMediaPlaybackRequiresUserGesture(false);
    s.setAllowFileAccess(true);
    s.setCacheMode(WebSettings.LOAD_DEFAULT);
    s.setUserAgentString(s.getUserAgentString() + " ArtimoshkaApp/1.1");

    webView.addJavascriptInterface(new NativeBridge(), "ArtimoshkaNative");
    webView.setWebViewClient(new WebViewClient() {
      // Локальные файлы для стирания объекта: модель LaMa и движок onnxruntime
      // лежат в assets APK (app/src/main/assets/{model,ort}), страница просит их
      // по адресам https://appassets.androidplatform.net/... — отдаём из APK,
      // интернет для стирания не нужен. Нет файла — возвращаем null, и страница
      // сама откатится на скачивание из сети.
      @Override
      public WebResourceResponse shouldInterceptRequest(WebView view,
                                                        WebResourceRequest request) {
        String url = request.getUrl().toString();
        String prefix = "https://appassets.androidplatform.net/";
        if (url.startsWith(prefix)) {
          String assetPath = url.substring(prefix.length()).split("[?#]")[0];
          try {
            String mime = "application/octet-stream";
            if (assetPath.endsWith(".mjs") || assetPath.endsWith(".js")) {
              mime = "text/javascript";
            } else if (assetPath.endsWith(".wasm")) {
              mime = "application/wasm";
            }
            return new WebResourceResponse(mime, null,
                MainActivity.this.getAssets().open(assetPath));
          } catch (Exception e) {
            return null;
          }
        }
        return null;
      }

      @Override
      public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        String url = request.getUrl().toString();
        if (url.contains("perchance.org")) return false;
        try {
          startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
          Toast.makeText(MainActivity.this, "Нет приложения для ссылки", Toast.LENGTH_SHORT).show();
        }
        return true;
      }
    });
    webView.setWebChromeClient(new WebChromeClient() {
      @Override
      public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                       FileChooserParams params) {
        if (filePathCallback != null) filePathCallback.onReceiveValue(null);
        filePathCallback = callback;
        cameraRequested = false;
        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
          ActivityCompat.requestPermissions(MainActivity.this,
              new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
          return true;
        }
        openImagePicker();
        return true;
      }
    });
    webView.setDownloadListener(new DownloadListener() {
      @Override
      public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                  String mimeType, long contentLength) {
        try {
          DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
          String name = "artimoshka-ai-" + System.currentTimeMillis() + ".jpg";
          req.setTitle(name);
          req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
          req.setNotificationVisibility(
              DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
          ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
          Toast.makeText(MainActivity.this, "Скачиваю в «Загрузки»", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
          Toast.makeText(MainActivity.this, "Не удалось скачать", Toast.LENGTH_SHORT).show();
        }
      }
    });

    if (savedInstanceState != null) webView.restoreState(savedInstanceState);
    else webView.loadUrl(APP_URL);
  }

  private void openImagePicker() {
    cameraUri = null;
    Intent gallery = new Intent(Intent.ACTION_PICK,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
    gallery.setType("image/*");

    Intent chooser;
    if (cameraRequested || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        == PackageManager.PERMISSION_GRANTED) {
      try {
        File dir = new File(getCacheDir(), "camera");
        if (!dir.exists()) dir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File photo = new File(dir, "shot_" + stamp + ".jpg");
        cameraUri = FileProvider.getUriForFile(this,
            "org.perchance.artimoshka.fileprovider", photo);
        Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        camera.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
        camera.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        chooser = Intent.createChooser(gallery, "Выберите фото");
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
      } catch (Exception e) {
        cameraUri = null;
        chooser = Intent.createChooser(gallery, "Выберите фото");
      }
    } else {
      chooser = Intent.createChooser(gallery, "Выберите фото");
    }
    try {
      startActivityForResult(chooser, FILE_CHOOSER_REQUEST);
    } catch (ActivityNotFoundException e) {
      if (filePathCallback != null) {
        filePathCallback.onReceiveValue(null);
        filePathCallback = null;
      }
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                         int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == CAMERA_PERMISSION_REQUEST) {
      cameraRequested = grantResults.length > 0
          && grantResults[0] == PackageManager.PERMISSION_GRANTED;
      openImagePicker();
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode != FILE_CHOOSER_REQUEST || filePathCallback == null) return;
    Uri[] results = null;
    if (resultCode == RESULT_OK) {
      if (data != null && data.getData() != null) {
        results = new Uri[]{data.getData()};
      } else if (cameraUri != null) {
        results = new Uri[]{cameraUri};
      }
    }
    cameraUri = null;
    filePathCallback.onReceiveValue(results);
    filePathCallback = null;
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    if (webView != null) webView.saveState(outState);
  }

  @Override
  public void onBackPressed() {
    if (webView != null && webView.canGoBack()) webView.goBack();
    else super.onBackPressed();
  }

  @Override
  protected void onDestroy() {
    if (webView != null) webView.destroy();
    super.onDestroy();
  }

  /** Мост для сохранения картинок: страница вызывает ArtimoshkaNative.saveImage(name, dataUrl). */
  class NativeBridge {
    @JavascriptInterface
    public String saveImage(String name, String dataUrl) {
      try {
        if (name == null || name.isEmpty()) name = "artimoshka-ai.jpg";
        if (dataUrl == null || !dataUrl.startsWith("data:")) return "error:нет данных";
        int comma = dataUrl.indexOf(',');
        String mime = "image/jpeg";
        try {
          String meta = dataUrl.substring(5, comma);
          String[] parts = meta.split(";");
          if (parts.length > 0 && parts[0].contains("/")) mime = parts[0];
        } catch (Exception ignored) {}
        byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          ContentValues v = new ContentValues();
          v.put(MediaStore.Downloads.DISPLAY_NAME, name);
          v.put(MediaStore.Downloads.MIME_TYPE, mime);
          v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
          Uri uri = getContentResolver()
              .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
          if (uri == null) return "error:нет доступа к Загрузкам";
          try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) return "error:нет доступа к Загрузкам";
            os.write(bytes);
          }
        } else {
          if (ContextCompat.checkSelfPermission(MainActivity.this,
              Manifest.permission.WRITE_EXTERNAL_STORAGE)
              != PackageManager.PERMISSION_GRANTED) {
            return "error:нет разрешения на запись";
          }
          File dir = Environment.getExternalStoragePublicDirectory(
              Environment.DIRECTORY_DOWNLOADS);
          if (!dir.exists()) dir.mkdirs();
          File out = new File(dir, name);
          try (OutputStream os = new java.io.FileOutputStream(out)) {
            os.write(bytes);
          }
          sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
              Uri.fromFile(out)));
        }
        return "ok:" + name;
      } catch (Exception e) {
        String msg = e.getMessage();
        return "error:" + (msg == null ? "неизвестная ошибка" : msg);
      }
    }
  }
}
