package lei.com.mediacodec_demo;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.content.FileProvider;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;


public class MainActivity extends Activity implements View.OnClickListener {

    private TextView select_audio, select_picture1, select_picture2, compose_video;
    private View compose;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        select_audio = (TextView) findViewById(R.id.select_audio);
        select_picture1 = (TextView) findViewById(R.id.select_picture1);
        select_picture2 = (TextView) findViewById(R.id.select_picture2);
        compose_video = (TextView) findViewById(R.id.compose_video);
        compose = findViewById(R.id.compose);
        select_audio.setOnClickListener(this);
        select_picture1.setOnClickListener(this);
        select_picture2.setOnClickListener(this);
        compose_video.setOnClickListener(this);
        compose.setOnClickListener(this);
        compose_video.setEnabled(false);
        compose.setEnabled(false);
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        switch (id) {
            case R.id.select_audio:
                selectAudio();
                break;
            case R.id.select_picture1:
                selectPicture1();
                break;
            case R.id.select_picture2:
                selectPicture2();
                break;
            case R.id.compose:
                compose();
                break;
            case R.id.compose_video:
                playVideo();
                break;
            default:
                break;
        }
    }

    private static final int AUDIO_REQUEST_INT = 101;
    private static final int PICTURE_REQUEST_INT_1 = 102;
    private static final int PICTURE_REQUEST_INT_2 = 103;

    private void selectAudio() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("audio/*");
        startActivityForResult(intent, AUDIO_REQUEST_INT);
    }

    private void selectPicture1() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/jpeg");
        startActivityForResult(intent, PICTURE_REQUEST_INT_1);
    }

    private void selectPicture2() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/jpeg");
        startActivityForResult(intent, PICTURE_REQUEST_INT_2);
    }

    private void playVideo() {
        Intent intent = new Intent(Intent.ACTION_VIEW);
//        intent.setType("video/*");
//        intent.setDataAndType(Uri.fromFile(new File(compose_video.getText().toString())), "video/*");

        //  这里我们使用fileProvider
        Uri fileUri = FileProvider.getUriForFile(this, "lei.com.mediacodec_demo.provider", new File(compose_video.getText().toString()));//android 7.0以上
        intent.setDataAndType(fileUri, "video/*");
        grantUriPermission(this, fileUri, intent);
        startActivity(intent);
    }

    private static void grantUriPermission (Context context, Uri fileUri, Intent intent) {
        List<ResolveInfo> resInfoList = context.getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
        for (ResolveInfo resolveInfo : resInfoList) {
            String packageName = resolveInfo.activityInfo.packageName;
            context.grantUriPermission(packageName, fileUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
    }

    // 将音频和图片合成视频,这个是重点部分
    private void compose() {
        AudioImageEncoder.with(this)
//                .load(select_picture.getText().toString(), select_audio.getText().toString())
                .load(select_picture1.getText().toString(), select_picture2.getText().toString(),select_audio.getText().toString())
                .rotation(0)
                .listen(new AudioImageEncoder.Listener() {
                    @Override
                    public void onPreExecute() {
                        Log.d("xulei", "AudioImageEncoder onPreExecute");
                    }

                    @Override
                    public void onPostExecute(String path) { //这个已经是在主线程了，这个是AsyncTask的onPostExecute回调过来的
                        MediaScannerConnection.scanFile(MainActivity.this, new String[]{path}, null, null);
                        compose_video.setEnabled(true);
                        compose_video.setText(path);
                    }

                    @Override
                    public void onError(Throwable t) {
                        Log.d("xulei", "AudioImageEncoder onError");
                        Toast.makeText(MainActivity.this, R.string.converse_video_failed, Toast.LENGTH_SHORT).show();
                        t.printStackTrace();
                    }

                    @Override
                    public void onCanceled() {
                        Log.d("xulei", "AudioImageEncoder onCanceled");
                        Toast.makeText(MainActivity.this, R.string.converse_video_cancel, Toast.LENGTH_SHORT).show();
                    }
                })
                .start();
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            Uri uri = data.getData();
            Log.d("xulei", "uri =  " + uri);
            final String realFilePath = Utils.getRealFilePath(MainActivity.this, uri);
            Log.d("xulei", "realFilePath =  " + realFilePath);
            switch (requestCode) {
                case AUDIO_REQUEST_INT:
                    select_audio.setText(realFilePath);
                    updateComposeBtn();
                    break;
                case PICTURE_REQUEST_INT_1:
                    select_picture1.setText(realFilePath);
                    updateComposeBtn();
                    break;
                case PICTURE_REQUEST_INT_2:
                    select_picture2.setText(realFilePath);
                    updateComposeBtn();
                    break;
                default:
                    break;
            }
        }
    }

    private void updateComposeBtn() {
        if (select_audio.getText() != null && select_picture1.getText() != null) {
            compose.setEnabled(true);
        } else {
            compose.setEnabled(false);
        }
    }
}
