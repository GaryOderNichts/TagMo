package com.hiddenramblings.tagmo;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import com.hiddenramblings.tagmo.amiibo.AmiiboManager;

import org.json.JSONException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;

import androidx.core.content.ContextCompat;

import static android.os.Environment.isExternalStorageEmulated;

/**
 * Created by MAS on 31/01/2016.
 */
public class Util {
    final static String TAG = "Util";

    public static final String DATA_DIR = "tagmo";
    public static final String AMIIBO_DATABASE_FILE = "amiibo.json";

    private static File storagePath;

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    public static String md5(byte[] data) {
        try {
            MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
            byte[] result = digest.digest(data);
            return bytesToHex(result);
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, e.getMessage());
        }
        return null;
    }

    public static void dumpLogcat(String fileName) throws Exception {
        File file = new File(fileName);

        Process mLogcatProc;
        BufferedReader reader;
        final StringBuilder log = new StringBuilder();
        String separator = System.getProperty("line.separator");
        log.append(android.os.Build.MANUFACTURER);
        log.append(" ");
        log.append(android.os.Build.MODEL);
        log.append(separator);
        log.append("Android SDK ");
        log.append(Build.VERSION.SDK_INT);
        log.append(" (");
        log.append(Build.VERSION.RELEASE);
        log.append(")");
        log.append(separator);
        log.append("TagMo Version " + BuildConfig.VERSION_NAME);

        try {
            String line;
            mLogcatProc = Runtime.getRuntime().exec(
                    new String[] { "logcat", "-ds", "AndroidRuntime:E" });
            reader = new BufferedReader(new InputStreamReader(
                    mLogcatProc.getInputStream()));
            log.append(separator);
            log.append(separator);
            log.append("AndroidRuntime Logs");
            log.append(separator);
            log.append(separator);
            while ((line = reader.readLine()) != null) {
                log.append(line);
                log.append(separator);
            }
            reader.close();

            mLogcatProc = Runtime.getRuntime().exec(
                    new String[] { "logcat", "-d", "com.hiddenramblings.tagmo" });
            reader = new BufferedReader(new InputStreamReader(
                    mLogcatProc.getInputStream()));
            log.append(separator);
            log.append(separator);
            log.append("TagMo Verbose Logs");
            log.append(separator);
            log.append(separator);
            while ((line = reader.readLine()) != null) {
                log.append(line);
                log.append(separator);
            }
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        FileOutputStream fos = new FileOutputStream(file);
        try {
            fos.write(log.toString().getBytes());
        } finally {
            fos.close();
        }
    }

//    public static void dumpLogcat(String fileName) throws Exception {
//        File file = new File(fileName);
//
//        Process process = Runtime.getRuntime().exec("logcat -d");
//        InputStream logStream = process.getInputStream();
//        try {
//            FileOutputStream fos = new FileOutputStream(file);
//            try {
//                String phoneDetails = String.format("Manufacturer: %s - Model: %s\nAndroid Ver: %s\nTagMo Version: %s\n",
//                    Build.MANUFACTURER, Build.MODEL,
//                    Build.VERSION.RELEASE,
//                    BuildConfig.VERSION_NAME);
//
//                fos.write(phoneDetails.getBytes());
//
//                byte[] buf = new byte[1024];
//                int read = logStream.read(buf);
//                while (read >= 0) {
//                    fos.write(buf, 0, read);
//                    read = logStream.read(buf);
//                }
//            } finally {
//                fos.close();
//            }
//        } finally {
//            logStream.close();
//        }
//    }

    public static void setFileStorage(Context mContext) {
        File[] storage = ContextCompat.getExternalFilesDirs(mContext, null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                storagePath = storage.length > 1 && storage[1] != null
                        && !isExternalStorageEmulated(storage[1]) ? storage[1] : storage[0];
            } catch (IllegalArgumentException | NullPointerException e) {
                // Fallback to internal storage
            }
        }
        storagePath = storage.length > 1 ? storage[1] : storage[0];
    }

    public static File getSDCardDir() {
        if (storagePath != null && storagePath.canRead())
            return storagePath.getParentFile().getParentFile().getParentFile().getParentFile();
        else
            return Environment.getExternalStorageDirectory();
    }

    public static File getDataDir() {
        return new File(Environment.getExternalStorageDirectory(), DATA_DIR);
    }

    public static class AmiiboInfoException extends Exception {
        public AmiiboInfoException(String message) {
            super(message);
        }
    }

    public static AmiiboManager loadDefaultAmiiboManager(Context context) throws IOException, JSONException, ParseException {
        AssetManager assetManager = context.getAssets();
        return AmiiboManager.parse(assetManager.open(AMIIBO_DATABASE_FILE));
    }

    public static AmiiboManager loadAmiiboManager(Context context) throws IOException, JSONException, ParseException {
        AmiiboManager amiiboManager = null;
        try {
            amiiboManager = AmiiboManager.parse(context.openFileInput(AMIIBO_DATABASE_FILE));
        } catch (IOException | JSONException | ParseException e) {
            amiiboManager = null;
            Log.e(TAG, "amiibo parse error", e);
        }
        if (amiiboManager == null) {
            amiiboManager = loadDefaultAmiiboManager(context);
        }

        return amiiboManager;
    }

    public static void saveAmiiboInfo(AmiiboManager amiiboManager, OutputStream outputStream) throws JSONException, IOException {
        OutputStreamWriter streamWriter = null;
        try {
            streamWriter = new OutputStreamWriter(outputStream);
            streamWriter.write(amiiboManager.toJSON().toString());
            outputStream.flush();
        } finally {
            if (streamWriter != null) {
                try {
                    streamWriter.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static void saveLocalAmiiboInfo(Context context, AmiiboManager amiiboManager) throws IOException, JSONException {
        OutputStream outputStream = null;
        try {
            outputStream = context.openFileOutput(Util.AMIIBO_DATABASE_FILE, Context.MODE_PRIVATE);
            Util.saveAmiiboInfo(amiiboManager, outputStream);
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static String friendlyPath(File file) {
        String dirPath = file.getAbsolutePath();
        String sdcardPath = getSDCardDir().getAbsolutePath();
        if (dirPath.startsWith(sdcardPath)) {
            dirPath = dirPath.substring(sdcardPath.length());
        }

        return dirPath;
    }

    public static boolean equals(Object o1, Object o2) {
        if (o1 == o2) {
            return true;
        } else if (o1 == null || o2 == null) {
            return false;
        } else {
            return o1.equals(o2);
        }
    }
}
