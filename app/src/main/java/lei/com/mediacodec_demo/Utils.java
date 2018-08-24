package lei.com.mediacodec_demo;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;

import java.util.Calendar;

/**
 * Created by xulei on 18-8-23.
 */

public class Utils {
    private static final String TAG = "Utils";

    public static String getRealFilePath(Context context, Uri uri) {
        if (null == uri) return null;
        final String scheme = uri.getScheme();
        String data = null;
        if (scheme == null)
            data = uri.getPath();
        else if (ContentResolver.SCHEME_FILE.equals(scheme)) {
            data = uri.getPath();
        } else if (ContentResolver.SCHEME_CONTENT.equals(scheme)) {
            if (isMediaDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];

                Uri contentUri = null;
                if ("image".equals(type)) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else if ("video".equals(type)) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else if ("audio".equals(type)) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                }

                final String selection = "_id=?";
                final String[] selectionArgs = new String[]{
                        split[1]
                };

                data = getDataColumn(context, contentUri, selection, selectionArgs);
            } else {
                Cursor cursor = context.getContentResolver().query(uri, new String[]{MediaStore.Images.ImageColumns.DATA}, null, null, null);
                if (null != cursor) {
                    if (cursor.moveToFirst()) {
                        int index = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATA);
                        if (index > -1) {
                            data = cursor.getString(index);
                        }
                    }
                    cursor.close();
                }
            }
        }
        return data;
    }

    public static boolean isMediaDocument(Uri uri) {
        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }


    public static String getDataColumn(Context context, Uri uri, String selection, String[] selectionArgs) {
        Cursor cursor = null;
        final String column = "_data";
        final String[] projection = {
                column
        };

        try {
            cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs,
                    null);
            if (cursor != null && cursor.moveToFirst()) {
                final int column_index = cursor.getColumnIndexOrThrow(column);
                return cursor.getString(column_index);
            }
        } finally {
            if (cursor != null)
                cursor.close();
        }
        return null;
    }


    public static String getCurrentTime() {
        Calendar c = Calendar.getInstance();//
        int year = c.get(Calendar.YEAR); // 获取当前年份
        int month = c.get(Calendar.MONTH) + 1;// 获取当前月份
        int day = c.get(Calendar.DAY_OF_MONTH);// 获取当日期
        int way = c.get(Calendar.DAY_OF_WEEK);// 获取当前日期的星期
        int hour = c.get(Calendar.HOUR_OF_DAY);//时
        int minute = c.get(Calendar.MINUTE);//分
        int second = c.get(Calendar.SECOND);//秒
        StringBuilder stringBuilder = new StringBuilder(year);
        String time = stringBuilder
                .append("_" + month)
                .append("_" + day)
                .append("_" + hour)
                .append("_" + minute)
                .append("_" + second)
                .toString();
        return time;
    }


    public static byte[] bitmap2yuv(Bitmap bitmap) {
        Log.d(TAG, "bitmap2yuv");
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] buffer = new int[width * height];
        bitmap.getPixels(buffer, 0, width, 0, 0, width, height);
        return rgb2yuv(buffer, width, height);
    }

    public static byte[] rgb2yuv(int[] argb, int width, int height) {
        Log.d(TAG, "rgb2yuv start.");
        int len = width * height;
        byte[] yuv = new byte[len * 3 / 2];
        int yIndex = 0;
        int uvIndex = len;
        int a, R, G, B, Y, U, V;
        int index = 0;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                // a = (argb[index] & 0xff000000) >> 24;// no use
                R = (argb[index] & 0xff0000) >> 16;
                G = (argb[index] & 0xff00) >> 8;
                B = (argb[index] & 0xff) >> 0;
                Y = (77 * R + 150 * G + 29 * B) >> 8;
                yuv[yIndex++] = (byte) ((Y < 0) ? 0 : ((Y > 255) ? 255 : Y));
                if (j % 2 == 0 && index % 2 == 0) {
                    U = ((-43 * R - 85 * G + 128 * B) >> 8) + 128;
                    V = ((128 * R - 107 * G - 21 * B) >> 8) + 128;
                    yuv[uvIndex++] = (byte) ((V < 0) ? 0 : ((V > 255) ? 255 : V));
                    yuv[uvIndex++] = (byte) ((U < 0) ? 0 : ((U > 255) ? 255 : U));
                }
                index++;
            }
        }
        Log.d(TAG, "return yuv, rgb2yuv end .");
        return yuv;
    }

    public static Bitmap rotateBitmap(Bitmap source, int rotation, boolean recycle) {
        if (rotation == 0 || rotation == 360 || source == null) return source;
        int w = source.getWidth();
        int h = source.getHeight();
        Matrix m = new Matrix();
        m.postRotate(rotation);
        Bitmap bitmap = Bitmap.createBitmap(source, 0, 0, w, h, m, true);
        if (recycle) source.recycle();
        return bitmap;
    }

}
