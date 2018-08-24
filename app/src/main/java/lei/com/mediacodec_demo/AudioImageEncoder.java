package lei.com.mediacodec_demo;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.AsyncTask;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;
import android.view.TextureView;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

public class AudioImageEncoder {
    private static final String TAG = AudioImageEncoder.class.getSimpleName();

    private static final boolean DEBUG = false;

    private static final int MAX_VIDEO_WIDTH = 1920;   // 视频最大宽度
    private static final int MAX_VIDEO_HEIGHT = 1080;   //视频最大高度

    private static final int FRAME_RATE = 30; //frame rate   帧率
    private static final int I_FRAME_INTERVAL = 1; //1 sec between I-Frame  ，这个没有太懂是什么？

    private static final long TIME_OUT_US = 1 * 1000 * 1000; //1 sec  1 秒
    private static final long STAMP_GAP = 500 * 1000; //500ms  ，没懂？

    private String mVideoPath;
    private String mVideoName;
    private int mVideoWidth;
    private int mVideoHeight;

    private int mSampleSize;

    private MediaCodec mMediaCodec;   // 用于访问Android底层的多媒体编解码器
    private MediaMuxer mMediaMuxer;   // 用于将音频和视频进行混合生成多媒体文件。 缺点是目前只能支持一个audiotrack和一个videotrack,而且仅支持mp4输出
    private MediaExtractor mMediaExtractor;   //用于音视频分路,负责将指定类型的媒体文件从文件中找到轨道，并填充到MediaCodec的缓冲区中

    private ProgressDialog mProgressDialog;
    private boolean mCanceled;

    private Context mContext;
    private String mImagePath;   // 图片路径
    private String mImagePath2;   // 图片路径
    private String mAudioPath;   // 音频路径
    private int mRotation;       // 旋转角度
    private Listener mListener;

    public interface Listener {
        public void onPreExecute();

        public void onPostExecute(String path);

        public void onError(Throwable t);

        public void onCanceled();
    }

    private void prepare() throws IOException {   // 正式处理前的准备
        //Get image width and height   [1] 获取图片的宽高
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(mImagePath, options);
        Log.d(TAG, "prepare imageWidth = " + options.outWidth + ", imageHeight = " + options.outHeight);

        //SampleSize            [2]获取图片宽高和最大视频宽高的比例
        mSampleSize = calculateSampleSize(Math.max(options.outWidth, options.outHeight),
                Math.min(options.outWidth, options.outHeight));
        Log.d(TAG, "prepare sampleSize = " + mSampleSize);

        //Get video width and height    [3]根据比例，获取合成视频的宽高
        int videoWidth = options.outWidth / mSampleSize;
        int videoHeight = options.outHeight / mSampleSize;

        if (mRotation == 90 || mRotation == 270) {  // [4]根据宽高比例再次确定宽高
            int tmp = videoWidth;
            videoWidth = videoHeight;
            videoHeight = tmp;
        }

        // ReSize video width and height          // [5]全局赋值
        mVideoWidth = (videoWidth / 16) * 16;
        mVideoHeight = (videoHeight / 16) * 16;

        Log.d(TAG, "prepare videoWidth = " + mVideoWidth + ", videoHeight = " + mVideoHeight + ", mRotation = " + mRotation);

        //Video path         // [6] 确定合成视频的保存路径
        /*
        if (DEBUG) {
            mVideoPath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + GalleryUtils.generateVideoName() + ".mp4";
        } else {
            mVideoPath = mContext.getCacheDir() + "/" + GalleryUtils.generateVideoName() + ".mp4";
        }
        */
        mVideoName = Utils.getCurrentTime() + "_mix_video.mp4";
        mVideoPath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + mVideoName;
        Log.d(TAG, "prepare videoPath = " + mVideoPath);

        //Create video format    [7]创建要编码的视频格式并设置参数
        MediaFormat videoMediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                mVideoWidth, mVideoHeight);
        //bit rate
        videoMediaFormat.setInteger(MediaFormat.KEY_BIT_RATE,
                (int) (mVideoWidth * mVideoHeight * FRAME_RATE * 0.3f));
        //frame rate
        videoMediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        //color format
        videoMediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        //I frame interval
        videoMediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);  //关键帧间隔时间 单位s

        //Create mediaCodec    [8] 使用MediaFormat 创建mMediaCodec，并start
        mMediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        //MediaCodec.createDecoderByType(), 可以看到还有一个
        mMediaCodec.configure(videoMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mMediaCodec.start();

        //Create mediaMuxer     [9]创建一个mMediaMuxer，确定输出的视频格式和视频路径，mMediaMuxer应该是只支持MP4格式输出
        mMediaMuxer = new MediaMuxer(mVideoPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        //Create mediaExtractor    [10] 创建mMediaExtractor，设置要分路的文件
        mMediaExtractor = new MediaExtractor();
        mMediaExtractor.setDataSource(mAudioPath);
    }

    class Task extends AsyncTask<Void, Integer, String> {
        private long MAX_TIME_MS = 2000L;// sec

        private void publish(long delta) {   //publishProgress 之后会调用onProgressUpdate可用于更新对话框
            if (delta >= MAX_TIME_MS) {
                publishProgress(100);
                return;
            }
            publishProgress((int) ((1.0f * delta / MAX_TIME_MS) * 100));
        }

        @Override
        protected String doInBackground(Void... voids) {   //子线程开始处理
            long t = System.currentTimeMillis();
            byte[] frameData = null;
            byte[] frameData2 = null;
            boolean isMore = false;
            int audioTrackIndex = -1;
            MediaFormat audioMediaFormat = null;

            long audioDurationsUs = 0;
            long videoTimeStamp = 0;

            int outAudioTrackIndex = -1;
            int outVideoTrackIndex = -1;

            boolean videoOutputDone = false;
            boolean videoInputDone = false;

            //Create data with image
            try {
                frameData = decodeImage(mImagePath, mSampleSize);    //[1]获取图片的yuv数据
                frameData2 = decodeImage(mImagePath2, mSampleSize);
                isMore = (frameData2 != null);
            } catch (Exception e) {
                Log.e(TAG, "decodeImage failed. " + e.toString());
                closeSilently();
                return null;
            }

            //Get audio track and audio format from audio file    // [2]之前设置的mMediaExtractor 源文件是音频，这里获取音轨，和音频格式，音频时长。
            for (int i = 0; i < mMediaExtractor.getTrackCount(); i++) {
                audioMediaFormat = mMediaExtractor.getTrackFormat(i);
                if (audioMediaFormat.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                    audioTrackIndex = i;
                    audioDurationsUs = audioMediaFormat.getLong(MediaFormat.KEY_DURATION);
                    Log.d("xulei"," audioDurationsUs = "+audioDurationsUs);
                    break;
                }
            }
            if (audioTrackIndex == -1) { //[3] 没有音轨的情况
                Log.e(TAG, "can not find audio track in audio file!");
                closeSilently();
                return null;
            }
            Log.d("xulei"," audioMediaFormat = "+audioMediaFormat.toString());
            //Add audio track     [4] 合成器增加一个 audio track ,感觉可以写到后面处理 audio 的时候
            outAudioTrackIndex = mMediaMuxer.addTrack(audioMediaFormat);

            //Video                                  [5] 先处理video的
            //Encode video data and write to muxer   [6]编码Video数据（没声音）并写入Muxer（最后会和音频一起合成一个有声音的视频文件）
            while (!videoOutputDone) {
                //Canceled
                if (mCanceled) break;
                publish(System.currentTimeMillis() - t);

                //Put data to mediaCodec   [7] 之前按指定格式创建了mMediaCodec，现在把数据写入mediaCodec进行编码 dequeueInputBuffer/queueInputBuffer
                if (!videoInputDone) {
                    int inputBufferIndex = mMediaCodec.dequeueInputBuffer(TIME_OUT_US);   // 获取可用缓冲区的index
                    Log.d("xulei"," Video Encode inputBufferIndex = "+inputBufferIndex);
                    if (inputBufferIndex >= 0) {
                        if (videoTimeStamp >= audioDurationsUs) {    //[7.1]编码的视频长度和音频时长一致了就停掉,可以看到这里指定了 BUFFER_FLAG_END_OF_STREAM
                            mMediaCodec.queueInputBuffer(inputBufferIndex, 0, 0, videoTimeStamp, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            videoInputDone = true;
                        } else {            //[7.2]视频继续编码
                            if(isMore){
                                if(inputBufferIndex%2 ==0){
                                    ByteBuffer inputBuffer = mMediaCodec.getInputBuffer(inputBufferIndex); //获取这个缓冲区的inputbuffer
                                    inputBuffer.clear();
                                    inputBuffer.put(frameData); // 写数据
                                    mMediaCodec.queueInputBuffer(inputBufferIndex, 0, frameData.length, videoTimeStamp, 0);// queueInputBuffer把这个ByteBuffer放回到队列中，这样才能正确释放缓存区
                                }else {
                                    ByteBuffer inputBuffer = mMediaCodec.getInputBuffer(inputBufferIndex); //获取这个缓冲区的inputbuffer
                                    inputBuffer.clear();
                                    inputBuffer.put(frameData2); // 写数据
                                    mMediaCodec.queueInputBuffer(inputBufferIndex, 0, frameData.length, videoTimeStamp, 0);// queueInputBuffer把这个ByteBuffer放回到队列中，这样才能正确释放缓存区
                                }
                            }else {
                                ByteBuffer inputBuffer = mMediaCodec.getInputBuffer(inputBufferIndex); //获取这个缓冲区的inputbuffer
                                inputBuffer.clear();
                                inputBuffer.put(frameData); // 写数据
                                mMediaCodec.queueInputBuffer(inputBufferIndex, 0, frameData.length, videoTimeStamp, 0);// queueInputBuffer把这个ByteBuffer放回到队列中，这样才能正确释放缓存区
                            }
                        }
                        videoTimeStamp += STAMP_GAP;
                    }
                }

                //Get encoded data    [8] 获取编码的数据  dequeueOutputBuffer/releaseOutputBuffer
                MediaCodec.BufferInfo videoInfo = new MediaCodec.BufferInfo();
                int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(videoInfo, TIME_OUT_US);
                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {  //之后尝试
                    Log.d(TAG, "info try again later!");
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { //格式发生改变了
                    MediaFormat mediaFormat = mMediaCodec.getOutputFormat();
                    outVideoTrackIndex = mMediaMuxer.addTrack(mediaFormat);  // 增加一个audio或者video的track都会返回对对饮改动index
                    Log.d(TAG, "------media muxer start-------");
                    mMediaMuxer.start();    //这个时候才开启mMediaMuxer， 注意一下
                } else if (outputBufferIndex < 0) { //其他小于0的情况，这里没这么复杂，暂时没处理了

                } else {
                    ByteBuffer outputBuffer = mMediaCodec.getOutputBuffer(outputBufferIndex); //outputBufferIndex>=0,这里是处理正常能获取到的output数据
                    if ((videoInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) { //Codec-specific数据需要指定成csd-0
                        MediaFormat mediaFormat = mMediaCodec.getOutputFormat();
                        mediaFormat.setByteBuffer("csd-0", outputBuffer);
                    } else if ((videoInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {  //获取的输出buffer已经是最后的了
                        videoOutputDone = true;
                    } else {
                        if (videoInfo.size != 0) {
                            outputBuffer.position(videoInfo.offset);
                            outputBuffer.limit(videoInfo.offset + videoInfo.size);
                            //Log.d(TAG, "writeSampleData video : outVideoTrackIndex = " + outVideoTrackIndex + ", presentationTimeUs = " + videoInfo.presentationTimeUs + ", size = " + videoInfo.size);
                            mMediaMuxer.writeSampleData(outVideoTrackIndex, outputBuffer, videoInfo); //写数据到 video track中
                        }
                    }
                    mMediaCodec.releaseOutputBuffer(outputBufferIndex, false); //释放缓冲区
                }
            }
            // 总结一下， 上面其实就是编码一段数据，取出来一段数据，把取出来的数据放到mMediaMuxer里面去。
            // 其实这里，用到mediaCodec的原因是 图片要转化成视频， 因为mMediaMuxer是用来将音频视频合成的

            //Audio                                [9]处理音频数据，要放到mMediaMuxer中，这里音频不需要那么复杂，可以直接从音频文件中使用mMediaExtractor将audio track分出来，给Muxer使用
            //Start read audio data from audio file
            ByteBuffer audioByteBuffer = ByteBuffer.allocate(10 * 1024);  // 创建一个容量为10*1024字节的ByteBuffer
            MediaCodec.BufferInfo audioBufferInfo = new MediaCodec.BufferInfo();
            mMediaExtractor.selectTrack(audioTrackIndex);  //选择当时add 的 audio track
            while (true) {     //这里的while循环其实就是， 每次去mMediaExtractor读 10 * 1024大小的数据写到mMediaMuxer的音轨通路里面，知道size < 0 break
                //Canceled
                if (mCanceled) break;
                publish(System.currentTimeMillis() - t);

                int size = mMediaExtractor.readSampleData(audioByteBuffer, 0); // 读取数据到audioByteBuffer中
                if (size < 0) {
                    mMediaExtractor.unselectTrack(audioTrackIndex);
                    break;
                }
                long presentationTimeUs = mMediaExtractor.getSampleTime();   //返回当前的时间戳
                int flags = mMediaExtractor.getSampleFlags();   //返回当前的 flag
                mMediaExtractor.advance();   //读取下一帧数据

                audioBufferInfo.offset = 0;
                audioBufferInfo.size = size;
                audioBufferInfo.presentationTimeUs = presentationTimeUs;
                audioBufferInfo.flags = flags;
                //Write audio to out file
                //Log.d(TAG, "writeSampleData audio : outAudioTrackIndex = " + outAudioTrackIndex + ", presentationTimeUs = " + audioBufferInfo.presentationTimeUs + ", size = " + audioBufferInfo.size);
                mMediaMuxer.writeSampleData(outAudioTrackIndex, audioByteBuffer, audioBufferInfo);
            }

            try {
                close();
            } catch (Exception ex) {
                return null;
            }

            while (System.currentTimeMillis() - t < MAX_TIME_MS) {  // 每过20ms就更新一下进度条
                //Canceled
                if (mCanceled) return null;
                publish(System.currentTimeMillis() - t);
                try {
                    Thread.sleep(20);
                } catch (Exception e) {
                }
            }
            publish(MAX_TIME_MS);

            //return GalleryUtils.transFileToContentUri(mContext, new File(mVideoPath));
            return mVideoPath;
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            if (mProgressDialog != null) mProgressDialog.setProgress(values[0]);
        }

        @Override
        protected void onPostExecute(String path) {
            if (mCanceled) {
                AudioImageEncoder.this.onCanceled();
                return;
            }
            if (path != null) {
                Log.d(TAG, "encode success");
                AudioImageEncoder.this.onPostExecute(path);
            } else {
                Log.e(TAG, "encode error");
                AudioImageEncoder.this.onError(new Exception("some error happened, please check."));
            }
        }
    }

    private void onPreExecute() {
        mProgressDialog = new ProgressDialog(mContext);
        mProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        mProgressDialog.setCancelable(false);
        mProgressDialog.setCanceledOnTouchOutside(false);
        mProgressDialog.setTitle(R.string.processing);
        mProgressDialog.setMax(100);
        mProgressDialog.setProgress(0);
        mProgressDialog.setButton(ProgressDialog.BUTTON_NEGATIVE, mContext.getString(R.string.cancel), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                mCanceled = true;
            }
        });
        mProgressDialog.show();
        if (mListener != null) {
            mListener.onPreExecute();
        }
    }

    private void onPostExecute(String path) {
        if (mProgressDialog != null) mProgressDialog.dismiss();
        if (mListener != null) {
            mListener.onPostExecute(path);
        }
    }

    private void onError(Throwable t) {
        if (mVideoPath != null) {
            delete(mVideoPath);
        }
        if (mProgressDialog != null) mProgressDialog.dismiss();
        if (mListener != null) {
            mListener.onError(t);
        }
    }

    private void onCanceled() {
        if (mVideoPath != null) {
            delete(mVideoPath);
        }
        if (mProgressDialog != null) mProgressDialog.dismiss();
        if (mListener != null) {
            mListener.onCanceled();
        }
    }

    private void startEncode() {
        try {
            prepare();  // 数据准备[重要]
        } catch (Exception e) {
            closeSilently();
            onError(e);
            return;
        }

        Task task = new Task();   //没有异常则开启子线程进行处理，这里用的是asyncTask
        task.execute();
    }

    private void delete(String file) {
        try {
            new File(file).delete();
        } catch (Exception e) {
        }
    }

    private void closeSilently() {
        try {
            close();
        } catch (Exception e) {
        }
    }

    private void close() {
        Log.d(TAG, "close");
        if (mMediaCodec != null) {
            mMediaCodec.stop();
            mMediaCodec.release();
            mMediaCodec = null;
        }
        if (mMediaMuxer != null) {
            mMediaMuxer.stop();
            mMediaMuxer.release();
            mMediaMuxer = null;
        }
        if (mMediaExtractor != null) {
            mMediaExtractor.release();
            mMediaExtractor = null;
        }
    }

    private AudioImageEncoder(Builder builder) {  //[私有的]构造方法
        this.mContext = builder.mContext;
        this.mImagePath = builder.mImagePath;
        this.mImagePath2 = builder.mImagePath2;
        this.mAudioPath = builder.mAudioPath;
        this.mRotation = builder.mRotation;
        this.mListener = builder.mListener;
    }

    public static Builder with(Context context) {
        return new Builder(context);
    }

    public static class Builder {  // 这里使用的build模式
        private Context mContext;
        private String mImagePath;
        private String mImagePath2;
        private String mAudioPath;
        private int mRotation;
        private Listener mListener;

        private Builder(Context context) {
            mContext = context;
        }

        public Builder load(String imagePath, String audioPath) {
            Log.d(TAG, "load imagePath = " + imagePath + ", audioPath = " + audioPath);
            this.mImagePath = imagePath;
            this.mAudioPath = audioPath;
            return this;
        }
        public Builder load(String imagePath,String imagePath2, String audioPath) {
            Log.d(TAG, "load imagePath = " + imagePath + ", audioPath = " + audioPath);
            this.mImagePath = imagePath;
            this.mImagePath2 = imagePath2;
            this.mAudioPath = audioPath;
            return this;
        }

        public Builder rotation(int rotation) {
            this.mRotation = rotation;
            return this;
        }

        public Builder listen(Listener listener) {
            this.mListener = listener;
            return this;
        }

        public void start() {
            AudioImageEncoder encoder = new AudioImageEncoder(this);
            encoder.start();
        }
    }

    private void start() {
        onPreExecute();  // 前期准备，这里主要是弹一个进度对话框

        if (mContext == null
                || mImagePath == null
                || mAudioPath == null) {
            onError(new IllegalArgumentException("Context = " + mContext + ", ImagePath = " + mImagePath + ", AudioPath = " + mAudioPath));
            return;
        }
        if (!(new File(mImagePath).exists())
                || !(new File(mAudioPath).exists())) {
            onError(new RuntimeException("ImageFile or AudioFile not exists!"));
            return;
        }

        startEncode();  // 调用AudioImageEncoder的startEncode开始处理
    }

    private int calculateSampleSize(int imageWidth, int imageHeight) {
        Log.d(TAG, "calculateSampleSize MAX_VIDEO_WIDTH : " + MAX_VIDEO_WIDTH
                + ", imageWidth : " + imageWidth
                + ", MAX_VIDEO_HEIGHT : " + MAX_VIDEO_HEIGHT
                + ", imageHeight : " + imageHeight);
        if (MAX_VIDEO_WIDTH < imageWidth || MAX_VIDEO_HEIGHT < imageHeight) {
            float ratioW = 1.0f * imageWidth / MAX_VIDEO_WIDTH;
            float ratioH = 1.0f * imageHeight / MAX_VIDEO_HEIGHT;
            float sampleSize = Math.max(ratioW, ratioH);
            return sampleSize < 1 ? 1 : (int) (sampleSize + 0.99f);
        }
        return 1;
    }

    private byte[] decodeImage(String img, int sampleSize) throws IOException {
        if (TextUtils.isEmpty(img)) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inJustDecodeBounds = false;
        options.inSampleSize = sampleSize;

        Bitmap bitmap = scaleBitmap(Utils.rotateBitmap(BitmapFactory.decodeFile(img, options), mRotation, true), mVideoWidth, mVideoHeight);

        Log.d(TAG, "decodeImage bitmap width = " + bitmap.getWidth() + ", height = " + bitmap.getHeight());

        return Utils.bitmap2yuv(bitmap);
    }

    private Bitmap scaleBitmap(Bitmap source, int targetW, int targetH) {
        int width = source.getWidth();
        int height = source.getHeight();
        if (targetW > width || targetH > height) {
            return source;
        }
        return Bitmap.createBitmap(source, (width - targetW) / 2, (height - targetH) / 2, targetW, targetH);
    }

}
