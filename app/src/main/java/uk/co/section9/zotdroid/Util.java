package uk.co.section9.zotdroid;

import static android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION;
import static androidx.core.content.PermissionChecker.PERMISSION_DENIED;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.app.Activity;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Created by oni on 14/07/2017.
 */

// W3C Date JSON Standard - https://www.w3.org/TR/NOTE-datetime
// https://stackoverflow.com/questions/2597083/illegal-pattern-character-t-when-parsing-a-date-string-to-java-util-date#25979660

public class Util {

    public static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    public static final String DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ssZ";
    public static final String DB_DATE_FORMAT = "yyyy-MM-dd HH:mm:ssZ";


    public static Date jsonStringToDate(String s) {
        // This apparently was supposed to work but really doesnt ><
        /*
        SimpleDateFormat dateFormat = new SimpleDateFormat(DATE_FORMAT, Locale.getDefault());
        // This effectively removes the 'T' that the DB complains about.
        try {
            return dateFormat.parse(s);
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return new Date();*/
        SimpleDateFormat dateFormat = new SimpleDateFormat(DB_DATE_FORMAT, Locale.getDefault());
        try {
            s.replace('T',' ');
            return dateFormat.parse(s);
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return new Date();

    }

    public static String dateToDBString(Date d){
        SimpleDateFormat dateFormat = new SimpleDateFormat(DB_DATE_FORMAT, Locale.getDefault());
        String dd = dateFormat.format(d);
        return dd;
    }

    /**
     * Convert the database string to a java Date - this function actually takes forever so I've
     * decided not to bother with it as I doubt we will order by date yet.
     * @param s
     * @return
     */
    public static Date dbStringToDate(String s) {
        // For some stupid Java reason we need to remove the T in our format
        SimpleDateFormat dateFormat = new SimpleDateFormat(DB_DATE_FORMAT, Locale.getDefault());
        try {
            return dateFormat.parse(s);
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return new Date();
    }

    public static void copyDate(Date from, Date to){
        to.setDate(from.getDate());
        to.setTime(from.getTime());
    }

    public static boolean path_exists(String path) {
        File root_dir = new File(path);
        return root_dir.exists();
    }

    public static String remove_trailing_slash(String path){
        if(path.endsWith("/")){
            path = path.substring(0,path.length()-2);
        }
        return path;
    }

    /**
     * Get the download directory we are using
     * @return
     */

    public static String getDownloadDirectory(Activity activity) {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(activity);
        String download_path = settings.getString("settings_download_location", "");

        if (!path_exists(download_path)) {
            //download_path = Environment.getExternalStorageDirectory().toString() + "/ZotDroid/";
            download_path = activity.getExternalFilesDir(null).getAbsolutePath();
            File root_dir = new File(download_path);
            if (!root_dir.exists()) {
                root_dir.mkdirs();
            }
        }

        // always have a trailing slash
        if(download_path.charAt(download_path.length()-1) != '/') {
            download_path += "/";
        }

        return download_path;
    }

    /**
     * Given an attachmentname, see if it exists in our download folder
     * @param filename
     * @param activity
     * @return
     */
    public static boolean fileExists(String filename, Activity activity){
        String dir = getDownloadDirectory(activity);
        return  path_exists(dir + filename);
    }

}
