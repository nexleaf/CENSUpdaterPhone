package edu.ucla.cens.Updater.model;


import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import edu.ucla.cens.Updater.utils.AppManager;
import edu.ucla.cens.Updater.utils.RestClient;
import edu.ucla.cens.Updater.utils.Utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Singleton settings model.
 * Provides ability to convert to and from preferences.
 * 
 * Singleton instance is instantiated via SettingsModel.get()
 */
public class SettingsModel {
    public static final String TAG = SettingsModel.class.getSimpleName();

	/**
	 * Singleton instance
	 */
	private static SettingsModel instance;
	
	private SharedPreferences prefs;
	public static final String SHARED_PREFERENCES_NAME = "prefs";

	private static final Map<String, String> PREFS_DEFAULTS = new HashMap<String, String>();
	
	static {
		PREFS_DEFAULTS.put("updateFrequency", "600");
		PREFS_DEFAULTS.put("autoUpdate", "true");
		// app info json string
		PREFS_DEFAULTS.put("appInfo", "{}");
	}
	
    
	private SettingsModel() {
		this.prefs = AppManager.get().getContext().getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);	
	}
	
	/**
	 * Retrieves singleton instance.
	 * @return the instance
	 */
	public static synchronized SettingsModel get() {
		if (instance == null) {
			instance = new SettingsModel();
		}
		return instance;
	}
	
	// ************** Generic accessors ***************
		
	/**
	 * Get preference as double.
	 * @param string
	 */
	public int getPrefsInt(String name) {
		Object oval = getPrefsString(name);
		Log.d(TAG, "getPrefsInt: " + name + "=" + oval);
		int ret = 0;
		try {
			ret = Integer.parseInt((String) oval);
		} catch (NumberFormatException e) {
			Object defaultVal = PREFS_DEFAULTS.get(name);
			Log.e(TAG, "Can't convert " + name + " " + oval + " to int. Will use the default " + defaultVal);
			// use the default to do the same
			try {
				ret = Integer.parseInt((String) defaultVal);
			} catch (NumberFormatException e1) {
				Log.e(TAG, "Internal error converting " + name + " " + defaultVal + " to int. ");
			}
		}
		return ret;
	}
	
	public String getPrefsString(String name) {
		String ovalue = PREFS_DEFAULTS.get(name);
		if (ovalue == null) {
			throw new IllegalArgumentException("Unknown preference name: " + name);
		}
		return prefs.getString(name, ovalue);
	}
	
	public boolean getPrefsBoolean(String name) {
		String sdefaultValue = PREFS_DEFAULTS.get(name);
		boolean defValue = Boolean.parseBoolean(sdefaultValue);
		return prefs.getBoolean(name, defValue);
	}

	// ************** Model specific accessors ***************
	
	/**
	 * Return updateFrequency in ms.
	 * 
	 * @return
	 */
	public int getUpdateFrequencyMillis() {
		int value = getPrefsInt("updateFrequency");
		return value*60000;
	}

    public long getRandomizeOffsetMillis() {
        long ret = 0;
        final String deviceId = Utils.queryDeviceId();
        android.util.Log.d(TAG, "getRandomizeOffsetMillis: deviceId=" + deviceId);
        long seed = Long.parseLong(deviceId, 16);
        Random random = new Random(seed);
        // windows in s.
        final int randomizeWindow = getRandomizeWindow()*60;
        // offset in millis
        ret = random.nextInt(randomizeWindow) * 1000L;
        android.util.Log.d(TAG, "getRandomizeOffsetMillis: seed=" + seed + ", randomizeWindow=" + randomizeWindow + ", offset=" + ret);
        Log.d("jitter final", String.valueOf(ret));
        return ret;
    }

    private int getRandomizeWindow(){
        RestClient client = new RestClient("http://updater.nexleaf.org");
        String result = client.getAsString("https://xytuqrtxok.localtunnel.me/clients/extra_info/352608041908900");
        Log.d("jitter result", result);
        JSONObject resultJSON = null;
        int resultInt = 1;
        try {
            resultJSON= new JSONObject(result);
            resultInt = resultJSON.getInt("jitter");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return resultInt;
    }







    public boolean isAutoInstall() {
		return getPrefsBoolean("autoUpdate");
	}
	
	
}
