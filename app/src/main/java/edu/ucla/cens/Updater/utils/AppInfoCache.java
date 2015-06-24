package edu.ucla.cens.Updater.utils;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.util.Log;


import com.google.gson.Gson;

import edu.ucla.cens.Updater.Database;
import edu.ucla.cens.Updater.model.AppInfoModel;
import edu.ucla.cens.Updater.model.SettingsModel;

/**
 * Maintains list of applications with installation details.
 * This class is a singleton.
 */
public class AppInfoCache extends HashMap<String, AppInfoModel> {
    public static final String TAG = AppInfoCache.class.getSimpleName();

	//private Map<String, AppInfoModel> appMap = new HashMap<String, AppInfoModel>();
	private static final long serialVersionUID = 1L;
	private List<String> idList = new ArrayList<String>();
	private String dataRetrievalError = null;
	public String getDataRetrievalError() {
		return dataRetrievalError;
	}

	public void setDataRetrievalError(String dataRetrievalError) {
		this.dataRetrievalError = dataRetrievalError;
	}

	/**
	 * Singleton instance
	 */
	private static AppInfoCache instance;
	
	private AppInfoCache() {
	}
	
	/**
	 * Retrieves singleton instance.
	 * @return the instance
	 */
	public static synchronized AppInfoCache get() {
		if (instance == null) {
			instance = new AppInfoCache();
		}
		return instance;
	}
	
	public void clear() {
		super.clear();
		idList.clear();
	}
	public void add(AppInfoModel app) {
		put(app.getQualifiedName(), app);
		idList.add(app.getQualifiedName());
	}

	public AppInfoModel getItemAt(int position) {
		if (position >= idList.size() || position < 0) {
			throw new IndexOutOfBoundsException();
		}
		return get(idList.get(position));
	}

	public void resetDataRetrievalError() {
		dataRetrievalError = null;
	}

	public boolean hasDataRetrievalError() {
		return dataRetrievalError != null;
	}

	/**
	 * Refresh installed app info in the cached instances
	 */
	public void refresh() {
		
		for (String packageName: idList) {
			AppInfoModel info = get(packageName);
			try {
				// Get the package's information. If this doesn't throw an
				// exception, then the package must be installed.
				PackageManager packageManager = AppManager.get().getContext().getPackageManager();
				PackageInfo appinfo = packageManager.getPackageInfo(packageName, 0);
				info.installedVersion = appinfo.versionCode;
			} catch(NameNotFoundException e) {
				info.installedVersion = 0;
			}						
			
		}
		
	}
	/**
	 * Load cache from persistent store
	 */
	public void load(Context context) {
		// TODO: implement save
		// perhaps use JSONBeans: https://code.google.com/p/jsonbeans
        List<String> appInfoList = new ArrayList<String>();
//        Object appInfoList;
        SharedPreferences sharedPreferences = context.getSharedPreferences(Database.PACKAGE_PREFERENCES, Context.MODE_PRIVATE);

        Gson json = new Gson();
        String text;
        text = sharedPreferences.getString("appInfoList","{}");
        if(!text.equals("{}")){
            appInfoList = json.fromJson(text,List.class);
            for(String i : appInfoList){
                text=sharedPreferences.getString(i,"");
                AppInfoModel appInfoModel = json.fromJson(text,AppInfoModel.class);
                add(appInfoModel);
            }
        }
        Log.d("values ki real length", String.valueOf(values().size()));

        Log.d("values appInfoList", String.valueOf(appInfoList.size()));

        String appInfoJsonString = SettingsModel.get().getPrefsString("appInfo");
		try {
			JSONObject appInfo = new JSONObject(appInfoJsonString);
			// ...
		} catch (JSONException e) {
			Log.e(TAG, "Can't load appInfo: " + e);
			throw new RuntimeException(e);
		}
	}
	
	public void save(Context context) {
		// TODO: implement load
        Log.d("values length", String.valueOf(values().size()));
        List<String> appInfoList = new ArrayList<String>();
        SharedPreferences sharedPreferences = context.getSharedPreferences(Database.PACKAGE_PREFERENCES, Context.MODE_PRIVATE);
        String text;
        Gson json = new Gson();
//        writeObject(context, "appInfo",appInfoList );
		for (AppInfoModel appInfo: values()) {


            text = json.toJson(appInfo);
            sharedPreferences.edit().putString(appInfo.getQualifiedName(),text).commit();
            appInfoList.add(appInfo.getQualifiedName());
//            Log.d("json is here",text);
//            AppInfoModel info = json.fromJson(text,AppInfoModel.class);
//            Log.d("json is here too",json.toJson(info));

		}
        text = json.toJson(appInfoList);
        sharedPreferences.edit().putString("appInfoList",text).commit();

//
	}


	
	
}
